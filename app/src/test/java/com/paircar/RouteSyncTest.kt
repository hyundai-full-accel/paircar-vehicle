package com.paircar

import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.repository.NaviRepository
import com.paircar.domain.usecase.CarpoolRouteSync
import com.paircar.vehicle.navi.MockNaviRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteSyncTest {

    /** 내비에 보낸 경로 요청을 기록한다 */
    private class RecordingNavi(private val inner: NaviRepository) : NaviRepository by inner {
        val requests = mutableListOf<RouteRequest>()
        override fun requestRoute(request: RouteRequest): NaviCommandResult =
            inner.requestRoute(request).also { requests += request }
    }

    private fun TestScope.setUp(): Triple<MockCarpoolRepository, RecordingNavi, CarpoolRouteSync> {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        val navi = RecordingNavi(MockNaviRepository(backgroundScope, connectDelayMillis = 0))
        navi.connect()
        runCurrent()
        return Triple(carpool, navi, CarpoolRouteSync(navi))
    }

    private fun RecordingNavi.lastWaypoints() = requests.last().waypoints.map { it.name }

    private suspend fun MockCarpoolRepository.started(routeSync: CarpoolRouteSync) {
        routeSync.request(todayTrip.value!!)
        updateTripStatus(CarpoolStatus.IN_PROGRESS)
    }

    @Test
    fun `no-show removes that pickup from the navi route`() = runTest {
        val (carpool, navi, routeSync) = setUp()
        carpool.started(routeSync)
        assertEquals(listOf("판교역 1번 출구", "양재시민의숲역 1번 출구"), navi.lastWaypoints())

        carpool.updateBoardingStatus("p-1", BoardingStatus.NO_SHOW)
        val result = routeSync.sync(carpool.todayTrip.value!!)

        assertEquals(NaviCommandResult.Requested, result)
        assertEquals(listOf("양재시민의숲역 1번 출구"), navi.lastWaypoints())
    }

    @Test
    fun `boarding after the navi arrived does not ask for a new route`() = runTest {
        val (carpool, navi, routeSync) = setUp()
        carpool.started(routeSync)

        routeSync.onWaypointArrived()
        carpool.updateBoardingStatus("p-1", BoardingStatus.BOARDED)

        assertNull(routeSync.sync(carpool.todayTrip.value!!))
        assertEquals(1, navi.requests.size)
    }

    @Test
    fun `boarding before the navi arrived drops the waypoint`() = runTest {
        val (carpool, navi, routeSync) = setUp()
        carpool.started(routeSync)

        carpool.updateBoardingStatus("p-1", BoardingStatus.BOARDED)
        routeSync.sync(carpool.todayTrip.value!!)

        assertEquals(listOf("양재시민의숲역 1번 출구"), navi.lastWaypoints())
    }

    @Test
    fun `deferred third pickup joins after the first one is passed`() = runTest {
        val (carpool, navi, routeSync) = setUp()
        // 수락 3명: 서현역(07:47) → 판교역(07:52) → 양재시민의숲역(08:25). 내비 경유지는 2곳까지
        carpool.updateMatchStatus("p-3", MatchStatus.ACCEPTED)
        carpool.started(routeSync)
        assertEquals(listOf("서현역 4번 출구", "판교역 1번 출구"), navi.lastWaypoints())

        routeSync.onWaypointArrived()
        carpool.updateBoardingStatus("p-3", BoardingStatus.BOARDED)
        routeSync.sync(carpool.todayTrip.value!!)

        assertEquals(listOf("판교역 1번 출구", "양재시민의숲역 1번 출구"), navi.lastWaypoints())
    }
}
