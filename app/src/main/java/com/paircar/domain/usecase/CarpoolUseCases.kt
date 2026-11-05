package com.paircar.domain.usecase

import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.Passenger
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.NaviRepository

data class CarpoolRoutePlan(
    val request: RouteRequest,
    /** 경유지 2개 제한으로 이번 경로에 포함되지 못한 동승자. 앞선 픽업 후 다시 경로를 요청한다. */
    val deferredPickups: List<Passenger>,
)

/** 남은 픽업 지점(시간순)을 경유지로, 카풀 목적지를 목적지로 하는 경로를 만든다. */
fun buildCarpoolRoutePlan(trip: CarpoolTrip): CarpoolRoutePlan {
    val pending = trip.pendingPickups
    val included = pending.take(RouteRequest.MAX_WAYPOINTS)
    return CarpoolRoutePlan(
        request = RouteRequest(
            destination = trip.destination,
            waypoints = included.map { it.pickup },
        ),
        deferredPickups = pending.drop(included.size),
    )
}

/** 아직 내비가 들러야 할 픽업 지점: 도착 전(WAITING)인 동승자만, 시간순, 최대 2곳 */
fun remainingWaypoints(trip: CarpoolTrip): List<Place> =
    trip.pickupsInOrder
        .filter { it.boardingStatus == BoardingStatus.WAITING }
        .take(RouteRequest.MAX_WAYPOINTS)
        .map { it.pickup }

/**
 * 내비 경유지를 남은 픽업에 맞춘다 (NaviHelper requestRouteWithWaypoints).
 * - 미탑승, 또는 내비가 도착하기 전에 탑승 완료 → 그 경유지를 빼고 경로를 다시 요청
 * - 경유지 2곳 제한으로 미뤄둔 동승자 → 앞 경유지를 지나면 넣어서 다시 요청
 * 내비가 이미 도착한 경유지는 내비가 알아서 넘기므로, 남은 경유지가 같으면 다시 요청하지 않는다
 * (다시 요청하면 내비에 경로 선택 화면이 또 뜬다).
 */
class CarpoolRouteSync(private val naviRepository: NaviRepository) {

    /** 내비에 남아 있다고 보는 경유지 = 마지막 요청 − 도착 이벤트가 온 경유지. 앱이 다시 켜져 모르면 null */
    private var naviWaypoints: List<Place>? = null

    fun request(trip: CarpoolTrip): NaviCommandResult {
        val request = RouteRequest(destination = trip.destination, waypoints = remainingWaypoints(trip))
        val result = naviRepository.requestRoute(request)
        if (result is NaviCommandResult.Requested) naviWaypoints = request.waypoints
        return result
    }

    fun onWaypointArrived() {
        naviWaypoints = naviWaypoints?.drop(1)
    }

    /** 남은 경유지가 바뀌었을 때만 다시 요청한다. 요청하지 않았으면 null */
    fun sync(trip: CarpoolTrip): NaviCommandResult? {
        if (trip.status != CarpoolStatus.IN_PROGRESS) return null
        if (remainingWaypoints(trip) == naviWaypoints) return null
        return request(trip)
    }
}

class StartCarpoolGuidanceUseCase(
    private val carpoolRepository: CarpoolRepository,
    private val routeSync: CarpoolRouteSync,
    private val tripRecorder: TripRecorder,
) {
    suspend operator fun invoke(trip: CarpoolTrip): NaviCommandResult {
        val result = routeSync.request(trip)
        if (result is NaviCommandResult.Requested && trip.status == CarpoolStatus.SCHEDULED) {
            carpoolRepository.updateTripStatus(CarpoolStatus.IN_PROGRESS)
            tripRecorder.start()
        }
        return result
    }
}

/**
 * 운행을 끝낸다: 탄 동승자 하차 처리 → 운행 완료 → 운행 기록 저장.
 * 내비 목적지 도착 이벤트나 운전자의 [운행 종료]로 호출된다.
 */
class FinishCarpoolUseCase(
    private val carpoolRepository: CarpoolRepository,
    private val tripRecorder: TripRecorder,
) {
    suspend operator fun invoke(arrivedByNavi: Boolean) {
        val trip = carpoolRepository.todayTrip.value ?: return
        if (trip.status != CarpoolStatus.IN_PROGRESS) return
        trip.acceptedPassengers
            .filter { it.boardingStatus == BoardingStatus.BOARDED }
            .forEach { carpoolRepository.updateBoardingStatus(it.id, BoardingStatus.DROPPED_OFF) }
        carpoolRepository.updateTripStatus(CarpoolStatus.COMPLETED)
        tripRecorder.finish(carpoolRepository.todayTrip.value ?: trip, arrivedByNavi)
    }
}

/**
 * 내비 이벤트를 카풀 상태에 반영한다.
 * 경유지 도착 → 다음 동승자 '픽업 지점 도착', 목적지 도착 → 운행 종료(하차 처리·기록 저장).
 */
class ApplyNaviEventUseCase(
    private val carpoolRepository: CarpoolRepository,
    private val finishCarpool: FinishCarpoolUseCase,
    private val routeSync: CarpoolRouteSync,
) {
    suspend operator fun invoke(event: NaviEvent) {
        val trip = carpoolRepository.todayTrip.value ?: return
        if (trip.status != CarpoolStatus.IN_PROGRESS) return
        when (event) {
            is NaviEvent.WaypointArrived -> {
                routeSync.onWaypointArrived()
                val next = trip.pendingPickups.firstOrNull { it.boardingStatus == BoardingStatus.WAITING }
                    ?: return
                carpoolRepository.updateBoardingStatus(next.id, BoardingStatus.ARRIVED_AT_PICKUP)
            }
            is NaviEvent.DestinationArrived -> finishCarpool(arrivedByNavi = true)
            else -> Unit
        }
    }
}
