package com.paircar.data.server

import com.paircar.domain.model.Attendance
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.model.CrewMember
import com.paircar.domain.model.Place
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.VehicleEnergyRepository
import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.CrewLiveEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
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
import java.time.YearMonth

/** 동승자별 남은 시간·거리 (내비 경유지 정보). 못 구했으면 null */
data class RiderRemain(val passengerId: String, val remainSeconds: Int?, val remainMeters: Int?)

/** 서버의 크루 (GET /api/crews/{crewId} + /members) */
data class ServerCrew(val crewId: Long, val name: String, val members: List<CrewMember>)

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

    private val _crew = MutableStateFlow<ServerCrew?>(null)

    /** 서버의 크루 정보와 명단. 아직 못 받았으면 null */
    val crew: StateFlow<ServerCrew?> = _crew.asStateFlow()

    /** 앱 시작 때: 서버 준비(멤버·크루 등) 후 남은 요청을 보내고 크루 명단을 받는다 */
    fun start() {
        scope.launch(Dispatchers.IO) {
            flush()
            refreshCrew()
        }
    }

    /** 크루 이름·명단을 다시 받는다 (초대 링크로 새 동승자가 들어왔을 수 있다) */
    suspend fun refreshCrew() = mutex.withLock {
        if (!ensureReady()) return@withLock
        try {
            loadCrew()
        } catch (e: Exception) {
            log("크루 명단을 받지 못했습니다: $e")
        }
    }

    private suspend fun loadCrew() {
        val crewId = store.crewId ?: return
        val crew = api.crew(crewId)
        val members = api.crewMembers(crewId)
        val passengerOf = store.crewMemberIds.entries.associate { (passengerId, crewMemberId) -> crewMemberId to passengerId }
        val me = store.memberId
        (0 until members.length()).map(members::getJSONObject).firstOrNull { it.getLong("memberId") == me }?.let {
            store.driverCrewMemberId = it.getLong("crewMemberId")
        }
        _crew.value = ServerCrew(
            crewId = crewId,
            name = crew.getString("name"),
            members = (0 until members.length()).map(members::getJSONObject).map { m ->
                val crewMemberId = m.getLong("crewMemberId")
                CrewMember(
                    id = crewMemberId.toString(),
                    name = m.getString("nickname"),
                    isDriver = m.getLong("memberId") == me,
                    joinedAt = ServerTime.toKst(m.getString("joinedAt")),
                    passengerId = passengerOf[crewMemberId],
                )
            }.sortedWith(compareByDescending<CrewMember> { it.isDriver }.thenBy { it.joinedAt }),
        )
    }

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
            // 경유지에 도착했는데 아직 안 탐 → 동승자에게 "차가 도착했어요"
            BoardingStatus.ARRIVED_AT_PICKUP -> scope.launch(Dispatchers.IO) { sendArrived(trip, passengerId) }
            BoardingStatus.WAITING -> Unit
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

    // 실시간 구독 (SSE): 동승자 웹 → 서버 → 차량 앱

    private val _liveEvents = MutableSharedFlow<CrewLiveEvent>(extraBufferCapacity = 32)

    /** 서버가 보낸 실시간 알림 (동승자 지각·나와 있음·탈 지점 변경·불참, 영수증 발행 등) */
    val liveEvents: SharedFlow<CrewLiveEvent> = _liveEvents.asSharedFlow()

    private val _liveConnected = MutableStateFlow(false)
    val liveConnected: StateFlow<Boolean> = _liveConnected.asStateFlow()

    private var liveJob: Job? = null

    /**
     * 구독을 열고 끊기면 새 티켓으로 다시 연다 (티켓은 60초짜리 일회용).
     * 앱이 켜져 있는 동안 계속 돈다.
     */
    fun startLive() {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val ticket = mutex.withLock { if (ensureReady()) api.liveTicket() else null }
                    if (ticket != null) {
                        api.streamEvents(ticket) { event, data ->
                            if (event == "connected") {
                                _liveConnected.value = true
                                log("실시간 구독 연결됨")
                            } else {
                                runCatching { parseLiveEvent(event, data) }
                                    .onSuccess { parsed -> log("실시간 알림: $event"); _liveEvents.emit(parsed) }
                                    .onFailure { log("실시간 알림을 읽지 못했습니다: $event $it") }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.status == 401 && e.code != "LIVE_TICKET_INVALID") store.reset()
                    log("실시간 구독 끊김: ${e.message}")
                } catch (e: Exception) {
                    log("실시간 구독 끊김: $e")
                }
                _liveConnected.value = false
                delay(LIVE_RECONNECT_MILLIS)
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        _liveConnected.value = false
    }

    /** SSE data: `{ type, tripId, data: {...}, occurredAt }`. crewMemberId 는 앱 동승자 ID 로 바꾼다 */
    internal fun parseLiveEvent(event: String, data: String): CrewLiveEvent {
        val body = JSONObject(data).optJSONObject("data") ?: JSONObject()
        val passengerOf = store.crewMemberIds.entries.associate { (passengerId, crewMemberId) -> crewMemberId to passengerId }
        val passengerId = if (body.has("crewMemberId")) passengerOf[body.getLong("crewMemberId")] else null
        val name = body.optString("nickname")
        return when (event) {
            "rider.delayed" -> CrewLiveEvent.RiderDelayed(passengerId, name, body.getInt("minutes"))
            "rider.here" -> CrewLiveEvent.RiderHere(passengerId, name)
            "rider.place-changed" -> {
                val place = PlaceRef(body.getString("placeName"), body.getDouble("latitude"), body.getDouble("longitude"))
                // 같은 장소를 다시 등록하지 않도록 서버 placeId 를 기억해 둔다
                store.placeIds = store.placeIds + (place.key to body.getLong("placeId"))
                CrewLiveEvent.RiderPlaceChanged(passengerId, name, Place(place.name, "", GeoPoint(place.latitude, place.longitude)))
            }
            "rider.absent" -> CrewLiveEvent.RiderAbsent(passengerId, name)
            "receipt.issued" -> CrewLiveEvent.ReceiptIssued
            "detour.confirm-requested" ->
                CrewLiveEvent.DetourConfirmRequested(body.getLong("detourId"), body.optString("placeName"), body.getDouble("detourKm"))
            else -> CrewLiveEvent.Other(event)
        }
    }

    // 실시간 알림 [운전자] → 동승자. 저장하지 않는 알림이라 실패해도 다시 보내지 않는다.

    private fun liveTripId(trip: CarpoolTrip): Long? = store.tripIds[trip.id]?.takeIf { it != ServerStore.FAILED_TRIP }

    /** 차량 위치(SDK)와 동승자별 남은 시간·거리(내비 경유지 정보) */
    suspend fun sendLocation(trip: CarpoolTrip, location: GeoPoint, remains: List<RiderRemain>) {
        val tripId = liveTripId(trip) ?: return
        val body = JSONArray(
            remains.mapNotNull { remain ->
                store.crewMemberIds[remain.passengerId]?.let {
                    JSONObject().put("crewMemberId", it)
                        .put("remainSeconds", remain.remainSeconds ?: JSONObject.NULL)
                        .put("remainMeters", remain.remainMeters ?: JSONObject.NULL)
                }
            },
        )
        live("위치") { api.liveLocation(tripId, location.latitude, location.longitude, body) }
    }

    suspend fun sendApproaching(trip: CarpoolTrip, passengerId: String) {
        val tripId = liveTripId(trip) ?: return
        val crewMemberId = store.crewMemberIds[passengerId] ?: return
        live("곧 도착") { api.liveApproaching(tripId, crewMemberId) }
    }

    suspend fun sendArrived(trip: CarpoolTrip, passengerId: String) {
        val tripId = liveTripId(trip) ?: return
        val crewMemberId = store.crewMemberIds[passengerId] ?: return
        live("차 도착") { api.liveArrived(tripId, crewMemberId) }
    }

    /** 아직 안 탄 동승자들에게 운전자 지각 알림 */
    suspend fun sendDriverDelay(trip: CarpoolTrip, minutes: Int) {
        val tripId = liveTripId(trip) ?: return
        live("운전자 지각") { api.liveDriverDelay(tripId, minutes) }
    }

    private suspend fun live(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("실시간 알림($what)을 보내지 못했습니다: $e")
        }
    }

    // 우회 거리: 동승자가 합류한 뒤 한 번 산출해 등록하고 운전자 몫을 확인한다

    /**
     * 아직 등록하지 않은 (동승자, 탈 지점)마다 [detourKmOf] 로 우회 거리를 구해 올린다.
     * 동승자도 확인해야(동승자 웹) 정산에 쓴다.
     */
    suspend fun registerDetours(trip: CarpoolTrip, detourKmOf: suspend (com.paircar.domain.model.Passenger) -> Double?) {
        val targets = trip.acceptedPassengers.filter { p ->
            p.id in store.crewMemberIds && detourKey(p) !in store.registeredDetours
        }
        if (targets.isEmpty()) return
        val measured = targets.mapNotNull { p -> detourKmOf(p)?.let { p to roundTenth(it.coerceAtLeast(0.0)) } }
        mutex.withLock {
            if (!ensureReady()) return@withLock
            val crewId = store.crewId ?: return@withLock
            val driver = store.driverCrewMemberId ?: return@withLock
            measured.forEach { (passenger, km) ->
                try {
                    val detour = api.registerDetour(crewId, store.crewMemberIds.getValue(passenger.id), driver, placeId(passenger.pickup.ref()), km)
                    api.confirmDetour(crewId, detour.getLong("detourId"))
                    store.registeredDetours = store.registeredDetours + detourKey(passenger)
                    log("우회 거리 등록: ${passenger.name} ${passenger.pickup.name} ${km}km")
                } catch (e: Exception) {
                    log("우회 거리를 등록하지 못했습니다: ${passenger.name} $e")
                }
            }
        }
    }

    private fun detourKey(passenger: com.paircar.domain.model.Passenger) = "${passenger.id}@${passenger.pickup.ref().key}"

    // 정산 (월 영수증)

    suspend fun receipts(month: YearMonth): List<ServerReceipt> = mutex.withLock {
        if (!ensureReady()) throw IOException("서버에 연결하지 못했어요")
        api.receipts(store.crewId!!, month.toString()).toReceipts()
    }

    /** 고른 달 영수증을 발행한다. 서버는 운전자·동승자 쌍마다 한 장씩 만든다 */
    suspend fun issueReceipts(month: YearMonth): List<ServerReceipt> = mutex.withLock {
        if (!ensureReady()) throw IOException("서버에 연결하지 못했어요")
        api.issueReceipts(store.crewId!!, month.toString()).toReceipts()
    }

    private fun JSONArray.toReceipts(): List<ServerReceipt> = (0 until length()).map(::getJSONObject).map { r ->
        val items = r.getJSONArray("items")
        ServerReceipt(
            receiptId = r.getLong("receiptId"),
            payerNickname = r.getString("payerNickname"),
            payeeNickname = r.getString("payeeNickname"),
            boardingDays = r.getInt("boardingDays"),
            sharedDistanceKm = r.getDouble("sharedDistanceKm"),
            detourKm = r.getDouble("detourKm"),
            ownDistanceKm = r.getDouble("ownDistanceKm"),
            fuelEconomy = r.getDouble("fuelEconomy"),
            fuelEconomySource = r.getString("fuelEconomySource"),
            fuelPriceWon = r.getInt("fuelPriceWon"),
            tollWon = r.getDouble("tollWon"),
            roundingWon = r.getDouble("roundingWon"),
            amountWon = r.getLong("amountWon"),
            payeeBankName = r.optString("payeeBankName"),
            payeeAccountNumber = r.optString("payeeAccountNumber"),
            waived = !r.isNull("waivedAt"),
            issuedAt = ServerTime.toKst(r.getString("issuedAt")),
            items = (0 until items.length()).map(items::getJSONObject).map {
                ServerReceiptItem(java.time.LocalDate.parse(it.getString("serviceDate")), it.getDouble("amountWon"))
            },
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
            // 이미 만든 크루가 있으면 그것을 쓴다 (크루 생성 응답을 못 받고 끊긴 경우 등). 없을 때만 새로 만든다.
            val existing = api.myCrews().let { crews -> (0 until crews.length()).map(crews::getJSONObject) }.firstOrNull()
            if (existing != null) {
                store.crewId = existing.getLong("crewId")
                log("서버 크루 이어서 사용: ${store.crewId}")
            } else {
                store.crewId = api.createCrew(carpool.crew.value?.name ?: trip?.title ?: DEFAULT_CREW_NAME).getLong("crewId")
                log("서버 크루 생성: ${store.crewId}")
            }
        }
        if (seedPassengers && trip != null) seed(trip)
        syncSettings(driverName)
    }

    private suspend fun seed(trip: CarpoolTrip) {
        // 수락한 동승자만 크루원이다 (새 동승 요청은 아직 크루가 아니다)
        val missing = trip.acceptedPassengers.filter { it.id !in store.crewMemberIds }
        if (missing.isEmpty()) return
        val code = api.createInvite(store.crewId!!).getString("code")
        missing.forEach { passenger ->
            val joined = api.joinAsNewMember(code, passenger.name)
            store.crewMemberIds = store.crewMemberIds + (passenger.id to joined.getLong("crewMemberId"))
            store.seededRiderTokens = store.seededRiderTokens + (passenger.id to joined.getString("token"))
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

        /** 실시간 구독이 끊겼을 때 다시 연결하기까지 */
        const val LIVE_RECONNECT_MILLIS = 3_000L

        /** 토큰이 무효라 새 멤버로 다시 준비할 때 */
        const val RESET_RETRY_MILLIS = 1_000L

        /** 서버 최소값 (fuelEconomy ≥ 0.01) */
        const val MIN_EFFICIENCY = 0.01
    }
}
