package com.paircar.domain.usecase

import com.paircar.domain.model.Attendance
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.CrewLiveEvent
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.Passenger
import com.paircar.domain.repository.CarpoolRepository

/**
 * 서버 실시간 알림(SSE)을 차량 앱에 반영한다. 주행 중 운전자 조작 없이 알림으로만 알린다.
 * - 지각·나와 있음: 알림만 (경로는 그대로)
 * - 탈 지점 변경: 동승자 픽업 지점을 바꾸고, 운행 중이면 내비 경유지를 다시 요청
 * - 불참: 오늘 불참으로 바꾸고, 운행 중이면 경유지에서 뺀다
 * - 영수증 발행: 정산을 다시 받는다
 *
 * [carpool] 은 서버로 다시 보내지 않는 저장소여야 한다 (서버에서 온 변경을 서버에 되돌려 보내지 않도록).
 */
class ApplyCrewLiveEventUseCase(
    private val carpool: CarpoolRepository,
    private val routeSync: CarpoolRouteSync,
    private val notify: (title: String, text: String) -> Unit,
    private val onReceiptIssued: suspend () -> Unit = {},
) {
    suspend operator fun invoke(event: CrewLiveEvent) {
        when (event) {
            is CrewLiveEvent.RiderDelayed -> notify("${event.name}님이 ${event.minutes}분 늦어요", "경로는 그대로예요")

            is CrewLiveEvent.RiderHere -> notify(
                "${event.name}님이 나와 있어요",
                passenger(event)?.pickup?.name?.let { "$it 에서 기다리고 있어요" } ?: "탈 지점에서 기다리고 있어요",
            )

            is CrewLiveEvent.RiderPlaceChanged -> {
                val passenger = passenger(event)
                if (passenger != null) {
                    // 서버는 주소를 주지 않는다. 지역 표시는 바뀌기 전 지점의 주소로 대신한다 (같은 단지의 다른 문 등)
                    carpool.updatePickup(passenger.id, event.place.copy(address = passenger.pickup.address))
                    resyncRoute()
                }
                notify("${event.name}님이 ${event.place.name}에서 타요", if (passenger != null) "경유지를 바꿨어요" else "탈 지점이 바뀌었어요")
            }

            is CrewLiveEvent.RiderAbsent -> {
                val passenger = passenger(event)
                if (passenger != null && passenger.isAttending) {
                    carpool.updateAttendance(passenger.id, Attendance.ABSENT)
                    resyncRoute()
                }
                notify("${event.name}님이 오늘은 안 타요", if (passenger != null) "경유지에서 뺐어요" else "오늘 운행에서 빠져요")
            }

            CrewLiveEvent.ReceiptIssued -> onReceiptIssued()

            is CrewLiveEvent.DetourConfirmRequested, is CrewLiveEvent.Other -> Unit
        }
    }

    private fun passenger(event: CrewLiveEvent.Rider): Passenger? =
        event.passengerId?.let { id -> carpool.todayTrip.value?.passengers?.firstOrNull { it.id == id } }

    /** 운행 중이면 남은 경유지가 바뀐 경우에만 내비 경로를 다시 요청한다 */
    private fun resyncRoute() {
        val trip = carpool.todayTrip.value ?: return
        if (trip.status == CarpoolStatus.IN_PROGRESS) routeSync.sync(trip)
    }
}

/**
 * 동승자별 남은 시간·거리: 아직 차가 도착하지 않은(WAITING) 동승자를 내비 경유지와 짝짓는다.
 * 내비 경유지 이름이 픽업 지점 이름과 같으면 그것을, 아니면 순서대로 짝짓는다.
 * @return 동승자 ID → (남은 초, 남은 미터)
 */
fun riderRemains(trip: CarpoolTrip, guidance: GuidanceState): Map<String, Pair<Int?, Int?>> {
    val waiting = trip.pickupsInOrder.filter { it.boardingStatus == BoardingStatus.WAITING }
    return waiting.mapIndexedNotNull { index, passenger ->
        val waypoint = guidance.waypoints.firstOrNull { it.name == passenger.pickup.name } ?: guidance.waypoints.getOrNull(index)
        waypoint?.let { passenger.id to (it.remainTimeSeconds to it.remainDistanceMeters) }
    }.toMap()
}
