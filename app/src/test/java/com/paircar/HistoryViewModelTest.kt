package com.paircar

import com.paircar.data.mock.MockTripHistory
import com.paircar.feature.history.HistoryUiState
import com.paircar.feature.history.HistoryViewModel
import com.paircar.feature.history.TripFilter
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
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.history(): Pair<HistoryViewModel, () -> HistoryUiState> {
        val records = MemoryTripRecords().apply { this.records.value = MockTripHistory.records(until = LocalDate.of(2026, 11, 5)) }
        val viewModel = HistoryViewModel(records, FakeRoutes())
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel to { viewModel.uiState.value }
    }

    @Test
    fun `opens on the latest trip`() = runTest(dispatcher) {
        val (_, state) = history()
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 11), state().month)
        assertEquals(LocalDate.of(2026, 11, 4), state().selected?.date)
        assertEquals(LocalDate.of(2026, 11, 2), state().week.first())
        assertEquals(7, state().week.size)
        // 지난 기록에는 경로선이 없어 경로 미리보기로 채운다
        assertTrue(state().selectedPath.isNotEmpty())
    }

    @Test
    fun `october matches the settlement ride count`() = runTest(dispatcher) {
        val (viewModel, state) = history()
        viewModel.selectMonth(YearMonth.of(2026, 10))
        advanceUntilIdle()

        // 평일 22일 − 공휴일 2일
        assertEquals(20, state().count(TripFilter.ALL))
        assertEquals(18, state().count(TripFilter.CARPOOL))
        assertEquals(2, state().count(TripFilter.SOLO))
        assertEquals(LocalDate.of(2026, 10, 30), state().selected?.date)

        viewModel.selectFilter(TripFilter.SOLO)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 23), state().selected?.date)
    }

    @Test
    fun `a day without trips shows nothing selected`() = runTest(dispatcher) {
        val (viewModel, state) = history()
        viewModel.selectDate(LocalDate.of(2026, 10, 24))
        advanceUntilIdle()

        assertNull(state().selected)
        assertEquals(emptyList<Any>(), state().dayRecords)
        assertEquals(LocalDate.of(2026, 10, 24), state().selectedDate)
    }
}
