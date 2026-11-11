package com.paircar.vehicle.navi

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.GuidanceStatus
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviDiagnosticCall
import com.paircar.domain.model.NaviError
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.model.NaviScreen
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.model.WaypointProgress
import com.paircar.domain.model.WaypointSlot
import com.paircar.domain.repository.NaviRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 차량/에뮬레이터 없이 UI 를 개발하기 위한 내비 시뮬레이터.
 * 경로 요청 시 구간마다 [segmentSeconds] 가 걸리는 것으로 가정하고,
 * [tickMillis] 마다 [simulatedSecondsPerTick] 초씩 진행시켜 경유지/목적지 도착 이벤트를 낸다.
 */
class MockNaviRepository(
    private val scope: CoroutineScope,
    private val connectDelayMillis: Long = 500,
    private val tickMillis: Long = 1_000,
    private val simulatedSecondsPerTick: Int = 30,
    private val segmentSeconds: Int = 600,
    private val metersPerSecond: Int = 10,
) : NaviRepository {

    override val provider = NaviProvider.MOCK

    private val _connectionState = MutableStateFlow<NaviConnectionState>(NaviConnectionState.Disconnected)
    override val connectionState: StateFlow<NaviConnectionState> = _connectionState.asStateFlow()

    private val _guidanceState = MutableStateFlow(GuidanceState())
    override val guidanceState: StateFlow<GuidanceState> = _guidanceState.asStateFlow()

    // Mock 은 차량 위치를 시뮬레이션하지 않는다(지도에는 경로와 정류장만 표시)
    override val currentLocation: StateFlow<GeoPoint?> = MutableStateFlow<GeoPoint?>(null).asStateFlow()

    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NaviEvent> = _events.asSharedFlow()

    private var connectJob: Job? = null
    private var simulationJob: Job? = null

    /** 남은 경유지 이름과 각 경유지까지 남은 시간(초) */
    private var remainingWaypoints = mutableListOf<Pair<String, Int>>()
    private var remainSeconds = 0

    private val isConnected get() = _connectionState.value is NaviConnectionState.Connected

    override fun connect() {
        val state = _connectionState.value
        if (state is NaviConnectionState.Connected || state is NaviConnectionState.Connecting) return
        _connectionState.value = NaviConnectionState.Connecting
        connectJob = scope.launch {
            delay(connectDelayMillis)
            _connectionState.value = NaviConnectionState.Connected(NaviProvider.MOCK)
            _events.tryEmit(NaviEvent.Info("[Mock] 내비 연결됨"))
        }
    }

    override fun disconnect() {
        connectJob?.cancel()
        simulationJob?.cancel()
        _guidanceState.value = GuidanceState()
        _connectionState.value = NaviConnectionState.Disconnected
    }

    override fun requestRoute(request: RouteRequest): NaviCommandResult {
        if (!isConnected) return notConnected()
        simulationJob?.cancel()
        remainingWaypoints = request.waypoints
            .mapIndexed { index, place -> place.name to segmentSeconds * (index + 1) }
            .toMutableList()
        remainSeconds = segmentSeconds * (request.waypoints.size + 1)
        _guidanceState.value = GuidanceState(
            status = GuidanceStatus.GUIDING,
            destinationName = request.destination.name,
        )
        publishProgress()
        _events.tryEmit(NaviEvent.RouteStarted(remainSeconds * metersPerSecond, remainSeconds))
        _events.tryEmit(NaviEvent.RoutingCompleted)
        startSimulation(request.destination.name)
        return NaviCommandResult.Requested
    }

    override fun addWaypoint(place: Place, slot: WaypointSlot): NaviCommandResult {
        if (!isConnected) return notConnected()
        if (_guidanceState.value.status != GuidanceStatus.GUIDING) {
            return NaviCommandResult.Rejected(NaviError.ROUTE_SEARCH_FAILED, "경로 안내 중이 아닙니다")
        }
        val index = slot.ordinal.coerceAtMost(remainingWaypoints.size)
        val arriveAt = (remainingWaypoints.getOrNull(index)?.second ?: remainSeconds) / 2
        remainingWaypoints.add(index, place.name to arriveAt.coerceAtLeast(simulatedSecondsPerTick))
        remainSeconds += segmentSeconds / 2
        publishProgress()
        _events.tryEmit(NaviEvent.Info("[Mock] 경유지 추가: ${place.name}"))
        return NaviCommandResult.Requested
    }

    override fun removeWaypoint(slot: WaypointSlot): NaviCommandResult {
        if (!isConnected) return notConnected()
        if (slot.ordinal >= remainingWaypoints.size) {
            return NaviCommandResult.Rejected(NaviError.ROUTE_SEARCH_FAILED, "그 경유지가 없습니다")
        }
        val removed = remainingWaypoints.removeAt(slot.ordinal)
        publishProgress()
        _events.tryEmit(NaviEvent.Info("[Mock] 경유지 삭제: ${removed.first}"))
        return NaviCommandResult.Requested
    }

    override fun cancelRoute(): NaviCommandResult {
        if (!isConnected) return notConnected()
        simulationJob?.cancel()
        _guidanceState.value = GuidanceState(status = GuidanceStatus.CANCELLED)
        _events.tryEmit(NaviEvent.RouteCancelled)
        return NaviCommandResult.Requested
    }

    override fun refreshRouteState(): NaviCommandResult {
        if (!isConnected) return notConnected()
        _events.tryEmit(NaviEvent.Info("[Mock] 경로 상태: ${_guidanceState.value.status}"))
        return NaviCommandResult.Requested
    }

    override fun showNavigationScreen(screen: NaviScreen): NaviCommandResult {
        if (!isConnected) return notConnected()
        _events.tryEmit(NaviEvent.Info("[Mock] 내비 화면 요청: $screen (실제 화면 전환 없음)"))
        return NaviCommandResult.Requested
    }

    override fun runDiagnostic(call: NaviDiagnosticCall): NaviCommandResult {
        if (!isConnected) return notConnected()
        _events.tryEmit(NaviEvent.Info("[Mock] ${call.label} (실제 호출 없음)"))
        return NaviCommandResult.Requested
    }

    private fun startSimulation(destinationName: String) {
        simulationJob = scope.launch {
            while (remainSeconds > 0) {
                delay(tickMillis)
                remainSeconds = (remainSeconds - simulatedSecondsPerTick).coerceAtLeast(0)
                remainingWaypoints = remainingWaypoints
                    .map { (name, seconds) -> name to (seconds - simulatedSecondsPerTick) }
                    .toMutableList()
                while (remainingWaypoints.firstOrNull()?.let { it.second <= 0 } == true) {
                    val arrived = remainingWaypoints.removeAt(0)
                    _events.tryEmit(NaviEvent.WaypointArrived(arrived.first))
                }
                publishProgress()
            }
            _guidanceState.value = _guidanceState.value.copy(status = GuidanceStatus.ARRIVED)
            _events.tryEmit(NaviEvent.DestinationArrived(destinationName))
        }
    }

    private fun publishProgress() {
        _guidanceState.value = _guidanceState.value.copy(
            remainDistanceMeters = remainSeconds * metersPerSecond,
            remainTimeSeconds = remainSeconds,
            waypoints = remainingWaypoints.map { (name, seconds) ->
                WaypointProgress(name, seconds * metersPerSecond, seconds)
            },
        )
    }

    private fun notConnected() =
        NaviCommandResult.Rejected(NaviError.NOT_CONNECTED, "[Mock] 내비에 연결되지 않았습니다")
}
