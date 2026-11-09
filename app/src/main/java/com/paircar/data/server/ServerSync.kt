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
import com.paircar.domain.model.CrewInfo
import com.paircar.domain.model.CrewSetup
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 동승자별 남은 시간·거리 (내비 경유지 정보). 못 구했으면 null */
data class RiderRemain(val passengerId: String, val remainSeconds: Int?, val remainMeters: Int?)


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

    private val _crewLoaded = MutableStateFlow(false)

    /** 서버에서 크루를 한 번이라도 받아 봤는지 (크루가 없다는 것도 확인했는지) */
    val crewLoaded: StateFlow<Boolean> = _crewLoaded.asStateFlow()

    /** 크루 이름·명단을 다시 받는다 (초대 링크로 새 동승자가 들어왔을 수 있다) */
    suspend fun refreshCrew() = mutex.withLock {
        if (!ensureReady()) return@withLock
        try {
            loadCrew()
            _crewLoaded.value = true
        } catch (e: Exception) {
            log("크루 명단을 받지 못했습니다: $e")
        }
    }

    /** 마지막으로 받은 크루원 명단·오늘 출석 (GET members + attendance 응답). 같으면 크루를 다시 받지 않는다 */
    @Volatile
    private var rosterSignature: String? = null

    /**
     * 오늘의 카풀·내 크루 화면이 보이는 동안 자주 부른다: 크루원 명단과 오늘 출석만 받아 보고(요청 2번),
     * 새 크루원이 들어왔거나 동승자가 평소/오늘 탈 곳·참석을 바꿨으면 크루 전체를 다시 받는다
     * (→ 출발 전 오늘 운행의 경유지·경로가 다시 만들어진다).
     */
    suspend fun pollCrew() {
        val crewId = store.crewId ?: return
        if (store.token == null) return
        val signature = try {
            rosterSignature(api.crewMembers(crewId), api.attendance(crewId, LocalDate.now(ServerTime.KST).toString()))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return
        }
        if (signature == rosterSignature) return
        log("크루원·출석이 바뀌어 크루를 다시 받습니다")
        refreshCrew()
    }

    private fun rosterSignature(members: JSONArray, attendance: JSONArray) = "$members|$attendance"

    /**
     * [크루 만들기]: 내 크루가 없으면 만들고, 출발지·도착지를 크루 장소로 등록해 일정을 저장한다.
     * 실패하면 예외 (화면에 알린다).
     */
    suspend fun setupCrew(setup: CrewSetup) = mutex.withLock {
        if (!ensureReady()) throw IOException("서버에 연결하지 못했어요")
        val crewId = store.crewId ?: api.createCrew(setup.name).getLong("crewId").also {
            store.crewId = it
            log("서버 크루 생성: $it")
        }
        val current = _crew.value
        api.updateCrew(
            crewId,
            crewBody(
                setup.name, setup.departureTime, setup.days,
                placeId(setup.origin.ref()), placeId(setup.destination.ref()),
                settlementMode = current?.settlementMode ?: "FAIR",
                includeDriver = current?.includeDriver ?: true,
            ),
        )
        loadCrew()
        _crewLoaded.value = true
        log("크루 일정 저장: ${setup.name} ${setup.departureTime} ${setup.origin.name} → ${setup.destination.name}")
    }

    private suspend fun loadCrew() {
        val crewId = store.crewId ?: return
        val crew = api.crew(crewId)
        val memberArray = api.crewMembers(crewId)
        val members = memberArray.objects()
        val attendanceArray = api.attendance(crewId, LocalDate.now(ServerTime.KST).toString())
        val attendance = attendanceArray.objects()
        val me = store.memberId
        members.firstOrNull { it.getLong("memberId") == me }?.let { store.driverCrewMemberId = it.getLong("crewMemberId") }

        // 동승자 ID: 이미 짝지어진 앱 ID(예시 동승자 p-1 등)를 그대로 쓰고, 처음 보는 멤버는 cm-{crewMemberId}
        val passengerOf = store.crewMemberIds.entries.associate { (passengerId, crewMemberId) -> crewMemberId to passengerId }.toMutableMap()
        members.filter { it.getLong("memberId") != me }.forEach { m ->
            val crewMemberId = m.getLong("crewMemberId")
            if (crewMemberId !in passengerOf) {
                passengerOf[crewMemberId] = "cm-$crewMemberId"
                store.crewMemberIds = store.crewMemberIds + ("cm-$crewMemberId" to crewMemberId)
            }
        }
        val places = (members.flatMap { listOfNotNull(it.place("boardPlace"), it.place("alightPlace")) } +
            listOfNotNull(crew.place("originPlace"), crew.place("destinationPlace")) +
            attendance.flatMap { listOfNotNull(it.place("boardPlace"), it.place("alightPlace")) })
        // 서버 장소를 기억해 두면 운행 시작·우회 거리 등록 때 같은 장소를 다시 등록하지 않는다
        store.placeIds = store.placeIds + places.associate { it.ref.key to it.placeId }

        rosterSignature = rosterSignature(memberArray, attendanceArray)
        _crew.value = ServerCrew(
            crewId = crewId,
            name = crew.getString("name"),
            members = members.map { m ->
                val crewMemberId = m.getLong("crewMemberId")
                val isDriver = m.getLong("memberId") == me
                CrewMember(
                    id = crewMemberId.toString(),
                    name = m.getString("nickname"),
                    isDriver = isDriver,
                    joinedAt = ServerTime.toKst(m.getString("joinedAt")),
                    passengerId = if (isDriver) null else passengerOf[crewMemberId],
                )
            }.sortedWith(compareByDescending<CrewMember> { it.isDriver }.thenBy { it.joinedAt }),
            departureTime = crew.optString("departureTime").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalTime::parse),
            departureDays = crew.optJSONArray("departureDays")?.let { days -> (0 until days.length()).map { DayOfWeek.valueOf(days.getString(it)) }.toSet() }.orEmpty(),
            origin = crew.place("originPlace"),
            destination = crew.place("destinationPlace"),
            settlementMode = crew.optString("settlementMode", "FAIR"),
            includeDriver = crew.optBoolean("includeDriver", true),
            memberPlaces = members.associate { it.getLong("crewMemberId") to MemberPlaces(it.place("boardPlace"), it.place("alightPlace")) },
            todayAttendance = attendance.associate { a ->
                a.getLong("crewMemberId") to ServerAttendance(
                    crewMemberId = a.getLong("crewMemberId"),
                    nickname = a.optString("nickname"),
                    attending = a.getBoolean("attending"),
                    boardPlace = a.place("boardPlace"),
                    alightPlace = a.place("alightPlace"),
                    changed = a.optBoolean("changed"),
                )
            },
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun JSONObject.place(key: String): ServerPlace? = optJSONObject(key)?.let {
        ServerPlace(it.getLong("placeId"), it.getString("name"), it.getDouble("latitude"), it.getDouble("longitude"))
    }

    // 크루 설정·출석 (출발 전) → 서버

    /**
     * 운전자가 [크루 정보 설정]에서 바꾼 이름·출발 시각·요일을 올린다 (PUT 이라 출발지·목적지·정산 방식도 함께 보낸다).
     * 차를 등록한 멤버만 바꿀 수 있다 (CREW_DRIVER_ONLY).
     */
    suspend fun pushCrewInfo(info: CrewInfo) = mutex.withLock {
        if (!ensureReady()) return@withLock
        try {
            val current = _crew.value ?: run { loadCrew(); _crew.value } ?: return@withLock
            val originId = info.origin?.let { placeId(it.ref()) } ?: current.origin?.placeId
            val destinationId = info.destination?.let { placeId(it.ref()) } ?: current.destination?.placeId
            api.updateCrew(
                current.crewId,
                crewBody(info.name, info.departureTime, info.days, originId, destinationId, current.settlementMode, current.includeDriver),
            )
            loadCrew()
        } catch (e: Exception) {
            log("크루 정보를 서버에 올리지 못했습니다: $e")
        }
    }

    /** 출발 전 오늘 참석 여부 (운전자가 [탑승·픽업 관리]에서 바꿈). 운행 중 불참은 [onAttendanceChanged] */
    suspend fun pushAttendance(date: LocalDate, passengerId: String, attendance: Attendance) = mutex.withLock {
        if (!ensureReady()) return@withLock
        val crewMemberId = store.crewMemberIds[passengerId] ?: return@withLock
        try {
            api.changeAttendance(store.crewId ?: return@withLock, crewMemberId, date.toString(), attendance == Attendance.ATTENDING)
            loadCrew()
        } catch (e: Exception) {
            log("출석을 서버에 올리지 못했습니다: $e")
        }
    }

    private fun crewBody(
        name: String,
        departure: LocalTime?,
        days: Set<DayOfWeek>,
        originPlaceId: Long?,
        destinationPlaceId: Long?,
        settlementMode: String,
        includeDriver: Boolean,
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("departureTime", departure?.format(HH_MM) ?: JSONObject.NULL)
        .put("departureDays", JSONArray(days.sorted().map { it.name }))
        .put("originPlaceId", originPlaceId ?: JSONObject.NULL)
        .put("destinationPlaceId", destinationPlaceId ?: JSONObject.NULL)
        .put("settlementMode", settlementMode)
        .put("includeDriver", includeDriver)

    /**
     * 서버 크루에 일정(출발지·목적지·출발 시각)이 아직 없으면 앱 크루 정보로 채운다 (크루를 처음 만든 직후).
     * 개발용: 대신 가입시킨 예시 동승자의 평소 탈 곳과 오늘 불참도 서버에 넣는다.
     */
    private suspend fun seedSchedule(trip: CarpoolTrip) {
        loadCrew()
        val current = _crew.value ?: return
        if (!current.hasSchedule) {
            val days = carpool.crew.value?.days?.takeIf { it.isNotEmpty() } ?: current.departureDays
            api.updateCrew(
                current.crewId,
                crewBody(current.name, trip.departureTime, days, placeId(trip.origin.ref()), placeId(trip.destination.ref()), current.settlementMode, current.includeDriver),
            )
            log("서버 크루 일정을 앱 값으로 채웠습니다")
        }
        if (!seedPassengers) return
        val today = LocalDate.now(ServerTime.KST)
        trip.acceptedPassengers.forEach { passenger ->
            val crewMemberId = store.crewMemberIds[passenger.id] ?: return@forEach
            if (current.memberPlaces[crewMemberId]?.board == null) {
                api.updateMemberPlaces(current.crewId, crewMemberId, placeId(passenger.pickup.ref()), placeId(trip.destination.ref()))
            }
            if (!passenger.isAttending && trip.date == today && current.todayAttendance[crewMemberId]?.changed != true) {
                api.changeAttendance(current.crewId, crewMemberId, today.toString(), attending = false)
            }
        }
        loadCrew()
    }

    // 운행 이벤트 → 요청 쌓기

    suspend fun onTripStarted(trip: CarpoolTrip) {
        val odometer = energy.read().odometerKm
        if (odometer == null) {
            // 서버는 출발 주행거리가 꼭 있어야 한다. 못 읽으면 이 운행은 서버에 올리지 않는다.
            log("주행거리를 못 읽어 운행 ${trip.id} 를 서버에 올리지 않습니다")
            return
        }
        // 같은 날 운행을 여러 번(출근·퇴근, 다시 시작) 해도 서버 운행이 겹치지 않도록 시작할 때마다 새 키를 쓴다
        val tripKey = "${trip.id}#${System.currentTimeMillis()}"
        store.rideLog = RideLog(tripKey, roundTenth(odometer), emptyList())
        enqueue(
            TripOp.Start(
                tripKey = tripKey,
                startedAt = ServerTime.format(now()),
                odometerKm = roundTenth(odometer),
                origin = trip.origin.ref(),
                destination = trip.destination.ref(),
                riders = trip.pickupsInOrder.map { RiderRef(it.id, it.pickup.ref(), trip.destination.ref()) },
            ),
        )
    }

    suspend fun onBoardingChanged(trip: CarpoolTrip, passengerId: String, status: BoardingStatus) {
        val ride = rideOf(trip) ?: return
        when (status) {
            BoardingStatus.BOARDED, BoardingStatus.DROPPED_OFF -> {
                val boarded = status == BoardingStatus.BOARDED
                val odometer = currentOdometer(ride)
                store.rideLog = ride.copy(events = ride.events + RideEvent(passengerId, boarded, odometer))
                val at = ServerTime.format(now())
                enqueue(
                    if (boarded) TripOp.Board(ride.tripKey, passengerId, at, odometer)
                    else TripOp.Alight(ride.tripKey, passengerId, at, odometer),
                )
            }
            BoardingStatus.NO_SHOW -> enqueue(TripOp.Absent(ride.tripKey, passengerId))
            // 경유지에 도착했는데 아직 안 탐 → 동승자에게 "차가 도착했어요"
            BoardingStatus.ARRIVED_AT_PICKUP -> scope.launch(Dispatchers.IO) { sendArrived(trip, passengerId) }
            BoardingStatus.WAITING -> Unit
        }
    }

    fun onAttendanceChanged(trip: CarpoolTrip, passengerId: String, attendance: Attendance) {
        // 출발 전 불참은 운행 시작 요청의 동승자에서 빠지는 것으로 충분하다
        val ride = rideOf(trip)
        if (attendance != Attendance.ABSENT || ride == null) return
        enqueue(TripOp.Absent(ride.tripKey, passengerId))
    }

    /** 운행 완료·취소: 구간을 나눠 올리고 운행을 끝낸다 */
    suspend fun onTripEnded(trip: CarpoolTrip) {
        val ride = rideOf(trip) ?: return
        val odometer = currentOdometer(ride)
        val (economy, source) = fuelEconomy()
        val segments = buildSegments(ride.startOdometerKm, odometer, ride.events, economy, source)
        if (segments.isNotEmpty()) enqueue(TripOp.Segments(ride.tripKey, segments), flush = false)
        // 앱은 통행료를 아직 모른다
        enqueue(TripOp.End(ride.tripKey, ServerTime.format(now()), odometer, tollFareWon = 0))
        store.rideLog = null
    }

    /** 계좌·충전 단가가 바뀌면 서버에 올리고, 계좌가 없어 멈춘 운행 기록도 이어서 보낸다 */
    fun onSettingsChanged() = flushLater()

    /** QR 초대 코드는 서버가 발급한다 (동승자 웹이 같은 코드로 가입을 확인한다) */
    suspend fun createInvite(): CrewInvite = mutex.withLock {
        if (!ensureReady()) throw IOException("서버에 연결하지 못했어요")
        val invite = api.createInvite(store.crewId ?: throw IllegalStateException("크루를 먼저 만들어 주세요"))
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
                        log("실시간 구독 끊김: 서버가 연결을 닫음")
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
            // 출발 전 출석 변경 (tripId 없음). 바꾼 사람을 뺀 크루 멤버 전원에게 온다
            "rider.attendance-changed" -> {
                val place = body.place("boardPlace")
                place?.let { store.placeIds = store.placeIds + (it.ref.key to it.placeId) }
                CrewLiveEvent.RiderAttendanceChanged(
                    passengerId = passengerId,
                    name = name,
                    date = LocalDate.parse(body.getString("serviceDate")),
                    attending = body.getBoolean("attending"),
                    pickup = place?.let { Place(it.name, "", GeoPoint(it.latitude, it.longitude)) },
                )
            }
            "receipt.issued" -> CrewLiveEvent.ReceiptIssued
            "detour.confirm-requested" ->
                CrewLiveEvent.DetourConfirmRequested(body.getLong("detourId"), body.optString("placeName"), body.getDouble("detourKm"))
            else -> CrewLiveEvent.Other(event)
        }
    }

    // 실시간 알림 [운전자] → 동승자. 저장하지 않는 알림이라 실패해도 다시 보내지 않는다.

    private fun liveTripId(trip: CarpoolTrip): Long? =
        rideOf(trip)?.let { store.tripIds[it.tripKey] }?.takeIf { it != ServerStore.FAILED_TRIP }

    /** 이 앱 운행의 진행 중 기록 (서버 운행 키는 "앱 운행 ID#시작 시각") */
    private fun rideOf(trip: CarpoolTrip): RideLog? = store.rideLog?.takeIf { it.tripKey.substringBefore("#") == trip.id }

    /** 지금 진행 중인 운행의 서버 tripId (테스트·복구 확인용) */
    fun serverTripId(trip: CarpoolTrip): Long? = liveTripId(trip)

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
        val crewId = store.crewId ?: return@withLock emptyList()
        api.receipts(crewId, month.toString()).toReceipts()
    }

    /** 고른 달 영수증을 발행한다. 서버는 운전자·동승자 쌍마다 한 장씩 만든다 */
    suspend fun issueReceipts(month: YearMonth): List<ServerReceipt> = mutex.withLock {
        if (!ensureReady()) throw IOException("서버에 연결하지 못했어요")
        api.issueReceipts(store.crewId ?: throw IllegalStateException("크루를 먼저 만들어 주세요"), month.toString()).toReceipts()
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
            val tripId = try {
                api.startTrip(body).getLong("tripId")
            } catch (e: ApiException) {
                val existing = e.data?.optLong("tripId")?.takeIf { e.code == "TRIP_ALREADY_IN_PROGRESS" && it > 0 } ?: throw e
                recoverInProgress(op, existing) ?: api.startTrip(body).getLong("tripId")
            }
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

    /**
     * 운행 시작이 409 TRIP_ALREADY_IN_PROGRESS 일 때 (앱 재설치·기기 저장 유실·종료 요청 유실).
     * - 같은 운행일이고 다른 앱 운행과 짝지어지지 않았으면 그 운행을 이어서 쓴다 → 그 tripId
     * - 지난 운행이면 마지막 주행거리로 끝내고 null (새로 시작한다)
     */
    private suspend fun recoverInProgress(op: TripOp.Start, existingTripId: Long): Long? {
        val existing = api.trip(existingTripId)
        val serviceDate = existing.getString("serviceDate")
        val startDate = ServerTime.parse(op.startedAt).atZone(ServerTime.KST).toLocalDate().toString()
        val claimed = store.tripIds.any { (key, id) -> id == existingTripId && key != op.tripKey }
        if (serviceDate == startDate && !claimed) {
            log("진행 중인 서버 운행 $existingTripId 를 이어서 씁니다")
            return existingTripId
        }
        val segments = existing.optJSONArray("segments")?.objects().orEmpty()
        val boardings = existing.optJSONArray("boardings")?.objects().orEmpty()
        val lastOdometer = (listOf(existing.getDouble("startOdometerKm")) +
            segments.map { it.getDouble("endOdometerKm") } +
            boardings.flatMap { b -> listOf("boardOdometerKm", "alightOdometerKm").mapNotNull { k -> b.optDouble(k).takeUnless(Double::isNaN) } })
            .max()
        api.endTrip(existingTripId, op.startedAt, lastOdometer, tollFareWon = 0)
        log("끝나지 않은 지난 운행 $existingTripId ($serviceDate) 을 끝내고 새로 시작합니다")
        return null
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
            // 이미 만든 크루가 있으면 그것을 쓴다 (크루 생성 응답을 못 받고 끊긴 경우 등)
            val existing = api.myCrews().let { crews -> (0 until crews.length()).map(crews::getJSONObject) }.firstOrNull()
            if (existing != null) {
                store.crewId = existing.getLong("crewId")
                log("서버 크루 이어서 사용: ${store.crewId}")
            } else if (trip != null) {
                // 앱에 이미 크루 정보(오늘 운행)가 있으면 그것으로 만든다. 없으면 [크루 만들기]에서 사용자가 만든다
                store.crewId = api.createCrew(carpool.crew.value?.name ?: trip.title).getLong("crewId")
                log("서버 크루 생성: ${store.crewId}")
            }
        }
        if (seedPassengers && trip != null && store.crewId != null) seed(trip)
        // 앱을 켤 때 한 번: 서버 크루 일정이 비어 있으면 채운다
        if (!scheduleChecked && trip != null) {
            try {
                seedSchedule(trip)
            } catch (e: ApiException) {
                // 네트워크·401 은 위로 올려 다시 준비한다. 그 밖의 거절은 운행 기록 전송을 막지 않도록 넘어간다
                if (e.retryable || e.status == 401) throw e
                log("크루 일정을 채우지 못했습니다: ${e.message}")
            }
            scheduleChecked = true
        }
        syncSettings(driverName)
    }

    private var scheduleChecked = false

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
        // 충전 단가·전비는 차량 값이다 (크루 값 아님). 정산은 운행에 쓴 차의 단가를 쓴다
        val price = current.chargePricePerKwh ?: DEFAULT_CHARGE_PRICE_WON
        val efficiency = current.efficiencyKmPerKwh ?: DEFAULT_EFFICIENCY
        val carSettings = "$FUEL_TYPE|$efficiency|$price"
        if (carSettings != store.syncedFuelPrice) {
            api.updateCar(store.carId!!, FUEL_TYPE, efficiency, price)
            store.syncedFuelPrice = carSettings
        }
    }

    /**
     * 크루 장소 ID. 처음 쓰는 장소는 등록한다.
     * 크루 안에서 장소 이름은 하나뿐이라(CREW_PLACE_NAME_DUPLICATED) 같은 이름이 이미 있으면 그 장소를 쓴다.
     */
    private suspend fun placeId(place: PlaceRef): Long {
        store.placeIds[place.key]?.let { return it }
        val crewId = store.crewId!!
        val id = try {
            api.registerPlace(crewId, place.name, place.latitude, place.longitude).getLong("placeId")
        } catch (e: ApiException) {
            if (e.code != "CREW_PLACE_NAME_DUPLICATED") throw e
            api.places(crewId).objects().firstOrNull { it.getString("name") == place.name }?.getLong("placeId") ?: throw e
        }
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

    /** 서버 크루 장소는 위도·경도 소수 6자리까지 받는다 (그보다 길면 400 INVALID_INPUT) */
    private fun Place.ref() = PlaceRef(name, roundCoordinate(point.latitude), roundCoordinate(point.longitude))

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FUEL_TYPE = "EV"
        const val DEFAULT_NICKNAME = "운전자"
        const val DEFAULT_CREW_NAME = "크루"

        /** 정산 화면 기본값과 같은 값 (운전자가 아직 정하지 않았을 때) */
        const val DEFAULT_EFFICIENCY = 5.5
        const val DEFAULT_CHARGE_PRICE_WON = 347

        /** 크루 출발 시각 (KST) */
        val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** 실시간 구독이 끊겼을 때 다시 연결하기까지 */
        const val LIVE_RECONNECT_MILLIS = 3_000L

        /** 토큰이 무효라 새 멤버로 다시 준비할 때 */
        const val RESET_RETRY_MILLIS = 1_000L

        /** 서버 최소값 (fuelEconomy ≥ 0.01) */
        const val MIN_EFFICIENCY = 0.01
    }
}
