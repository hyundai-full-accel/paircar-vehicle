package com.paircar

import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.data.server.PairCarApi
import com.paircar.data.server.RideLog
import com.paircar.data.server.ServerCarpoolRepository
import com.paircar.data.server.ServerStore
import com.paircar.data.server.ServerSync
import com.paircar.data.server.ServerTime
import com.paircar.data.server.TripOp
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.ReceiverAccount
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.repository.VehicleEnergyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * 실제 서버에 붙여 운행 한 건을 끝까지 올려 본다. 서버 주소를 줄 때만 돈다:
 * `./gradlew :app:testDebugUnitTest -PpaircarServer=http://localhost --tests '*ServerSyncIntegrationTest'`
 * 서버에 테스트 멤버·크루·운행이 남는다.
 */
class ServerSyncIntegrationTest {

    private val baseUrl: String? = System.getProperty("paircar.server")

    @Test
    fun `운행 시작부터 종료까지 서버에 올라간다`() = runBlocking {
        assumeTrue("서버 주소(-PpaircarServer)가 없어 건너뜀", !baseUrl.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = MemoryServerStore()
        val api = PairCarApi(baseUrl!!) { store.token }
        val local = MockCarpoolRepository(latencyMillis = 0)
        val logs = mutableListOf<String>()
        // 읽을 때마다 10km 씩 늘어나는 주행거리
        val energy = object : VehicleEnergyRepository {
            var odometer = 1000.0
            override suspend fun read(): VehicleEnergy {
                odometer += 10
                return VehicleEnergy(cumulativeEvEfficiency = 5.2, odometerKm = odometer)
            }
        }
        val settings = SettlementSettings(account = ReceiverAccount("테스트은행", "000-0000-0000"), chargePricePerKwh = 300)
        // 서버는 끝난 달만 수동 발행한다. 지난달 운행으로 기록해 영수증 발행까지 확인한다
        val recordedAt = java.time.LocalDateTime.now().minusMonths(1)
        val sync = ServerSync(
            api = api, store = store, carpool = local, settings = { settings }, energy = energy,
            scope = scope, seedPassengers = true, log = { logs += it; println("[sync] $it") },
            now = { recordedAt },
            retryDelayMillis = 60_000,
        )
        val repo = ServerCarpoolRepository(local, sync, scope)

        // QR 초대는 서버가 발급
        val invite = sync.createInvite()
        assertTrue(invite.url.contains(invite.code))

        val trip = local.todayTrip.value!!
        val rider = trip.pendingPickups.first()
        val absent = trip.passengers.first { !it.isAttending }

        repo.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        repo.updateBoardingStatus(rider.id, BoardingStatus.BOARDED)
        repo.updateBoardingStatus(rider.id, BoardingStatus.DROPPED_OFF)
        repo.updateTripStatus(CarpoolStatus.COMPLETED)
        sync.flush()

        assertEquals("남은 요청 없음: $logs", emptyList<TripOp>(), store.outbox)
        val tripId = store.tripIds.entries.single { it.key.startsWith(trip.id + "#") }.value
        val result = api.trip(tripId)
        println("[server] $result")

        // 시각: 서버는 Z 로 돌려주지만 같은 순간이고, 운행일은 KST 날짜다
        assertTrue(result.getString("startedAt").endsWith("Z"))
        assertEquals(recordedAt.toLocalDate().toString(), result.getString("serviceDate"))
        assertNotNull(result.optString("endedAt").takeIf { it.isNotEmpty() && it != "null" })
        assertEquals(1040.0, result.getDouble("endOdometerKm"), 0.0)

        // 출발 때 참석한 동승자만 탑승 대상, 불참자는 빠진다
        val boardings = result.getJSONArray("boardings")
        val riderCrewMemberId = store.crewMemberIds.getValue(rider.id)
        val boarding = (0 until boardings.length()).map(boardings::getJSONObject).single { it.getLong("crewMemberId") == riderCrewMemberId }
        assertEquals(1020.0, boarding.getDouble("boardOdometerKm"), 0.0)
        assertEquals(1030.0, boarding.getDouble("alightOdometerKm"), 0.0)
        assertFalse((0 until boardings.length()).any { boardings.getJSONObject(it).getLong("crewMemberId") == store.crewMemberIds[absent.id] })

        // 구간: 1010→1020 (혼자) · 1020→1030 (동승) · 1030→1040 (혼자)
        val segments = result.getJSONArray("segments")
        assertEquals(3, segments.length())
        assertEquals(listOf(riderCrewMemberId), segments.getJSONObject(1).getJSONArray("riderCrewMemberIds").let { a -> (0 until a.length()).map(a::getLong) })
        assertEquals("MEASURED", segments.getJSONObject(0).getString("fuelEconomySource"))

        // 크루 명단: 운전자 먼저, 예시 동승자는 앱 동승자와 짝지어지고, 초대 링크로 들어온 사람은 짝이 없다
        api.joinAsNewMember(invite.code, "웹가입")
        sync.refreshCrew()
        // 화면이 보는 crew 는 서버 값이 들어오면 비동기로 바뀐다
        val crew = withTimeout(5_000) { repo.crew.first { it?.members?.any { m -> m.name == "웹가입" } == true } }!!
        assertEquals(store.crewId.toString(), crew.id)
        assertTrue(crew.members.first().isDriver)
        assertEquals(trip.driver.name, crew.members.first().name)
        assertEquals(rider.id, crew.members.single { it.name == rider.name }.passengerId)
        // 초대 링크로 들어온 사람은 앱 동승자가 아니라 cm-{crewMemberId} 로 짝지어진다
        val webMember = crew.members.single { it.name == "웹가입" }
        assertEquals("cm-${webMember.id}", webMember.passengerId)
        assertEquals(trip.acceptedPassengers.size + 2, crew.members.size)

        // 크루 일정은 처음 준비할 때 앱 값으로 채워진다 (출발지·목적지·출발 시각)
        val serverCrew = sync.crew.value!!
        assertTrue(serverCrew.hasSchedule)
        assertEquals(trip.departureTime, serverCrew.departureTime)
        assertEquals(trip.origin.name, serverCrew.origin!!.name)
        // 예시 동승자의 평소 탈 곳도 서버에 들어간다
        assertEquals(rider.pickup.name, serverCrew.memberPlaces.getValue(riderCrewMemberId).board!!.name)

        // 월별 정산: 이번 달은 수동 발행이 막히고(매달 1일 자동 발행), 끝난 달은 발행하면 서버 금액
        val records = MemoryTripRecords()
        val settlementRepo = com.paircar.data.server.ServerSettlementRepository(sync, repo, records, scope)
        val thisMonth = java.time.YearMonth.now(ServerTime.KST)
        val month = java.time.YearMonth.from(recordedAt)
        assertFalse(settlementRepo.canIssue(thisMonth))
        val notClosed = runCatching { sync.issueReceipts(thisMonth) }.exceptionOrNull() as com.paircar.data.server.ApiException
        assertEquals("SETTLEMENT_MONTH_NOT_CLOSED", notClosed.code)
        assertTrue(settlementRepo.issuesOnServer)
        settlementRepo.issue(month)
        val issued = com.paircar.data.server.issuedSettlement(month, crew.name, sync.receipts(month), listOf(rider.name))
        val riderReceipt = issued.receipts.single { it.member.name == rider.name }
        println("[settlement] ${riderReceipt.member} total=${riderReceipt.totalWon}")
        assertTrue(riderReceipt.member.issued != null)
        assertEquals(1, riderReceipt.member.rides)
        assertEquals(10.0, riderReceipt.member.sharedKm, 0.0)
        assertTrue(riderReceipt.totalWon > 0)
        assertEquals("000-0000-0000", issued.receiverAccount?.number)

        // 보낸 시각(+09:00)과 받은 시각(Z)이 같은 순간
        val startedAt = ServerTime.parse(result.getString("startedAt"))
        assertEquals(recordedAt.withNano(0).atZone(java.time.ZoneId.systemDefault()).toInstant(), startedAt)
        scope.cancel()
    }

    /**
     * 서버 일정으로 오늘 운행 만들기, 출발 전 출석 변경, 진행 중 운행 복구(409).
     */
    @Test
    fun `서버 일정과 출석으로 오늘 운행을 만들고 끝나지 않은 운행을 이어서 쓴다`() = runBlocking {
        assumeTrue("서버 주소(-PpaircarServer)가 없어 건너뜀", !baseUrl.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = MemoryServerStore()
        val api = PairCarApi(baseUrl!!) { store.token }
        val local = MockCarpoolRepository(latencyMillis = 0)
        val energy = object : VehicleEnergyRepository {
            override suspend fun read() = VehicleEnergy(cumulativeEvEfficiency = 5.2, odometerKm = 3000.0)
        }
        val settings = SettlementSettings(account = ReceiverAccount("테스트은행", "000-0000-0000"))
        val sync = ServerSync(api, store, local, { settings }, energy, scope, seedPassengers = true, log = { println("[sync] $it") }, retryDelayMillis = 60_000)
        val repo = ServerCarpoolRepository(
            local = local, sync = sync, scope = scope, routes = FakeRoutes(),
            vehicleName = "전기차",
        )
        // 초대 API 는 IP 당 분당 10회 제한이라, 앞 테스트에 이어 돌면 429 가 난다. 풀릴 때까지 다시 준비한다
        withTimeout(90_000) {
            while (true) {
                sync.flush()
                sync.refreshCrew()
                if (sync.crew.value?.hasSchedule == true && store.crewMemberIds.size >= 2) break
                kotlinx.coroutines.delay(5_000)
            }
        }

        // 서버 일정·탈 곳·출석으로 만든 운행: 예시 운행과 같은 동승자(같은 ID), 서버의 차량 충전 단가
        val mock = com.paircar.data.mock.MockCarpoolData.todayTrip(java.time.LocalDate.now())
        val rider = mock.pickupsInOrder.first()
        waitUntil { local.todayTrip.value!!.passengers.map { it.id }.toSet() == mock.acceptedPassengers.map { it.id }.toSet() }
        val car = api.car(store.carId!!)
        assertEquals(347, car.getInt("manualFuelPriceWon"))

        // 운전자가 출발 전에 불참으로 바꾸면 서버 출석에 들어가고, 다시 받아도 불참
        repo.updateAttendance(rider.id, com.paircar.domain.model.Attendance.ABSENT)
        waitUntil { sync.crew.value?.todayAttendance?.get(store.crewMemberIds.getValue(rider.id))?.attending == false }
        repo.updateAttendance(rider.id, com.paircar.domain.model.Attendance.ATTENDING)
        waitUntil { sync.crew.value?.todayAttendance?.get(store.crewMemberIds.getValue(rider.id))?.attending == true }

        // 크루 정보 변경 → 서버 크루 설정
        repo.updateCrewInfo(com.paircar.domain.model.CrewInfo("판교 퇴근 크루", java.time.LocalTime.of(18, 30), setOf(java.time.DayOfWeek.MONDAY)))
        waitUntil { sync.crew.value?.name == "판교 퇴근 크루" }
        assertEquals(java.time.LocalTime.of(18, 30), sync.crew.value!!.departureTime)
        assertEquals(setOf(java.time.DayOfWeek.MONDAY), sync.crew.value!!.departureDays)

        // 앱 기록을 잃은 채(새 기기 저장소) 다시 시작해도 409 의 tripId 로 진행 중 운행을 이어서 쓴다
        repo.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        sync.flush()
        val first = sync.serverTripId(local.todayTrip.value!!)!!
        store.tripIds = emptyMap()
        store.outbox = emptyList()
        sync.onTripStarted(local.todayTrip.value!!)
        sync.flush()
        assertEquals(first, sync.serverTripId(local.todayTrip.value!!))
        val current = api.currentTrip(store.crewId!!)!!
        assertEquals(first, current.getLong("tripId"))
        scope.cancel()
    }

    /** 크루가 없는 새 기기: [크루 만들기]로 크루·일정을 만들면 오늘 운행이 서버 일정으로 생기고 QR 을 발급할 수 있다 */
    @Test
    fun `크루가 없으면 크루 만들기로 크루와 일정을 만든다`() = runBlocking {
        assumeTrue("서버 주소(-PpaircarServer)가 없어 건너뜀", !baseUrl.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = MemoryServerStore()
        val api = PairCarApi(baseUrl!!) { store.token }
        // 서버 모드 앱처럼 예시 운행·크루 없이 시작한다
        val local = MockCarpoolRepository(latencyMillis = 0, tripFactory = { null }, crew = null)
        val energy = object : VehicleEnergyRepository {
            override suspend fun read() = VehicleEnergy(odometerKm = 0.0)
        }
        val settings = SettlementSettings(account = ReceiverAccount("테스트은행", "000-0000-0000"))
        val sync = ServerSync(api, store, local, { settings }, energy, scope, seedPassengers = false, log = { println("[sync] $it") }, retryDelayMillis = 60_000)
        val repo = ServerCarpoolRepository(local = local, sync = sync, scope = scope, routes = FakeRoutes(), vehicleName = "전기차")

        sync.refreshCrew()
        // 멤버·차량은 만들지만 크루는 사용자가 만들 때까지 만들지 않는다
        assertNotNull(store.carId)
        assertEquals(null, store.crewId)
        waitUntil { repo.setupState.value == com.paircar.domain.model.CrewSetupState.NEEDS_SETUP }

        val home = com.paircar.domain.model.Place("우리집", "", com.paircar.core.model.GeoPoint(37.537, 127.085))
        val work = com.paircar.domain.model.Place("판교 사옥", "", com.paircar.core.model.GeoPoint(37.3947, 127.1115))
        repo.setupCrew(
            com.paircar.domain.model.CrewSetup("새 크루", java.time.LocalTime.of(7, 40), setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY), home, work),
        )
        assertNotNull(store.crewId)
        waitUntil { repo.setupState.value == com.paircar.domain.model.CrewSetupState.READY && local.todayTrip.value != null }
        val trip = local.todayTrip.value!!
        assertEquals(java.time.LocalTime.of(7, 40), trip.departureTime)
        assertEquals("우리집", trip.origin.name)
        assertEquals("판교 사옥", trip.destination.name)
        assertEquals("전기차", trip.driver.carModel)
        assertEquals(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY), repo.crew.value!!.days)
        val invite = repo.createInvite()
        assertTrue(invite.url.isNotBlank())

        // 내 크루 화면의 1초 확인: 초대로 누가 들어오면 명단만 보고 크루를 다시 받는다
        val before = repo.crew.value!!.members.map { it.name }
        repo.pollCrewMembers()
        assertEquals("명단이 그대로면 바뀌지 않는다", before, repo.crew.value!!.members.map { it.name })
        api.joinAsNewMember(invite.code, "새동승자")
        repo.pollCrewMembers()
        waitUntil { repo.crew.value!!.members.any { it.name == "새동승자" } }
        scope.cancel()
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) kotlinx.coroutines.delay(100)
    }
}
/**
 * 실시간 흐름: 동승자 웹 → 서버 → SSE → 차량 앱(운행 반영), 차량 앱 → 서버 → 동승자 SSE.
 * 동승자 웹 동작은 개발용으로 대신 가입시킨 동승자 토큰으로 흉내 낸다.
 */
