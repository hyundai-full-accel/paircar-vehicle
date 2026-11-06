package com.paircar

import com.paircar.data.mock.MockSettlementData
import com.paircar.domain.model.EfficiencySource
import com.paircar.domain.model.MemberRides
import com.paircar.domain.model.SettlementPolicy
import com.paircar.domain.model.SettlementReceipt
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.model.estimateRideWon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettlementTest {

    private val october = MockSettlementData.october2026

    @Test
    fun `receipt splits shared distance and adds detour`() {
        val receipt = october.receipts.single { it.member.name == "차수아" }

        assertEquals(108.0, receipt.sharedShareKm, 0.0)
        assertEquals(54.0, receipt.detourKm, 0.0)
        assertEquals(162.0, receipt.burdenKm, 0.0)
        // 162km ÷ 5.5km/kWh × 347원/kWh = 10,220.7…
        assertEquals(10_221L, receipt.chargeWon)
        assertEquals(0L, receipt.tollWon)
        assertEquals(79L, receipt.roundingWon)
        assertEquals(10_300L, receipt.totalWon)
    }

    @Test
    fun `paid and excluded members owe nothing`() {
        val dues = october.receipts.associate { it.member.name to it.dueWon }

        assertEquals(mapOf("차수아" to 10_300L, "이도윤" to 0L, "한민서" to 0L), dues)
        assertEquals(10_300L, october.totalDueWon)
    }

    @Test
    fun `exact hundreds are not rounded up again`() {
        // 100km ÷ 5km/kWh × 500원/kWh = 10,000원
        val receipt = SettlementReceipt(
            MemberRides(name = "A", rides = 1, sharedKm = 200.0, headcount = 2, detourKmPerRide = 0.0),
            SettlementPolicy(efficiencyKmPerKwh = 5.0, efficiencySource = EfficiencySource.MANUAL, chargePricePerKwh = 500),
        )

        assertEquals(10_000L, receipt.totalWon)
        assertEquals(0L, receipt.roundingWon)
    }

    @Test
    fun `one ride estimate uses the same rule as the monthly receipt`() {
        // (40km ÷ 2 + 8.9km) ÷ 5.5km/kWh × 347원/kWh = 1,823.3… → 1,900원
        assertEquals(1_900L, october.policy.estimateRideWon(sharedKm = 40.0, headcount = 2, detourKm = 8.9))
    }

    @Test
    fun `vehicle efficiency replaces the entered value`() {
        val manual = october.policy

        val cumulative = manual.withVehicle(VehicleEnergy(cumulativeEvEfficiency = 6.0, sinceChargeEvEfficiency = 4.0))
        assertEquals(6.0, cumulative.efficiencyKmPerKwh, 0.0)
        assertEquals(EfficiencySource.VEHICLE_CUMULATIVE, cumulative.efficiencySource)

        val sinceCharge = manual.withVehicle(VehicleEnergy(sinceChargeEvEfficiency = 4.0))
        assertEquals(EfficiencySource.VEHICLE_SINCE_CHARGE, sinceCharge.efficiencySource)

        // 차량이 전비를 주지 않으면(에뮬레이터) 입력값 그대로
        assertSame(manual, manual.withVehicle(VehicleEnergy(odometerKm = 0.0)))
    }
}
