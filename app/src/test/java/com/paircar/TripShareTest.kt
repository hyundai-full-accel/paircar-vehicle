package com.paircar

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.Place
import com.paircar.domain.model.TripKind
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStop
import com.paircar.domain.model.TripStopType
import com.paircar.feature.history.shareText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TripShareTest {

    @Test
    fun `area keeps only the city and district`() {
        assertEquals("경기 성남시 분당구", place("경기 성남시 분당구 판교역로").area)
        assertEquals("서울 광진구", place("서울 광진구 구의동").area)
        // 구가 없으면 시까지
        assertEquals("경기 화성시", place("경기 화성시 남양읍 현대연구소로 150").area)
    }

    @Test
    fun `share text leaves out passenger names and place names`() {
        val point = GeoPoint(37.0, 127.0)
        val record = TripRecord(
            id = "t",
            kind = TripKind.CARPOOL,
            date = LocalDate.of(2026, 10, 30),
            startTime = LocalTime.of(7, 10),
            endTime = LocalTime.of(8, 21),
            distanceKm = 68.3,
            distanceSource = DistanceSource.ODOMETER,
            detourKm = 8.9,
            stops = listOf(
                TripStop(LocalTime.of(7, 10), TripStopType.DEPART, "집", point, area = "서울 광진구"),
                TripStop(LocalTime.of(7, 34), TripStopType.PICKUP, "판교역 2번 출구", point, "차수아", "경기 성남시 분당구"),
                // 지역을 모르는 예전 기록
                TripStop(LocalTime.of(8, 21), TripStopType.ARRIVE, "회사", point),
            ),
            path = emptyList(),
            arrivedByNavi = true,
        )

        val text = record.shareText()

        assertEquals(
            listOf(
                "[페어카 운행 요약]",
                "10월 30일 (금) 07:10~08:21 · 1시간 11분",
                "총 이동 거리 68.3km · 추가 우회 +8.9km · 동승 1명",
                "07:10 출발 · 서울 광진구",
                "07:34 픽업 · 경기 성남시 분당구",
                "08:21 도착 · 지역 정보 없음",
            ).joinToString("\n"),
            text,
        )
        listOf("차수아", "판교역", "집", "회사").forEach { assertFalse(it, text.contains(it)) }
    }

    private fun place(address: String) = Place("p", address, GeoPoint(0.0, 0.0))
}
