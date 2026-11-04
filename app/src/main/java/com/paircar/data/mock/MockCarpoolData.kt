package com.paircar.data.mock

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.Attendance
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Crew
import com.paircar.domain.model.Driver
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.Passenger
import com.paircar.domain.model.Place
import java.time.LocalDate
import java.time.LocalTime

/**
 * 서버 API 확정 전 화면 개발용 데이터. 확정 시안의 "판교 출근 크루"(서울 광진 → 판교역 → 남양연구소).
 * 좌표는 OpenStreetMap 기준 대략값. 시각은 시안 값이며 교통 반영을 가정한다
 * (OSRM 기준 직접 59.4km·60분, 판교역 경유 68.3km·69분).
 */
object MockCarpoolData {

    /** 서버(모바일웹 가입 주소) 미구축. 예약 도메인(.example)이라 실제로 열리지 않는다. */
    const val INVITE_BASE_URL_PLACEHOLDER = "https://paircar.example/join"

    val crew = Crew(
        id = "crew-pangyo",
        name = "판교 출근 크루",
        startDate = LocalDate.of(2026, 10, 30),
        weekdays = "월-금",
    )

    val origin = Place(
        name = "집",
        address = "서울 광진구 구의동",
        point = GeoPoint(37.5370, 127.0850),
    )

    val destination = Place(
        name = "회사",
        address = "경기 화성시 남양읍 현대연구소로 150",
        point = GeoPoint(37.1557, 126.8267),
    )

    val driver = Driver(
        id = "driver-1",
        name = "최민혁",
        carModel = "아이오닉 6",
        plateNumber = "12가 3456",
    )

    fun todayTrip(date: LocalDate = LocalDate.now()) = CarpoolTrip(
        id = "trip-${date}",
        title = "출근",
        date = date,
        departureTime = LocalTime.of(7, 10),
        estimatedArrivalTime = LocalTime.of(8, 34),
        origin = origin,
        destination = destination,
        driver = driver,
        status = CarpoolStatus.SCHEDULED,
        passengers = listOf(
            Passenger(
                id = "p-1",
                name = "차수아",
                pickup = Place("판교역 2번 출구", "경기 성남시 분당구 판교역로", GeoPoint(37.3947, 127.1115)),
                estimatedPickupTime = LocalTime.of(7, 32),
                matchStatus = MatchStatus.ACCEPTED,
                previousPickup = Place("판교역 3번 출구", "경기 성남시 분당구 판교역로", GeoPoint(37.3950, 127.1108)),
            ),
            Passenger(
                id = "p-2",
                name = "한민서",
                pickup = Place("판교역 3번 출구", "경기 성남시 분당구 판교역로", GeoPoint(37.3950, 127.1108)),
                estimatedPickupTime = LocalTime.of(7, 32),
                matchStatus = MatchStatus.ACCEPTED,
                attendance = Attendance.ABSENT,
            ),
            Passenger(
                id = "p-3",
                name = "박서연",
                pickup = Place("복정역 1번 출구", "서울 송파구 송파대로", GeoPoint(37.4705, 127.1265)),
                estimatedPickupTime = LocalTime.of(7, 24),
                matchStatus = MatchStatus.REQUESTED,
            ),
            Passenger(
                id = "p-4",
                name = "최도윤",
                pickup = Place("광교중앙역 2번 출구", "경기 수원시 영통구 광교중앙로", GeoPoint(37.2886, 127.0516)),
                estimatedPickupTime = LocalTime.of(7, 52),
                matchStatus = MatchStatus.REQUESTED,
            ),
        ),
    )
}
