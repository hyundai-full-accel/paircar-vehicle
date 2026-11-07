package com.paircar

import com.paircar.data.server.RideEvent
import com.paircar.data.server.ServerTime
import com.paircar.data.server.buildSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ServerTimeTest {

    @Test
    fun `보낼 때는 KST 오프셋을 붙인다`() {
        val sent = ServerTime.format(LocalDateTime.of(2026, 11, 7, 8, 30, 15, 123_000_000), ZoneId.of("Asia/Seoul"))
        assertEquals("2026-11-07T08:30:15+09:00", sent)
    }

    @Test
    fun `기기가 UTC 여도 같은 순간을 보낸다`() {
        // UTC 23:30 = KST 다음날 08:30
        val sent = ServerTime.format(LocalDateTime.of(2026, 11, 6, 23, 30), ZoneOffset.UTC)
        assertEquals("2026-11-07T08:30:00+09:00", sent)
    }

    @Test
    fun `서버의 Z 응답과 나노초 자릿수를 같은 순간으로 읽는다`() {
        assertEquals(Instant.parse("2026-11-06T23:00:00Z"), ServerTime.parse("2026-11-06T23:00:00Z"))
        assertEquals(ServerTime.parse("2026-11-14T08:41:52.208313Z").epochSecond, ServerTime.parse("2026-11-14T08:41:52.208312606Z").epochSecond)
        assertEquals(ServerTime.parse("2026-11-07T08:00:00+09:00"), ServerTime.parse("2026-11-06T23:00:00Z"))
    }

    @Test
    fun `화면에는 KST 로 바꿔 보여준다 - 운행일 경계`() {
        val kst = ServerTime.toKst("2026-11-06T23:30:00Z")
        assertEquals(LocalDate.of(2026, 11, 7), kst.toLocalDate())
        assertEquals(8, kst.hour)
    }
}

class SegmentBuilderTest {

    @Test
    fun `탑승 인원이 바뀌는 지점마다 구간을 나눈다`() {
        val segments = buildSegments(
            startOdometerKm = 1000.0,
            endOdometerKm = 1025.0,
            events = listOf(
                RideEvent("a", boarded = true, odometerKm = 1001.0),
                RideEvent("b", boarded = true, odometerKm = 1010.0),
                RideEvent("a", boarded = false, odometerKm = 1021.0),
                RideEvent("b", boarded = false, odometerKm = 1021.0),
            ),
            fuelEconomy = 5.456,
            fuelEconomySource = "MEASURED",
        )
        assertEquals(listOf(1, 2, 3, 4), segments.map { it.seq })
        assertEquals(listOf(1000.0 to 1001.0, 1001.0 to 1010.0, 1010.0 to 1021.0, 1021.0 to 1025.0), segments.map { it.startOdometerKm to it.endOdometerKm })
        assertEquals(listOf(emptyList(), listOf("a"), listOf("a", "b"), emptyList()), segments.map { it.riderPassengerIds })
        assertEquals(5.46, segments.first().fuelEconomy, 0.0)
    }

    @Test
    fun `주행거리가 안 바뀌면 구간이 없다 (에뮬레이터)`() {
        val segments = buildSegments(0.0, 0.0, listOf(RideEvent("a", true, 0.0), RideEvent("a", false, 0.0)), 5.5, "MANUAL")
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `거꾸로 가는 주행거리는 앞 값에 맞추고 같은 인원 구간은 합친다`() {
        val segments = buildSegments(
            startOdometerKm = 100.0,
            endOdometerKm = 120.0,
            events = listOf(
                RideEvent("a", true, 105.0),
                RideEvent("x", false, 103.0), // 타지 않은 사람의 하차·흔들린 값
            ),
            fuelEconomy = 5.0,
            fuelEconomySource = "MANUAL",
        )
        assertEquals(listOf(100.0 to 105.0, 105.0 to 120.0), segments.map { it.startOdometerKm to it.endOdometerKm })
        assertEquals(listOf(emptyList(), listOf("a")), segments.map { it.riderPassengerIds })
    }
}