class ServerLiveIntegrationTest {

    private val baseUrl: String? = System.getProperty("paircar.server")

    @Test
    fun `동승자 알림이 SSE 로 와서 운행에 반영되고 운전자 알림은 동승자에게 간다`() = runBlocking {
        assumeTrue("서버 주소(-PpaircarServer)가 없어 건너뜀", !baseUrl.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = MemoryServerStore()
        val api = PairCarApi(baseUrl!!) { store.token }
        val local = MockCarpoolRepository(latencyMillis = 0)
        val energy = object : VehicleEnergyRepository {
            override suspend fun read() = VehicleEnergy(cumulativeEvEfficiency = 5.2, odometerKm = 2000.0)
        }
        val settings = SettlementSettings(account = ReceiverAccount("테스트은행", "000-0000-0000"))
        val sync = ServerSync(api, store, local, { settings }, energy, scope, seedPassengers = true, log = { println("[sync] $it") }, retryDelayMillis = 60_000)
        val repo = ServerCarpoolRepository(local, sync, scope)
        val navi = com.paircar.vehicle.navi.MockNaviRepository(scope, connectDelayMillis = 0).also { it.connect() }
        val routeSync = com.paircar.domain.usecase.CarpoolRouteSync(navi)
        val notices = java.util.Collections.synchronizedList(mutableListOf<String>())
        val apply = com.paircar.domain.usecase.ApplyCrewLiveEventUseCase(local, routeSync, notify = { t, _ -> notices += t })

        sync.flush()
        sync.refreshCrew()
        val trip = local.todayTrip.value!!
        val rider = trip.pendingPickups.first()
        routeSync.request(trip)
        repo.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        sync.flush()
        val tripId = sync.serverTripId(trip)!!
        val riderCrewMemberId = store.crewMemberIds.getValue(rider.id)

        // 차량 앱 구독 → 받은 알림을 운행에 반영
        val received = java.util.Collections.synchronizedList(mutableListOf<com.paircar.domain.model.CrewLiveEvent>())
        scope.launch { sync.liveEvents.collect { received += it; apply(it) } }
        sync.startLive()
        withTimeout(5_000) { sync.liveConnected.first { it } }

        // 동승자 웹 구독
        val riderToken = store.seededRiderTokens.getValue(rider.id)
        val riderApi = PairCarApi(baseUrl) { riderToken }
        val riderEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
        scope.launch { riderApi.streamEvents(riderApi.liveTicket()) { event, _ -> riderEvents += event } }
        waitUntil { "connected" in riderEvents }

        // 동승자 웹: 늦어요 → 나와 있어요 → 탈 지점 변경 → 불참
        val newPlace = api.registerPlace(store.crewId!!, "판교역 4번 출구", 37.3955, 127.1120).getLong("placeId")
        riderCall(riderToken, "POST", "/api/trips/$tripId/live/me/delay", """{"minutes":5}""")
        riderCall(riderToken, "POST", "/api/trips/$tripId/live/me/here", null)
        riderCall(riderToken, "PUT", "/api/trips/$tripId/boardings/$riderCrewMemberId/board-place", """{"placeId":$newPlace}""")
        waitUntil { received.any { it is com.paircar.domain.model.CrewLiveEvent.RiderPlaceChanged } }
        waitUntil { local.todayTrip.value!!.passengers.first { it.id == rider.id }.pickup.name == "판교역 4번 출구" }
        // 남은 경유지(= 내비에 다시 요청한 경유지)가 새 지점으로 바뀐다
        assertEquals("판교역 4번 출구", routeWaypoint(local))

        // 차량 앱 → 동승자: 위치·곧 도착·차 도착, 우회 거리 확인 요청 (불참하기 전, 아직 탈 사람에게)
        sync.sendLocation(trip, com.paircar.core.model.GeoPoint(37.45, 127.05), listOf(com.paircar.data.server.RiderRemain(rider.id, 120, 800)))
        sync.sendApproaching(trip, rider.id)
        sync.sendArrived(trip, rider.id)
        sync.registerDetours(local.todayTrip.value!!) { 3.0 }
        waitUntil { riderEvents.containsAll(listOf("vehicle.location", "vehicle.approaching", "vehicle.arrived", "detour.confirm-requested")) }
        println("[rider] $riderEvents")
        assertEquals(local.todayTrip.value!!.acceptedPassengers.count { it.id in store.crewMemberIds }, store.registeredDetours.size)

        riderCall(riderToken, "DELETE", "/api/trips/$tripId/boardings/$riderCrewMemberId", null)
        waitUntil { local.todayTrip.value!!.passengers.first { it.id == rider.id }.attendance == com.paircar.domain.model.Attendance.ABSENT }
        println("[received] $received")
        println("[notices] $notices")
        assertEquals(
            listOf("${rider.name}님이 5분 늦어요", "${rider.name}님이 나와 있어요", "${rider.name}님이 판교역 4번 출구에서 타요", "${rider.name}님이 오늘은 안 타요"),
            notices.toList(),
        )
        assertTrue(received.first() is com.paircar.domain.model.CrewLiveEvent.RiderDelayed)
        assertEquals(rider.id, (received.first() as com.paircar.domain.model.CrewLiveEvent.RiderDelayed).passengerId)
        // 불참한 동승자는 경유지에서 빠진다
        assertTrue(routeWaypoint(local) != "판교역 4번 출구")

        sync.stopLive()
        scope.cancel()
    }

    private fun routeWaypoint(local: MockCarpoolRepository): String? =
        com.paircar.domain.usecase.remainingWaypoints(local.todayTrip.value!!).firstOrNull()?.name

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) kotlinx.coroutines.delay(50)
    }

    private fun riderCall(token: String, method: String, path: String, body: String?) {
        val connection = (java.net.URL(baseUrl!!.trimEnd('/') + path).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val status = connection.responseCode
        val text = (if (status < 300) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText()
        assertTrue("$method $path → $status $text", status in 200..299)
    }
}

private class MemoryServerStore : ServerStore {
    override var token: String? = null
    override var memberId: Long? = null
    override var crewId: Long? = null
    override var carId: Long? = null
    override val vehicleKey: String = "test-" + UUID.randomUUID()
    override var driverCrewMemberId: Long? = null
    override var registeredDetours: Set<String> = emptySet()
    override var seededRiderTokens: Map<String, String> = emptyMap()
    override var placeIds: Map<String, Long> = emptyMap()
    override var crewMemberIds: Map<String, Long> = emptyMap()
    override var tripIds: Map<String, Long> = emptyMap()
    override var rideLog: RideLog? = null
    override var outbox: List<TripOp> = emptyList()
    override var syncedAccountHash: String? = null
    override var syncedFuelPrice: String? = null
    override fun reset() {
        token = null; memberId = null; crewId = null; carId = null; driverCrewMemberId = null
        registeredDetours = emptySet(); seededRiderTokens = emptyMap()
        placeIds = emptyMap(); crewMemberIds = emptyMap(); tripIds = emptyMap()
        syncedAccountHash = null; syncedFuelPrice = null
    }
}
