package com.paircar.domain.model

import com.paircar.core.model.GeoPoint
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

data class Place(
    val name: String,
    val address: String,
    val point: GeoPoint,
    /** 내비 POI ID. 서버/검색 연동 전까지는 null(좌표만 사용). */
    val poiId: String? = null,
)

data class Driver(
    val id: String,
    val name: String,
    val carModel: String,
    val plateNumber: String,
)

enum class MatchStatus { REQUESTED, ACCEPTED, DECLINED, CANCELLED }

enum class BoardingStatus { WAITING, ARRIVED_AT_PICKUP, BOARDED, NO_SHOW, DROPPED_OFF }

enum class CarpoolStatus { SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED }

/** 크루원이 오늘 운행에 참여하는지. 불참이면 경유지에서 뺀다. */
enum class Attendance { ATTENDING, ABSENT }

data class Passenger(
    val id: String,
    val name: String,
    val pickup: Place,
    val estimatedPickupTime: LocalTime,
    val matchStatus: MatchStatus,
    val boardingStatus: BoardingStatus = BoardingStatus.WAITING,
    val attendance: Attendance = Attendance.ATTENDING,
    /** 픽업 장소가 바뀌었으면 바뀌기 전 장소 (경유지 변경 알림용) */
    val previousPickup: Place? = null,
    /** 운전자가 탑승 완료를 누른 시각 (운행 기록용) */
    val boardedAt: LocalTime? = null,
) {
    val isAttending: Boolean
        get() = attendance == Attendance.ATTENDING

    val isPendingPickup: Boolean
        get() = matchStatus == MatchStatus.ACCEPTED && isAttending &&
            (boardingStatus == BoardingStatus.WAITING || boardingStatus == BoardingStatus.ARRIVED_AT_PICKUP)
}

data class CarpoolTrip(
    val id: String,
    val title: String,
    val date: LocalDate,
    val departureTime: LocalTime,
    val estimatedArrivalTime: LocalTime,
    val origin: Place,
    val destination: Place,
    val driver: Driver,
    val passengers: List<Passenger>,
    val status: CarpoolStatus,
) {
    val acceptedPassengers: List<Passenger>
        get() = passengers.filter { it.matchStatus == MatchStatus.ACCEPTED }

    val requestedPassengers: List<Passenger>
        get() = passengers.filter { it.matchStatus == MatchStatus.REQUESTED }

    /** 픽업 예정 시간 순으로 정렬된, 아직 탑승하지 않은 동승자 */
    val pendingPickups: List<Passenger>
        get() = passengers.filter { it.isPendingPickup }.sortedBy { it.estimatedPickupTime }

    val nextPickup: Passenger?
        get() = pendingPickups.firstOrNull()

    val boardedCount: Int
        get() = acceptedPassengers.count { it.boardingStatus == BoardingStatus.BOARDED }

    /** 수락된 동승자 중 오늘 참석하는 사람을 픽업 시간순으로 (= 경유지) */
    val pickupsInOrder: List<Passenger>
        get() = acceptedPassengers.filter { it.isAttending }.sortedBy { it.estimatedPickupTime }

    /** 오늘 경유지 중 픽업 장소가 바뀐 동승자 */
    val changedPickups: List<Passenger>
        get() = pickupsInOrder.filter { it.previousPickup != null }

    /** 일정상 출발~도착 시간. 경로 미리보기(교통 미반영)보다 이 값을 "총 소요 시간"으로 쓴다. */
    val scheduledDurationSeconds: Int
        get() = Duration.between(departureTime, estimatedArrivalTime).seconds.toInt()

    /** 지도 경로선용: 출발지 → 픽업 지점(시간순) → 목적지 */
    fun planStops(): List<GeoPoint> =
        listOf(origin.point) + pickupsInOrder.map { it.pickup.point } + destination.point

    /** 경유지 없이 출발지 → 목적지 (경로 비교용) */
    fun directStops(): List<GeoPoint> = listOf(origin.point, destination.point)
}
