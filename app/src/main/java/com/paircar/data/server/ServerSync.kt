package com.paircar.data.server

import com.paircar.domain.model.Attendance
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.model.Place
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.VehicleEnergyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime

/** 서버 연결 상태 (개발 확인용) */
sealed interface ServerSyncState {
    data object Idle : ServerSyncState
    data object Synced : ServerSyncState
    /** 아직 못 보낸 요청이 있다. [reason] 은 마지막 실패 이유 */
    data class Pending(val count: Int, val reason: String) : ServerSyncState
}

/**
 * 앱 상태를 페어카 크루 서버에 맞춘다.
 *
 * 서버에는 아직 일정·매칭이 없어서 오늘 운행과 동승자 목록은 앱(Mock)이 갖고,
 * 서버에는 멤버·차량·크루·장소·계좌·충전 단가와 운행 기록(시작·탑승·하차·구간·종료)을 올린다.
 * 운행 기록은 [ServerStore.outbox] 에 쌓아 두고 발생 순서대로 보낸다. 오프라인이거나 서버가 내려가 있어도
 * 운행은 앱에서 그대로 진행되고, 연결되면 이어서 보낸다.
 *
 * [seedPassengers] (개발용): 동승자는 원래 초대 링크(동승자 웹)로 직접 들어와야 하지만,
 * 앱의 예시 동승자를 서버 크루 멤버로 대신 가입시켜 운행 기록이 끝까지 올라가게 한다.
 */
