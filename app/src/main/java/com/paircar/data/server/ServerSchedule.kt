package com.paircar.data.server

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.Attendance
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.CrewMember
import com.paircar.domain.model.Driver
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.Passenger
import com.paircar.domain.model.Place
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** 서버 크루 장소 (PlaceSummary) */
data class ServerPlace(val placeId: Long, val name: String, val latitude: Double, val longitude: Double) {
    val ref: PlaceRef
        get() = PlaceRef(name, latitude, longitude)
}

/** 크루 멤버의 평소 탈 곳·내릴 곳 */
data class MemberPlaces(val board: ServerPlace?, val alight: ServerPlace?)

/** 그날 출석 (GET /api/crews/{crewId}/attendance/{date}). 따로 정하지 않았으면 참석·평소 탈 곳 */
data class ServerAttendance(
    val crewMemberId: Long,
    val nickname: String,
    val attending: Boolean,
    val boardPlace: ServerPlace?,
    val alightPlace: ServerPlace?,
    /** 그날만 따로 정했는지 */
    val changed: Boolean,
)

/** 서버의 크루 (GET /api/crews/{crewId} + /members + 오늘 출석) */
data class ServerCrew(
    val crewId: Long,
    val name: String,
    val members: List<CrewMember>,
    /** KST. 정하지 않았으면 null */
    val departureTime: LocalTime? = null,
    val departureDays: Set<DayOfWeek> = emptySet(),
    val origin: ServerPlace? = null,
    val destination: ServerPlace? = null,
    val settlementMode: String = "FAIR",
    val includeDriver: Boolean = true,
    /** crewMemberId → 평소 탈 곳·내릴 곳 */
    val memberPlaces: Map<Long, MemberPlaces> = emptyMap(),
    /** 오늘(KST) 출석. crewMemberId → 출석 */
    val todayAttendance: Map<Long, ServerAttendance> = emptyMap(),
) {
    /** 출발 시각·출발지·목적지가 다 있어야 오늘 운행을 서버 값으로 만들 수 있다 */
    val hasSchedule: Boolean
        get() = departureTime != null && origin != null && destination != null
}

/**
 * 서버 크루 일정·멤버 탈 곳·오늘 출석으로 오늘 운행을 만든다. 일정이 없으면 null (앱 예시 운행을 쓴다).
 *
 * - 동승자 = 운전자를 뺀 크루 멤버 중 탈 곳이 있는 사람. 탈 곳이 없는 멤버(막 가입)는 크루 명단에만 보인다.
 * - 오늘만 다른 곳에서 타면 [Passenger.previousPickup] 에 평소 탈 곳을 둔다 (경유지 변경 알림).
 * - 픽업 예정 시각 = 출발 시각 + 출발지 → 탈 곳 소요 시간. 서버에 픽업 시각이 없어 경로 미리보기로 구한다.
 * - 서버에 주소가 없어, 앞서 쓰던 운행([previous])에 같은 이름의 장소가 있으면 그 주소를 쓴다 (없으면 빈 주소).
 * - 차종·번호판은 서버에 없어 [vehicleName] 을 쓴다.
 *
 * @param travelSeconds 두 지점 사이 소요 시간(초). 못 구하면 null
 */
suspend fun buildTodayTrip(
    date: LocalDate,
    crew: ServerCrew,
    previous: CarpoolTrip?,
    vehicleName: String = "",
    travelSeconds: suspend (List<GeoPoint>) -> Int?,
): CarpoolTrip? {
    if (!crew.hasSchedule) return null
    val departure = crew.departureTime!!
    val knownAddresses = (listOfNotNull(previous?.origin, previous?.destination) + previous?.passengers.orEmpty().flatMap { listOfNotNull(it.pickup, it.previousPickup) })
        .associate { it.name to it.address }
    fun ServerPlace.toPlace() = Place(name, knownAddresses[name].orEmpty(), GeoPoint(latitude, longitude))

    val origin = crew.origin!!.toPlace()
    val destination = crew.destination!!.toPlace()
    val driver = crew.members.firstOrNull { it.isDriver }

    val passengers = crew.members.filterNot { it.isDriver }.mapNotNull { member ->
        val crewMemberId = member.id.toLong()
        val usual = crew.memberPlaces[crewMemberId]?.board
        val today = crew.todayAttendance[crewMemberId]
        val board = today?.boardPlace ?: usual ?: return@mapNotNull null
        val pickup = board.toPlace()
        val seconds = travelSeconds(listOf(origin.point, pickup.point)) ?: 0
        Passenger(
            id = member.passengerId ?: "cm-$crewMemberId",
            name = member.name,
            pickup = pickup,
            estimatedPickupTime = departure.plusSeconds(seconds.toLong()),
            matchStatus = MatchStatus.ACCEPTED,
            attendance = if (today?.attending == false) Attendance.ABSENT else Attendance.ATTENDING,
            previousPickup = usual?.takeIf { today?.changed == true && it.placeId != board.placeId }?.toPlace(),
        )
    }
    val stops = listOf(origin.point) + passengers.filter { it.isAttending }.sortedBy { it.estimatedPickupTime }.map { it.pickup.point } + destination.point
    val totalSeconds = travelSeconds(stops) ?: travelSeconds(listOf(origin.point, destination.point)) ?: 0

    return CarpoolTrip(
        id = "trip-$date",
        // 서버에 운행 이름이 없어 출발 시각으로 정한다
        title = if (departure.hour < 12) "출근" else "퇴근",
        date = date,
        departureTime = departure,
        estimatedArrivalTime = departure.plusSeconds(totalSeconds.toLong()),
        origin = origin,
        destination = destination,
        driver = Driver(
            id = driver?.id ?: "driver",
            name = driver?.name.orEmpty(),
            carModel = vehicleName,
            plateNumber = "",
        ),
        passengers = passengers,
        status = CarpoolStatus.SCHEDULED,
    )
}
