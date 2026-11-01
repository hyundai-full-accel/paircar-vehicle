package com.paircar.domain.usecase

import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.Passenger
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

class StartCarpoolGuidanceUseCase(
    private val carpoolRepository: CarpoolRepository,
    private val naviRepository: NaviRepository,
) {
    suspend operator fun invoke(trip: CarpoolTrip): NaviCommandResult {
        val plan = buildCarpoolRoutePlan(trip)
        val result = naviRepository.requestRoute(plan.request)
        if (result is NaviCommandResult.Requested && trip.status == CarpoolStatus.SCHEDULED) {
            carpoolRepository.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        }
        return result
    }
}

/**
 * 내비 이벤트를 카풀 상태에 반영한다.
 * 경유지 도착 → 다음 동승자 '픽업 지점 도착', 목적지 도착 → 하차 처리 및 운행 완료.
 */
class ApplyNaviEventUseCase(
    private val carpoolRepository: CarpoolRepository,
) {
    suspend operator fun invoke(event: NaviEvent) {
        val trip = carpoolRepository.todayTrip.value ?: return
        if (trip.status != CarpoolStatus.IN_PROGRESS) return
        when (event) {
            is NaviEvent.WaypointArrived -> {
                val next = trip.pendingPickups.firstOrNull { it.boardingStatus == BoardingStatus.WAITING }
                    ?: return
                carpoolRepository.updateBoardingStatus(next.id, BoardingStatus.ARRIVED_AT_PICKUP)
            }
            is NaviEvent.DestinationArrived -> {
                trip.acceptedPassengers
                    .filter { it.boardingStatus == BoardingStatus.BOARDED }
                    .forEach { carpoolRepository.updateBoardingStatus(it.id, BoardingStatus.DROPPED_OFF) }
                carpoolRepository.updateTripStatus(CarpoolStatus.COMPLETED)
            }
            else -> Unit
        }
    }
}