class ServerSync(
    private val api: PairCarApi,
    private val store: ServerStore,
    /** 오늘 운행·크루 정보를 가진 저장소 (서버 동기화를 하지 않는 원본) */
    private val carpool: CarpoolRepository,
    private val settings: () -> SettlementSettings,
    private val energy: VehicleEnergyRepository,
    private val scope: CoroutineScope,
    private val seedPassengers: Boolean,
    private val log: (String) -> Unit,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val retryDelayMillis: Long = 30_000,
) {

    private val mutex = Mutex()
    private val queueLock = Any()
    private var retryJob: Job? = null

    private val _state = MutableStateFlow<ServerSyncState>(ServerSyncState.Idle)
    val state: StateFlow<ServerSyncState> = _state.asStateFlow()

    /** 앱 시작 때: 서버 준비(멤버·크루 등) 후 남은 요청을 보낸다 */
    fun start() = flushLater()

    // 운행 이벤트 → 요청 쌓기

    suspend fun onTripStarted(trip: CarpoolTrip) {
        val odometer = energy.read().odometerKm
        if (odometer == null) {
            // 서버는 출발 주행거리가 꼭 있어야 한다. 못 읽으면 이 운행은 서버에 올리지 않는다.
            log("주행거리를 못 읽어 운행 ${trip.id} 를 서버에 올리지 않습니다")
            return
        }
        store.rideLog = RideLog(trip.id, roundTenth(odometer), emptyList())
        enqueue(
            TripOp.Start(
                tripKey = trip.id,
                startedAt = ServerTime.format(now()),
                odometerKm = roundTenth(odometer),
                origin = trip.origin.ref(),
                destination = trip.destination.ref(),
                riders = trip.pickupsInOrder.map { RiderRef(it.id, it.pickup.ref(), trip.destination.ref()) },
            ),
        )
    }

    suspend fun onBoardingChanged(trip: CarpoolTrip, passengerId: String, status: BoardingStatus) {
        val ride = store.rideLog?.takeIf { it.tripKey == trip.id } ?: return
        when (status) {
            BoardingStatus.BOARDED, BoardingStatus.DROPPED_OFF -> {
                val boarded = status == BoardingStatus.BOARDED
                val odometer = currentOdometer(ride)
                store.rideLog = ride.copy(events = ride.events + RideEvent(passengerId, boarded, odometer))
                val at = ServerTime.format(now())
                enqueue(
                    if (boarded) TripOp.Board(trip.id, passengerId, at, odometer)
                    else TripOp.Alight(trip.id, passengerId, at, odometer),
                )
            }
            BoardingStatus.NO_SHOW -> enqueue(TripOp.Absent(trip.id, passengerId))
            BoardingStatus.WAITING, BoardingStatus.ARRIVED_AT_PICKUP -> Unit
        }
    }

    fun onAttendanceChanged(trip: CarpoolTrip, passengerId: String, attendance: Attendance) {
        // 출발 전 불참은 운행 시작 요청의 동승자에서 빠지는 것으로 충분하다
        if (attendance != Attendance.ABSENT || store.rideLog?.tripKey != trip.id) return
        enqueue(TripOp.Absent(trip.id, passengerId))
    }

    /** 운행 완료·취소: 구간을 나눠 올리고 운행을 끝낸다 */
    suspend fun onTripEnded(trip: CarpoolTrip) {
        val ride = store.rideLog?.takeIf { it.tripKey == trip.id } ?: return
        val odometer = currentOdometer(ride)
        val (economy, source) = fuelEconomy()
        val segments = buildSegments(ride.startOdometerKm, odometer, ride.events, economy, source)
        if (segments.isNotEmpty()) enqueue(TripOp.Segments(trip.id, segments), flush = false)
        // 앱은 통행료를 아직 모른다
        enqueue(TripOp.End(trip.id, ServerTime.format(now()), odometer, tollFareWon = 0))
        store.rideLog = null
    }

    /** 계좌·충전 단가가 바뀌면 서버에 올리고, 계좌가 없어 멈춘 운행 기록도 이어서 보낸다 */
    fun onSettingsChanged() = flushLater()

    /** QR 초대 코드는 서버가 발급한다 (동승자 웹이 같은 코드로 가입을 확인한다) */
    suspend fun createInvite(): CrewInvite = mutex.withLock {
        if (!ensureReady()) throw IOException("서버에 연결하지 못했어요")
        val invite = api.createInvite(store.crewId!!)
        CrewInvite(
            code = invite.getString("code"),
            url = invite.getString("inviteUrl"),
            expiresAt = ServerTime.toDeviceLocal(invite.getString("expiresAt")),
        )
    }

    // 보내기

    private fun enqueue(op: TripOp, flush: Boolean = true) {
        synchronized(queueLock) { store.outbox = store.outbox + op }
        if (flush) flushLater()
    }

    private fun flushLater() {
        scope.launch(Dispatchers.IO) { flush() }
    }

    suspend fun flush() = mutex.withLock {
        if (!ensureReady()) return@withLock
        while (true) {
            val op = synchronized(queueLock) { store.outbox.firstOrNull() } ?: break
            try {
                send(op)
                dequeue(op)
            } catch (e: ApiException) {
                when {
                    e.status == 401 -> {
                        // 서버 DB 가 초기화돼 토큰이 없어졌다. 새 멤버로 다시 준비한다.
                        log("토큰이 무효라 서버 멤버를 다시 만듭니다")
                        store.reset()
                        pending(e)
                        retryLater(RESET_RETRY_MILLIS)
                        return@withLock
                    }
                    e.code == "BANK_ACCOUNT_REQUIRED" -> {
                        // 계좌를 등록하면(onSettingsChanged) 이어서 보낸다
                        pending(e)
                        return@withLock
                    }
                    e.retryable -> {
                        pending(e)
                        retryLater()
                        return@withLock
                    }
                    else -> {
                        // 다시 보내도 실패하는 요청. 버리고 다음 요청으로 넘어간다.
                        log("서버가 거절한 요청을 버립니다: ${op.javaClass.simpleName} ${op.tripKey} → ${e.message}")
                        if (op is TripOp.Start) store.tripIds = store.tripIds + (op.tripKey to ServerStore.FAILED_TRIP)
                        dequeue(op)
                    }
                }
            } catch (e: Exception) {
                // 응답 형식이 예상과 다르다 (서버 명세 변경 등). 같은 요청이 뒤를 계속 막지 않도록 버린다.
                log("서버 응답을 처리하지 못해 요청을 버립니다: ${op.javaClass.simpleName} ${op.tripKey} → $e")
                if (op is TripOp.Start) store.tripIds = store.tripIds + (op.tripKey to ServerStore.FAILED_TRIP)
                dequeue(op)
            }
        }
        _state.value = ServerSyncState.Synced
    }

    private fun dequeue(op: TripOp) = synchronized(queueLock) {
        val queue = store.outbox
        if (queue.firstOrNull() == op) store.outbox = queue.drop(1)
    }

    private fun pending(e: ApiException) {
        val count = synchronized(queueLock) { store.outbox.size }
        _state.value = ServerSyncState.Pending(count, e.message.orEmpty())
        log("서버 요청 대기 ${count}건: ${e.message}")
    }

    private fun retryLater(delayMillis: Long = retryDelayMillis) {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch(Dispatchers.IO) {
            delay(delayMillis)
            flush()
        }
    }

    private suspend fun send(op: TripOp) {
        if (op is TripOp.Start) {
            if (op.tripKey in store.tripIds) return
            val riders = op.riders.mapNotNull { rider ->
                val crewMemberId = store.crewMemberIds[rider.passengerId]
                if (crewMemberId == null) log("서버 크루 멤버가 아닌 동승자는 빼고 시작합니다: ${rider.passengerId}")
                crewMemberId?.let {
                    JSONObject().put("crewMemberId", it).put("boardPlaceId", placeId(rider.board)).put("alightPlaceId", placeId(rider.alight))
                }
            }
            val body = JSONObject()
                .put("crewId", store.crewId)
                .put("carId", store.carId)
                .put("originPlaceId", placeId(op.origin))
                .put("destinationPlaceId", placeId(op.destination))
                .put("startedAt", op.startedAt)
                .put("startOdometerKm", op.odometerKm)
                .put("riders", JSONArray(riders))
            val tripId = api.startTrip(body).getLong("tripId")
            store.tripIds = store.tripIds + (op.tripKey to tripId)
            return
        }

        val tripId = store.tripIds[op.tripKey]?.takeIf { it != ServerStore.FAILED_TRIP } ?: return
        fun member(passengerId: String) = store.crewMemberIds[passengerId]
        when (op) {
            is TripOp.Board -> member(op.passengerId)?.let { api.board(tripId, it, op.occurredAt, op.odometerKm) }
            is TripOp.Alight -> member(op.passengerId)?.let { api.alight(tripId, it, op.occurredAt, op.odometerKm) }
            is TripOp.Absent -> member(op.passengerId)?.let { api.absent(tripId, it) }
            is TripOp.Segments -> api.uploadSegments(
                tripId,
                JSONArray(
                    op.segments.map { s ->
                        JSONObject()
                            .put("seq", s.seq)
                            .put("startOdometerKm", s.startOdometerKm)
                            .put("endOdometerKm", s.endOdometerKm)
                            .put("fuelEconomy", s.fuelEconomy)
                            .put("fuelEconomySource", s.fuelEconomySource)
                            .put("riderCrewMemberIds", JSONArray(s.riderPassengerIds.mapNotNull(::member)))
                    },
                ),
            )
            is TripOp.End -> api.endTrip(tripId, op.endedAt, op.odometerKm, op.tollFareWon)
            is TripOp.Start -> Unit
        }
    }

    // 준비 (멤버 → 차량 → 크루 → 동승자 → 계좌·단가)

    /** 실패하면 false. 다시 시도는 다음 이벤트나 [retryLater] 때 한다. */
    private suspend fun ensureReady(): Boolean = try {
        prepare()
        true
    } catch (e: ApiException) {
        if (e.status == 401) {
            log("토큰이 무효라 서버 멤버를 다시 만듭니다")
            store.reset()
        }
        pending(e)
        retryLater(if (e.status == 401) RESET_RETRY_MILLIS else retryDelayMillis)
        false
    } catch (e: Exception) {
        log("서버 준비 중 응답을 처리하지 못했습니다: $e")
        _state.value = ServerSyncState.Pending(synchronized(queueLock) { store.outbox.size }, e.toString())
        retryLater()
        false
    }

    private suspend fun prepare() {
        val trip = carpool.todayTrip.value
        val driverName = trip?.driver?.name ?: DEFAULT_NICKNAME

        if (store.token == null) {
            val member = api.createMember(driverName)
            store.token = member.getString("token")
            store.memberId = member.getLong("memberId")
            log("서버 멤버 생성: ${store.memberId}")
        }
        if (store.carId == null) {
            val car = api.registerCar(store.vehicleKey, FUEL_TYPE, settings().efficiencyKmPerKwh ?: DEFAULT_EFFICIENCY)
            store.carId = car.getLong("carId")
        }
        if (store.crewId == null) {
            val crew = api.createCrew(carpool.crew.value?.name ?: trip?.title ?: DEFAULT_CREW_NAME)
            store.crewId = crew.getLong("crewId")
            log("서버 크루 생성: ${store.crewId}")
        }
        if (seedPassengers && trip != null) seed(trip)
        syncSettings(driverName)
    }

    private suspend fun seed(trip: CarpoolTrip) {
        val missing = trip.passengers.filter { it.id !in store.crewMemberIds }
        if (missing.isEmpty()) return
        val code = api.createInvite(store.crewId!!).getString("code")
        missing.forEach { passenger ->
            val joined = api.joinAsNewMember(code, passenger.name)
            store.crewMemberIds = store.crewMemberIds + (passenger.id to joined.getLong("crewMemberId"))
        }
        log("예시 동승자 ${missing.size}명을 서버 크루에 가입시켰습니다")
    }

    private suspend fun syncSettings(holder: String) {
        val current = settings()
        current.account?.let { account ->
            val hash = sha256("${account.bank}|${account.number}|$holder")
            if (hash != store.syncedAccountHash) {
                api.putBankAccount(account.bank, account.number, holder)
                store.syncedAccountHash = hash
            }
        }
        val price = current.chargePricePerKwh ?: DEFAULT_CHARGE_PRICE_WON
        if (price.toString() != store.syncedFuelPrice) {
            api.changeFuelPrice(store.crewId!!, price)
            store.syncedFuelPrice = price.toString()
        }
    }

    private suspend fun placeId(place: PlaceRef): Long {
        store.placeIds[place.key]?.let { return it }
        val id = api.registerPlace(store.crewId!!, place.name, place.latitude, place.longitude).getLong("placeId")
        store.placeIds = store.placeIds + (place.key to id)
        return id
    }

    /** 주행거리는 줄어들면 서버가 거절하므로 앞 값보다 작으면 앞 값을 쓴다 */
    private suspend fun currentOdometer(ride: RideLog): Double {
        val last = ride.events.lastOrNull()?.odometerKm ?: ride.startOdometerKm
        val read = energy.read().odometerKm?.let(::roundTenth) ?: return last
        return maxOf(read, last)
    }

    /** 전비: 차량 값이 있으면 MEASURED, 없으면 운전자 입력·기본값 MANUAL */
    private suspend fun fuelEconomy(): Pair<Double, String> {
        energy.read().evEfficiency?.takeIf { it >= MIN_EFFICIENCY }?.let { return it to "MEASURED" }
        return (settings().efficiencyKmPerKwh ?: DEFAULT_EFFICIENCY) to "MANUAL"
    }

    private fun Place.ref() = PlaceRef(name, point.latitude, point.longitude)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FUEL_TYPE = "EV"
        const val DEFAULT_NICKNAME = "운전자"
        const val DEFAULT_CREW_NAME = "크루"

        /** 정산 화면 기본값과 같은 값 (운전자가 아직 정하지 않았을 때) */
        const val DEFAULT_EFFICIENCY = 5.5
        const val DEFAULT_CHARGE_PRICE_WON = 347

        /** 토큰이 무효라 새 멤버로 다시 준비할 때 */
        const val RESET_RETRY_MILLIS = 1_000L

        /** 서버 최소값 (fuelEconomy ≥ 0.01) */
        const val MIN_EFFICIENCY = 0.01
    }
}
