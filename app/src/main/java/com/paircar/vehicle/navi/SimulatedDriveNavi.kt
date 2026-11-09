package com.paircar.vehicle.navi

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.GuidanceStatus
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.model.WaypointProgress
import com.paircar.domain.model.WaypointSlot
import com.paircar.domain.repository.NaviRepository
import com.paircar.domain.repository.RoutePreviewRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 개발·시연용 차량 이동 시뮬레이션. 에뮬레이터의 Pleos 내비는 차가 움직이지 않는다
 * (Virtual Console 가속·adb geo fix 모두 내비 위치에 반영되지 않음).
 *
 * 실제 내비([inner])에 경로 요청은 그대로 보내고, 주행을 시작하면 그 경로를 따라 차가 가는 것처럼
 * 현재 위치·남은 거리/시간·경유지 도착·목적지 도착을 대신 만든다.
 * - 페어카 지도, 주행 화면, 서버로 보내는 위치(동승자 웹)가 이 값을 쓴다.
 * - 경유지에 닿으면 [stopSeconds] 동안 멈추고, 실제 내비에서도 그 경유지를 지운다 (실차에서 내비가 지나가듯).
 * - 정류장에 가까워지면 느려진다 (곧 도착 알림이 나갈 시간을 준다).
 */
class SimulatedDriveNavi(
    private val inner: NaviRepository,
    private val routes: RoutePreviewRepository,
    private val scope: CoroutineScope,
    /** 시뮬레이션 출발점 (오늘 운행의 출발지). 없으면 내비 현재 위치 */
    private val startPoint: () -> GeoPoint?,
    /** 전체 경로를 이 시간(초) 안팎으로 달린다 */
    private val driveSeconds: Int = 150,
    private val stopSeconds: Int = 8,
    private val tickMillis: Long = 1_000,
) : NaviRepository by inner {

    /** 개발용 화면에서 끄고 켠다. 꺼져 있으면 실제 내비 값만 쓴다 */
    @Volatile
    var enabled: Boolean = true

    private val simLocation = MutableStateFlow<GeoPoint?>(null)
    private val simGuidance = MutableStateFlow<GuidanceState?>(null)
    private val simEvents = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    private var job: Job? = null

    /** 지금 시뮬레이션 중인 남은 경유지 (도착하거나 앱이 지우면 빠진다) */
    private val waypoints = mutableListOf<Place>()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    override val currentLocation: StateFlow<GeoPoint?> =
        combine(inner.currentLocation, simLocation) { real, sim -> sim ?: real }
            .stateIn(scope, SharingStarted.Eagerly, inner.currentLocation.value)

    override val guidanceState: StateFlow<GuidanceState> =
        combine(inner.guidanceState, simGuidance) { real, sim -> sim ?: real }
            .stateIn(scope, SharingStarted.Eagerly, inner.guidanceState.value)

    override val events: SharedFlow<NaviEvent> = merge(inner.events, simEvents).shareIn(scope, SharingStarted.Eagerly)

    override fun requestRoute(request: RouteRequest): NaviCommandResult {
        val result = inner.requestRoute(request)
        if (enabled && result is NaviCommandResult.Requested) {
            job?.cancel()
            job = scope.launch { drive(request) }
        }
        return result
    }

    override fun removeWaypoint(slot: WaypointSlot): NaviCommandResult {
        synchronized(waypoints) { if (slot.ordinal < waypoints.size) waypoints.removeAt(slot.ordinal) }
        return inner.removeWaypoint(slot)
    }

    override fun cancelRoute(): NaviCommandResult {
        stop()
        return inner.cancelRoute()
    }

    private fun stop() {
        job?.cancel()
        job = null
        _running.value = false
        simGuidance.value = null
        simLocation.value = null
    }

    private suspend fun drive(request: RouteRequest) {
        val start = simLocation.value ?: startPoint() ?: inner.currentLocation.value ?: return
        val stops = listOf(start) + request.waypoints.map { it.point } + request.destination.point
        val path = routes.preview(stops).path.takeIf { it.size >= 2 } ?: stops
        val along = cumulative(path)
        val total = along.last()
        // 경유지마다 경로 위에서 가장 가까운 지점까지의 거리 (앞 경유지 다음부터 찾는다)
        var searchFrom = 0
        val marks = request.waypoints.associateWith { place ->
            val index = (searchFrom until path.size).minByOrNull { distance(path[it], place.point) } ?: path.lastIndex
            searchFrom = index
            along[index]
        }
        synchronized(waypoints) {
            waypoints.clear()
            waypoints.addAll(request.waypoints)
        }
        val baseSpeed = maxOf(total / driveSeconds, MIN_SPEED)
        var travelled = 0.0
        var paused = 0
        _running.value = true
        simLocation.value = start
        publish(request, marks, travelled, total)

        while (travelled < total) {
            delay(tickMillis)
            if (paused > 0) {
                paused--
                publish(request, marks, travelled, total)
                continue
            }
            val next = synchronized(waypoints) { waypoints.firstOrNull() }
            val nextAt = next?.let { marks[it] } ?: total
            // 정류장에 가까워지면 남은 거리의 1/5 씩 (최소 MIN_SPEED) 줄여 다가간다
            val speed = minOf(baseSpeed, maxOf((nextAt - travelled) / 5, MIN_SPEED))
            travelled = minOf(total, travelled + speed * tickMillis / 1000.0)
            if (next != null && travelled >= nextAt) {
                travelled = nextAt
                synchronized(waypoints) { waypoints.remove(next) }
                simLocation.value = pointAt(path, along, travelled)
                simEvents.emit(NaviEvent.WaypointArrived(next.name))
                // 실차에서는 내비가 도착한 경유지를 지나간다. 에뮬레이터 내비도 같은 상태가 되도록 지운다
                inner.removeWaypoint(WaypointSlot.FIRST)
                paused = stopSeconds
            }
            simLocation.value = pointAt(path, along, travelled)
            publish(request, marks, travelled, total)
        }
        simGuidance.value = GuidanceState(
            status = GuidanceStatus.ARRIVED,
            destinationName = request.destination.name,
            remainDistanceMeters = 0,
            remainTimeSeconds = 0,
        )
        _running.value = false
        simEvents.emit(NaviEvent.DestinationArrived(request.destination.name))
    }

    /** 남은 거리·시간 (시간은 시내 평균 50km/h 로 어림. 시뮬레이션 속도와 무관하게 실제처럼 보이게) */
    private fun publish(request: RouteRequest, marks: Map<Place, Double>, travelled: Double, total: Double) {
        val remaining = synchronized(waypoints) { waypoints.toList() }
        simGuidance.value = GuidanceState(
            status = GuidanceStatus.GUIDING,
            destinationName = request.destination.name,
            remainDistanceMeters = (total - travelled).toInt(),
            remainTimeSeconds = ((total - travelled) / DISPLAY_SPEED).toInt(),
            waypoints = remaining.map { place ->
                val left = ((marks[place] ?: total) - travelled).coerceAtLeast(0.0)
                WaypointProgress(place.name, left.toInt(), (left / DISPLAY_SPEED).toInt())
            },
        )
    }

    private companion object {
        /** m/s. 시연이 너무 늘어지지 않도록 */
        const val MIN_SPEED = 30.0

        /** m/s (= 50km/h) */
        const val DISPLAY_SPEED = 13.9

        fun cumulative(path: List<GeoPoint>): List<Double> {
            val result = ArrayList<Double>(path.size)
            var sum = 0.0
            path.forEachIndexed { i, point ->
                if (i > 0) sum += distance(path[i - 1], point)
                result += sum
            }
            return result
        }

        fun pointAt(path: List<GeoPoint>, along: List<Double>, meters: Double): GeoPoint {
            val i = along.indexOfFirst { it >= meters }.takeIf { it > 0 } ?: return if (meters <= 0) path.first() else path.last()
            val from = path[i - 1]
            val to = path[i]
            val span = along[i] - along[i - 1]
            val t = if (span <= 0) 0.0 else (meters - along[i - 1]) / span
            return GeoPoint(from.latitude + (to.latitude - from.latitude) * t, from.longitude + (to.longitude - from.longitude) * t)
        }

        fun distance(a: GeoPoint, b: GeoPoint): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
    }
}
