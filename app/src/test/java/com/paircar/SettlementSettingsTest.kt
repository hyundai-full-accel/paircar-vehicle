package com.paircar

import com.paircar.data.mock.MockSettlementData
import com.paircar.data.mock.MockSettlementRepository
import com.paircar.data.mock.MockTripHistory
import com.paircar.domain.model.CostRules
import com.paircar.domain.model.EfficiencySource
import com.paircar.domain.model.ReceiverAccount
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.model.resolve
import com.paircar.feature.settlement.SettlementUiState
import com.paircar.feature.settlement.SettlementViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SettlementSettingsTest {

    private val october = MockSettlementData.october2026
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun chasua(settings: SettlementSettings) =
        october.resolve(settings, request = null, energy = VehicleEnergy()).receipts.single { it.member.name == "차수아" }

    @Test
    fun `turning off rounding keeps the exact amount`() {
        val receipt = chasua(SettlementSettings(rules = CostRules(roundUp = false)))

        assertEquals(10_221L, receipt.totalWon)
        assertEquals(0L, receipt.roundingWon)
    }

    @Test
    fun `charge price from settings replaces the default`() {
        // 162km ÷ 5.5km/kWh × 400원/kWh = 11,781.8… → 11,800원
        val receipt = chasua(SettlementSettings(chargePricePerKwh = 400))

        assertEquals(11_782L, receipt.chargeWon)
        assertEquals(11_800L, receipt.totalWon)
    }

    @Test
    fun `excluding the charge leaves only other costs`() {
        val receipt = chasua(SettlementSettings(rules = CostRules(includeCharge = false)))

        assertEquals(0L, receipt.chargeWon)
        assertEquals(0L, receipt.totalWon)
    }

    @Test
    fun `entered efficiency is used when the vehicle gives none`() {
        val policy = october.resolve(SettlementSettings(efficiencyKmPerKwh = 6.0), null, VehicleEnergy()).policy
        assertEquals(6.0, policy.efficiencyKmPerKwh, 0.0)
        assertEquals(EfficiencySource.MANUAL, policy.efficiencySource)

        // 차량 전비가 있으면 차량 값이 이긴다
        val vehicle = october.resolve(SettlementSettings(efficiencyKmPerKwh = 6.0), null, VehicleEnergy(cumulativeEvEfficiency = 5.0)).policy
        assertEquals(EfficiencySource.VEHICLE_CUMULATIVE, vehicle.efficiencySource)
    }

    @Test
    fun `per ride share adds up to the monthly charge`() {
        val receipt = chasua(SettlementSettings())

        // (216km ÷ 18회 ÷ 2 + 3km) ÷ 5.5 × 347 = 567.8… → 568원
        assertEquals(568L, receipt.perRideWon)
        // 회차별 반올림 차이만 남는다
        assertTrue(kotlin.math.abs(receipt.perRideWon * 18 - receipt.chargeWon) <= 18)
    }

    @Test
    fun `account shows only the last four digits`() {
        assertEquals("KB국민 ····7890", ReceiverAccount("KB국민", "12345678907890").masked)
    }

    private fun TestScope.settlement(store: MemorySettlementSettings): Pair<SettlementViewModel, () -> SettlementUiState> {
        val records = MemoryTripRecords().apply { this.records.value = MockTripHistory.records(until = LocalDate.of(2026, 11, 5)) }
        val viewModel = SettlementViewModel(MockSettlementRepository(), store, records, SequenceEnergy(), today = { LocalDate.of(2026, 11, 5) })
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel to { viewModel.uiState.value }
    }

    @Test
    fun `request needs a receiver account`() = runTest(dispatcher) {
        val store = MemorySettlementSettings()
        val (viewModel, state) = settlement(store)
        advanceUntilIdle()

        var message: String? = null
        viewModel.requestSettlement { message = it }
        advanceUntilIdle()

        assertEquals("수취 계좌를 먼저 등록해 주세요", message)
        assertNull(state().settlement?.requestedAt)
    }

    @Test
    fun `requested month keeps its amount when settings change later`() = runTest(dispatcher) {
        val store = MemorySettlementSettings(SettlementSettings(account = ReceiverAccount("KB국민", "1234567890")))
        val (viewModel, state) = settlement(store)
        advanceUntilIdle()

        viewModel.requestSettlement {}
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 5), state().settlement?.requestedAt)
        assertEquals(10_300L, state().settlement?.totalDueWon)

        // 요청 뒤 단가를 올려도 요청한 달 금액은 그대로
        viewModel.saveSettings(store.settings.value.copy(chargePricePerKwh = 400)) {}
        advanceUntilIdle()
        assertEquals(10_300L, state().settlement?.totalDueWon)
    }

    @Test
    fun `breakdown lists only carpool trips of the month`() = runTest(dispatcher) {
        val (_, state) = settlement(MemorySettlementSettings())
        advanceUntilIdle()

        // 10월 평일 22일 − 공휴일 2일 − 혼자 간 날 2일
        assertEquals(18, state().carpoolRecords.size)
        assertTrue(state().carpoolRecords.all { "차수아" in it.passengerNames })
    }
}
