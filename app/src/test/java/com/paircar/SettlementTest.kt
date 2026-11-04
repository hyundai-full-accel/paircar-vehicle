package com.paircar

import com.paircar.data.mock.MockSettlementData
import com.paircar.domain.model.MemberRides
import com.paircar.domain.model.SettlementPolicy
import com.paircar.domain.model.SettlementReceipt
import org.junit.Assert.assertEquals
import org.junit.Test

class SettlementTest {

    private val october = MockSettlementData.october2026

    @Test
    fun `receipt follows the design numbers`() {
        val receipt = october.receipts.single { it.member.name == "차수아" }

        assertEquals(108.0, receipt.sharedShareKm, 0.0)
        assertEquals(54.0, receipt.detourKm, 0.0)
        assertEquals(162.0, receipt.burdenKm, 0.0)
        assertEquals(20_667L, receipt.fuelWon)
        assertEquals(0L, receipt.tollWon)
        assertEquals(33L, receipt.roundingWon)
        assertEquals(20_700L, receipt.totalWon)
    }

    @Test
    fun `paid and excluded members owe nothing`() {
        val dues = october.receipts.associate { it.member.name to it.dueWon }

        assertEquals(mapOf("차수아" to 20_700L, "이도윤" to 0L, "한민서" to 0L), dues)
        assertEquals(20_700L, october.totalDueWon)
    }

    @Test
    fun `exact hundreds are not rounded up again`() {
        // 100km ÷ 10km/L × 1,000원/L = 10,000원
        val receipt = SettlementReceipt(
            MemberRides(name = "A", rides = 1, sharedKm = 200.0, headcount = 2, detourKmPerRide = 0.0),
            SettlementPolicy(fuelEfficiencyKmPerL = 10.0, fuelPricePerL = 1_000),
        )

        assertEquals(10_000L, receipt.totalWon)
        assertEquals(0L, receipt.roundingWon)
    }
}
