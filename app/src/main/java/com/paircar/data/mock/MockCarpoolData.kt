package com.paircar.data.mock

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Driver
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.Passenger
import com.paircar.domain.model.Place
import java.time.LocalDate
import java.time.LocalTime

/** 서버 API 확정 전 화면 개발용 데이터 (분당 → 양재 출근 카풀). 좌표는 대략적인 값. */
object MockCarpoolData {

    val origin = Place(
        name = "정자역 2번 출구",
        address = "경기 성남시 분당구 정자일로",
        point = GeoPoint(37.3670, 127.1085),
    )

    val destination = Place(
        name = "현대자동차 양재사옥",
        address = "서울 서초구 헌릉로 12",
        point = GeoPoint(37.4632, 127.0423),
    )

    val driver = Driver(
        id = "driver-1",
        name = "한지훈",
        carModel = "아이오닉 5",
        plateNumber = "12가 3456",
    )

    fun todayTrip(date: LocalDate = LocalDate.now()) = CarpoolTrip(
        id = "trip-${date}",
        title = "출근",
        date = date,
        departureTime = LocalTime.of(7, 40),
        estimatedArrivalTime = LocalTime.of(8, 35),
        origin = origin,
        destination = destination,
        driver = driver,
        status = CarpoolStatus.SCHEDULED,
        passengers = listOf(
            Passenger(
                id = "p-1",
                name = "김민지",
                pickup = Place("판교역 1번 출구", "경기 성남시 분당구 판교역로", GeoPoint(37.3948, 127.1112)),
                estimatedPickupTime = LocalTime.of(7, 52),
                matchStatus = MatchStatus.ACCEPTED,
            ),
            Passenger(
                id = "p-2",
                name = "이준호",
                pickup = Place("양재시민의숲역 1번 출구", "서울 서초구 매헌로", GeoPoint(37.4705, 127.0384)),
                estimatedPickupTime = LocalTime.of(8, 25),
                matchStatus = MatchStatus.ACCEPTED,
            ),
            Passenger(
                id = "p-3",
                name = "박서연",
                pickup = Place("서현역 4번 출구", "경기 성남시 분당구 서현로", GeoPoint(37.3851, 127.1234)),
                estimatedPickupTime = LocalTime.of(7, 47),
                matchStatus = MatchStatus.REQUESTED,
            ),
            Passenger(
                id = "p-4",
                name = "최도윤",
                pickup = Place("청계산입구역", "서울 서초구 청계산로", GeoPoint(37.4486, 127.0559)),
                estimatedPickupTime = LocalTime.of(8, 15),
                matchStatus = MatchStatus.REQUESTED,
            ),
        ),
    )
}
