package com.paircar

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Driver
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.Passenger
import com.paircar.domain.model.Place
import java.time.LocalDate
import java.time.LocalTime

/** 테스트 고정 데이터: 수락 2명 + 요청 2명 (경유지 2곳 제한 검증용). 화면용 목업과 분리해 둔다. */
object TestTrips {
    fun commute(date: LocalDate = LocalDate.of(2026, 11, 2)) = CarpoolTrip(
        id = "trip-$date",
        title = "출근",
        date = date,
        departureTime = LocalTime.of(7, 40),
        estimatedArrivalTime = LocalTime.of(8, 35),
        origin = Place("정자역 2번 출구", "경기 성남시 분당구 정자일로", GeoPoint(37.3670, 127.1085)),
        destination = Place("현대자동차 양재사옥", "서울 서초구 헌릉로 12", GeoPoint(37.4632, 127.0423)),
        driver = Driver("driver-1", "한지훈", "아이오닉 5", "12가 3456"),
        status = CarpoolStatus.SCHEDULED,
        passengers = listOf(
            Passenger("p-1", "김민지", Place("판교역 1번 출구", "경기 성남시 분당구 판교역로", GeoPoint(37.3948, 127.1112)),
                LocalTime.of(7, 52), MatchStatus.ACCEPTED),
            Passenger("p-2", "이준호", Place("양재시민의숲역 1번 출구", "서울 서초구 매헌로", GeoPoint(37.4705, 127.0384)),
                LocalTime.of(8, 25), MatchStatus.ACCEPTED),
            Passenger("p-3", "박서연", Place("서현역 4번 출구", "경기 성남시 분당구 서현로", GeoPoint(37.3851, 127.1234)),
                LocalTime.of(7, 47), MatchStatus.REQUESTED),
            Passenger("p-4", "최도윤", Place("청계산입구역", "서울 서초구 청계산로", GeoPoint(37.4486, 127.0559)),
                LocalTime.of(8, 15), MatchStatus.REQUESTED),
        ),
    )
}
