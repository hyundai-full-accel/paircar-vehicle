package com.paircar

import com.paircar.data.server.RideEvent
import com.paircar.data.server.ServerTime
import com.paircar.data.server.buildSegments
import com.paircar.domain.model.resolve
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

class MergeServerCrewTest {

    private val local = com.paircar.domain.model.Crew("crew-pangyo", "판교 출근 크루", LocalDate.of(2026, 10, 1), setOf(java.time.DayOfWeek.MONDAY))
    private val server = com.paircar.data.server.ServerCrew(
        crewId = 7,
        name = "서버 크루",
        members = listOf(
            com.paircar.domain.model.CrewMember("1", "운전자", isDriver = true, joinedAt = LocalDateTime.of(2026, 11, 3, 9, 0)),
            com.paircar.domain.model.CrewMember("2", "동승자", isDriver = false, joinedAt = LocalDateTime.of(2026, 11, 5, 9, 0), passengerId = "p-1"),
        ),
    )

    @Test
    fun `서버 값을 못 받았으면 앱 크루 그대로`() {
        assertEquals(local, com.paircar.data.server.mergeServerCrew(local, null, null))
    }

    @Test
    fun `이름·시작일·명단은 서버, 요일은 앱`() {
        val merged = com.paircar.data.server.mergeServerCrew(local, server, null)!!
        assertEquals("7", merged.id)
        assertEquals("서버 크루", merged.name)
        assertEquals(LocalDate.of(2026, 11, 3), merged.startDate)
        assertEquals(local.days, merged.days)
        assertEquals(server.members, merged.members)
    }

    @Test
    fun `운전자가 바꾼 이름이 있으면 그 이름`() {
        assertEquals("새 이름", com.paircar.data.server.mergeServerCrew(local, server, "새 이름")!!.name)
    }
}

class ServerSettlementTest {

    // 로컬 서버가 실제로 발행한 영수증 값 (20km 동승, 13.1km/L, 1,700원, 통행료 1,500원 반씩)
    private val receipt = com.paircar.data.server.ServerReceipt(
        receiptId = 1, payerNickname = "차수아", payeeNickname = "최민혁",
        boardingDays = 1, sharedDistanceKm = 20.0, detourKm = 0.0, ownDistanceKm = 10.0,
        fuelEconomy = 13.1, fuelEconomySource = "MEASURED", fuelPriceWon = 1700,
        tollWon = 750.0, roundingWon = 52.29, amountWon = 2100,
        payeeBankName = "테스트은행", payeeAccountNumber = "000-000-0000", waived = false,
        issuedAt = LocalDateTime.of(2026, 11, 7, 17, 42),
        items = listOf(com.paircar.data.server.ServerReceiptItem(LocalDate.of(2026, 11, 7), 2047.71)),
    )

    @Test
    fun `발행한 영수증은 서버 금액을 그대로 보여준다`() {
        val settlement = com.paircar.data.server.issuedSettlement(
            java.time.YearMonth.of(2026, 11), "판교 출근 크루", listOf(receipt), roster = listOf("차수아", "한민서"),
        )
        val shown = settlement.receipts.single { it.member.name == "차수아" }
        assertEquals(2100, shown.totalWon)
        assertEquals(2100, shown.dueWon)
        assertEquals(750, shown.tollWon)
        assertEquals(1298, shown.chargeWon)
        assertEquals(52, shown.roundingWon)
        assertEquals(10.0, shown.burdenKm, 0.0)
        assertEquals(2048, shown.perRideWon)
        assertEquals(2, shown.member.headcount)

        assertTrue(settlement.receipts.single { it.member.name == "한민서" }.member.isExcluded)
        assertEquals(2100, settlement.totalDueWon)
        assertEquals(LocalDate.of(2026, 11, 7), settlement.requestedAt)
        assertEquals("000-000-0000", settlement.receiverAccount?.number)
        assertEquals(1, settlement.carpoolDays)
    }

    @Test
    fun `발행한 달은 지금 설정·차량 전비로 다시 계산하지 않는다`() {
        val settlement = com.paircar.data.server.issuedSettlement(java.time.YearMonth.of(2026, 11), "크루", listOf(receipt), emptyList())
        val resolved = settlement.resolve(
            com.paircar.domain.model.SettlementSettings(chargePricePerKwh = 999, efficiencyKmPerKwh = 1.0),
            request = null,
            energy = com.paircar.domain.model.VehicleEnergy(cumulativeEvEfficiency = 7.0),
        )
        assertEquals(settlement, resolved)
    }

    @Test
    fun `서버 내 부담 거리에는 우회 거리가 들어 있어 함께 탄 몫과 나눠 보여준다`() {
        val withDetour = receipt.copy(ownDistanceKm = 13.0, detourKm = 3.0)
        val shown = com.paircar.data.server.issuedSettlement(java.time.YearMonth.of(2026, 11), "크루", listOf(withDetour), emptyList())
            .receipts.single()
        assertEquals(10.0, shown.sharedShareKm, 0.0)
        assertEquals(3.0, shown.detourKm, 0.0)
        assertEquals(13.0, shown.burdenKm, 0.0)
        assertEquals(2, shown.member.headcount)
    }

    @Test
    fun `면제한 영수증은 받을 금액이 없다`() {
        val settlement = com.paircar.data.server.issuedSettlement(java.time.YearMonth.of(2026, 11), "크루", listOf(receipt.copy(waived = true)), emptyList())
        assertEquals(0, settlement.totalDueWon)
    }

    @Test
    fun `발행 전인 달은 기기 운행 기록으로 어림하고, 크루원이 없으면 보여주지 않는다`() {
        val november = java.time.YearMonth.of(2026, 11)
        assertEquals(null, com.paircar.data.server.estimatedSettlement(november, "크루", "운전자", emptyList(), emptyList()))
        val records = com.paircar.data.mock.MockTripHistory.records(until = LocalDate.of(2026, 11, 10))
        val estimate = com.paircar.data.server.estimatedSettlement(november, "크루", "운전자", listOf("차수아", "한민서"), records)!!
        assertEquals(null, estimate.requestedAt)
        assertTrue(estimate.members.single { it.name == "차수아" }.rides > 0)
        assertTrue(estimate.members.single { it.name == "한민서" }.isExcluded)
        // 10월 기록은 11월 어림에 들어가지 않는다
        assertEquals(records.count { it.date.monthValue == 11 && it.passengerNames.isNotEmpty() }, estimate.carpoolDays)
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
