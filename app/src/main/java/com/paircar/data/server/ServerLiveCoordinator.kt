package com.paircar.data.server

import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.Passenger
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.NaviRepository
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.usecase.ApplyCrewLiveEventUseCase
import com.paircar.domain.usecase.riderRemains
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 차량 앱 ↔ 서버 실시간 흐름을 묶는다.
 * - 받기: SSE 구독 → [ApplyCrewLiveEventUseCase] (동승자 지각·나와 있음·탈 지점 변경·불참, 영수증)
 * - 보내기: 운행 중 [LOCATION_INTERVAL_MILLIS] 마다 차량 위치·동승자별 남은 시간 (위치·남은 시간은 Pleos SDK 값),
 *   경유지 [APPROACHING_METERS] 안이면 "곧 도착" 한 번
 * - 우회 거리: 크루 명단을 받으면 아직 등록하지 않은 동승자의 우회 거리를 경로 비교로 구해 올린다
 */
class ServerLiveCoordinator(
    private val sync: ServerSync,
    private val carpool: CarpoolRepository,
    private val navi: NaviRepository,
    private val routes: RoutePreviewRepository,
    private val applyEvent: ApplyCrewLiveEventUseCase,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {

    fun start() {
        sync.startLive()
        scope.launch {
            sync.liveEvents.collect { event ->
                runCatching { applyEvent(event) }.onFailure { log("실시간 알림을 반영하지 못했습니다: $event $it") }
            }
        }
        scope.launch { reportWhileDriving() }
        scope.launch(Dispatchers.IO) {
            sync.crew.filterNotNull().first()
            carpool.todayTrip.filterNotNull().first().let { trip -> sync.registerDetours(trip, ::detourKm) }
        }
    }

    private suspend fun reportWhileDriving() {
        val approached = mutableSetOf<String>()
        var tripKey: String? = null
        while (scope.isActive) {
            delay(LOCATION_INTERVAL_MILLIS)
            val trip = carpool.todayTrip.value ?: continue
            if (trip.status != CarpoolStatus.IN_PROGRESS) continue
            if (trip.id != tripKey) {
                tripKey = trip.id
                approached.clear()
            }
            // Pleos NaviHelper 에 현재 위치·경유지 남은 거리/시간을 다시 요청한다 (결과는 상태 흐름에 반영)
            navi.refreshRouteState()
            val location = navi.currentLocation.value ?: continue
            val remains = riderRemains(trip, navi.guidanceState.value)
            withContext(Dispatchers.IO) {
                sync.sendLocation(trip, location, remains.map { (id, remain) -> RiderRemain(id, remain.first, remain.second) })
                remains.filter { (id, remain) -> id !in approached && remain.second != null && remain.second!! <= APPROACHING_METERS }
                    .keys.forEach { id ->
                        approached += id
                        sync.sendApproaching(trip, id)
                    }
            }
        }
    }

    /** 운전자 출발지 기준: (출발지 → 탈 지점 → 목적지) − (출발지 → 목적지). 길찾기가 어림값이면 등록하지 않는다 */
    private suspend fun detourKm(passenger: Passenger): Double? {
        val trip = carpool.todayTrip.value ?: return null
        val via = routes.preview(listOf(trip.origin.point, passenger.pickup.point, trip.destination.point))
        val direct = routes.preview(trip.directStops())
        if (via.isApproximate || direct.isApproximate) return null
        return (via.distanceMeters - direct.distanceMeters) / 1000.0
    }

    private companion object {
        /** 문서: 5~10초마다 */
        const val LOCATION_INTERVAL_MILLIS = 10_000L

        /** 문서: addProximityAlert(정문 300m) */
        const val APPROACHING_METERS = 300
    }
}
