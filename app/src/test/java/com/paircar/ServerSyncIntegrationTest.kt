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
        val sync = ServerSync(
            api = api, store = store, carpool = local, settings = { settings }, energy = energy,
            scope = scope, seedPassengers = true, log = { logs += it; println("[sync] $it") },
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
        val tripId = store.tripIds.getValue(trip.id)
        val result = api.trip(tripId)
        println("[server] $result")

        // 시각: 서버는 Z 로 돌려주지만 같은 순간이고, 운행일은 KST 날짜다
        assertTrue(result.getString("startedAt").endsWith("Z"))
        assertEquals(trip.date.toString(), result.getString("serviceDate"))
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
        assertEquals(null, crew.members.single { it.name == "웹가입" }.passengerId)
        assertEquals(trip.acceptedPassengers.size + 2, crew.members.size)

        // 보낸 시각(+09:00)과 받은 시각(Z)이 같은 순간
        val startedAt = ServerTime.parse(result.getString("startedAt"))
        assertTrue(java.time.Duration.between(startedAt, java.time.Instant.now()).abs().seconds < 120)
        scope.cancel()
    }
}

private class MemoryServerStore : ServerStore {
    override var token: String? = null
    override var memberId: Long? = null
    override var crewId: Long? = null
    override var carId: Long? = null
    override val vehicleKey: String = "test-" + UUID.randomUUID()
    override var placeIds: Map<String, Long> = emptyMap()
    override var crewMemberIds: Map<String, Long> = emptyMap()
    override var tripIds: Map<String, Long> = emptyMap()
    override var rideLog: RideLog? = null
    override var outbox: List<TripOp> = emptyList()
    override var syncedAccountHash: String? = null
    override var syncedFuelPrice: String? = null
    override fun reset() {
        token = null; memberId = null; crewId = null; carId = null
        placeIds = emptyMap(); crewMemberIds = emptyMap(); tripIds = emptyMap()
        syncedAccountHash = null; syncedFuelPrice = null
    }
}
