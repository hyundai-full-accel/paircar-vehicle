package com.paircar.domain.model

import com.paircar.core.model.GeoPoint
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

data class Passenger(
    val id: String,
    val name: String,
    val pickup: Place,
    val estimatedPickupTime: LocalTime,
    val matchStatus: MatchStatus,
    val boardingStatus: BoardingStatus = BoardingStatus.WAITING,
) {
    val isPendingPickup: Boolean
        get() = matchStatus == MatchStatus.ACCEPTED &&
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

    /** 수락된 동승자를 픽업 시간순으로 */
    val pickupsInOrder: List<Passenger>
        get() = acceptedPassengers.sortedBy { it.estimatedPickupTime }

    /** 지도 경로선용: 출발지 → 픽업 지점(시간순) → 목적지 */
    fun planStops(): List<GeoPoint> =
        listOf(origin.point) + pickupsInOrder.map { it.pickup.point } + destination.point
}
