package com.paircar

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.GuidanceStatus
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviDiagnosticCall
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.model.NaviScreen
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.model.WaypointSlot
import com.paircar.domain.repository.NaviRepository
import com.paircar.vehicle.navi.SimulatedDriveNavi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedDriveNaviTest {

    /** 에뮬레이터 Pleos 내비처럼: 요청은 받지만 차는 움직이지 않는다 */
    private class StillNavi : NaviRepository {
        val removed = mutableListOf<WaypointSlot>()
        var cancelled = false
        override val provider = NaviProvider.PLEOS
        override val connectionState = MutableStateFlow<NaviConnectionState>(NaviConnectionState.Connected(NaviProvider.PLEOS))
        override val guidanceState = MutableStateFlow(GuidanceState())
        override val currentLocation = MutableStateFlow<GeoPoint?>(GeoPoint(37.41, 127.09))
        override val events = MutableSharedFlow<NaviEvent>()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun requestRoute(request: RouteRequest) = NaviCommandResult.Requested
        override fun addWaypoint(place: Place, slot: WaypointSlot) = NaviCommandResult.Requested
        override fun removeWaypoint(slot: WaypointSlot) = NaviCommandResult.Requested.also { removed += slot }
        override fun cancelRoute() = NaviCommandResult.Requested.also { cancelled = true }
        override fun refreshRouteState() = NaviCommandResult.Requested
        override fun showNavigationScreen(screen: NaviScreen) = NaviCommandResult.Requested
        override fun runDiagnostic(call: NaviDiagnosticCall) = NaviCommandResult.Requested
    }

    private val home = GeoPoint(37.5370, 127.0850)
    private val pickup = Place("판교역 2번 출구", "", GeoPoint(37.3947, 127.1115))
    private val work = Place("회사", "", GeoPoint(37.1557, 126.8267))

    @Test
    fun `주행을 시작하면 경유지에 도착해 멈췄다가 목적지에 도착한다`() = runTest {
        val inner = StillNavi()
        val sim = SimulatedDriveNavi(inner, FakeRoutes(), backgroundScope, startPoint = { home }, driveSeconds = 60)
        val events = mutableListOf<NaviEvent>()
        backgroundScope.launch { sim.events.collect { events += it } }
        runCurrent()

        sim.requestRoute(RouteRequest(destination = work, waypoints = listOf(pickup)))
        runCurrent()
        assertEquals(home, sim.currentLocation.value)
        assertEquals(GuidanceStatus.GUIDING, sim.guidanceState.value.status)
        assertEquals(listOf("판교역 2번 출구"), sim.guidanceState.value.waypoints.map { it.name })

        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(listOf(NaviEvent.WaypointArrived("판교역 2번 출구"), NaviEvent.DestinationArrived("회사")), events)
        // 에뮬레이터 내비도 지나간 경유지를 지운다
        assertEquals(listOf(WaypointSlot.FIRST), inner.removed)
        assertEquals(GuidanceStatus.ARRIVED, sim.guidanceState.value.status)
        val end = sim.currentLocation.value!!
        assertTrue(kotlin.math.abs(end.latitude - work.point.latitude) < 1e-6)
    }

    @Test
    fun `도착 전에 앱이 경유지를 지우면 그 경유지에 서지 않는다`() = runTest {
        val inner = StillNavi()
        val sim = SimulatedDriveNavi(inner, FakeRoutes(), backgroundScope, startPoint = { home }, driveSeconds = 60)
        val events = mutableListOf<NaviEvent>()
        backgroundScope.launch { sim.events.collect { events += it } }
        runCurrent()

        sim.requestRoute(RouteRequest(destination = work, waypoints = listOf(pickup)))
        runCurrent()
        sim.removeWaypoint(WaypointSlot.FIRST)
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(listOf(NaviEvent.DestinationArrived("회사")), events)
        assertEquals(listOf(WaypointSlot.FIRST), inner.removed)
    }

    @Test
    fun `안내를 취소하면 시뮬레이션을 멈추고 실제 내비 값으로 돌아간다`() = runTest {
        val inner = StillNavi()
        val sim = SimulatedDriveNavi(inner, FakeRoutes(), backgroundScope, startPoint = { home })
        runCurrent()
        sim.requestRoute(RouteRequest(destination = work, waypoints = emptyList()))
        runCurrent()
        sim.cancelRoute()
        runCurrent()
        assertTrue(inner.cancelled)
        assertEquals(inner.currentLocation.value, sim.currentLocation.value)
    }
}
