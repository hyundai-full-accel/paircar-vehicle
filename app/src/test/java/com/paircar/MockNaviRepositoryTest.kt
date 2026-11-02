package com.paircar

import com.paircar.domain.model.GuidanceStatus
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviError
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.model.RouteRequest
import com.paircar.vehicle.navi.MockNaviRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockNaviRepositoryTest {

    private val trip = TestTrips.commute()
    private val request = RouteRequest(trip.destination, trip.acceptedPassengers.map { it.pickup })

    @Test
    fun `commands are rejected before connection`() = runTest {
        val navi = MockNaviRepository(backgroundScope)

        val result = navi.requestRoute(request)

        assertTrue(result is NaviCommandResult.Rejected && result.error == NaviError.NOT_CONNECTED)
    }

    @Test
    fun `route simulation arrives at waypoints then destination`() = runTest {
        val navi = MockNaviRepository(backgroundScope, connectDelayMillis = 10, tickMillis = 100)
        val events = mutableListOf<NaviEvent>()
        backgroundScope.launch { navi.events.collect { events += it } }
        runCurrent()

        navi.connect()
        advanceTimeBy(20)
        assertEquals(NaviConnectionState.Connected(NaviProvider.MOCK), navi.connectionState.value)

        assertEquals(NaviCommandResult.Requested, navi.requestRoute(request))
        assertEquals(GuidanceStatus.GUIDING, navi.guidanceState.value.status)
        assertEquals(2, navi.guidanceState.value.waypoints.size)

        // 구간 3개 × 600초 ÷ 틱당 30초 = 60틱 × 100ms
        advanceTimeBy(6_100)
        runCurrent()

        assertEquals(GuidanceStatus.ARRIVED, navi.guidanceState.value.status)
        val arrivals = events.filter { it is NaviEvent.WaypointArrived || it is NaviEvent.DestinationArrived }
        assertEquals(
            listOf(
                NaviEvent.WaypointArrived("판교역 1번 출구"),
                NaviEvent.WaypointArrived("양재시민의숲역 1번 출구"),
                NaviEvent.DestinationArrived(trip.destination.name),
            ),
            arrivals,
        )
    }

    @Test
    fun `cancel stops guidance`() = runTest {
        val navi = MockNaviRepository(backgroundScope, connectDelayMillis = 0)
        navi.connect()
        runCurrent()
        navi.requestRoute(request)

        navi.cancelRoute()

        assertEquals(GuidanceStatus.CANCELLED, navi.guidanceState.value.status)
    }
}
