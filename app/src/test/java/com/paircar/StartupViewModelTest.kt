package com.paircar

import com.paircar.core.common.VehicleCheckStore
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.feature.startup.StartupStep
import com.paircar.feature.startup.StartupUiState
import com.paircar.feature.startup.StartupViewModel
import com.paircar.vehicle.navi.MockNaviRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val checkedAt = LocalDateTime.of(2026, 11, 3, 6, 58)

    private class MemoryStore : VehicleCheckStore {
        var saved: LocalDateTime? = null
        override fun lastCheckedAt(): LocalDateTime? = saved
        override fun saveCheckedAt(time: LocalDateTime) {
            saved = time
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.startup(connectDelayMillis: Long, store: VehicleCheckStore): Pair<StartupViewModel, () -> StartupUiState> {
        val navi = MockNaviRepository(backgroundScope, connectDelayMillis = connectDelayMillis)
        val viewModel = StartupViewModel(navi, MockCarpoolRepository(latencyMillis = 0), store, now = { checkedAt }, clock = { testScheduler.currentTime })
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel to { viewModel.uiState.value }
    }

    @Test
    fun `연결되면 단계를 모두 지나 확인 시각을 저장한다`() = runTest(dispatcher) {
        val store = MemoryStore()
        val (_, state) = startup(connectDelayMillis = 500, store)

        advanceUntilIdle()

        assertTrue(state().done)
        assertFalse(state().failed)
        // Mock 데이터: 한민서 불참 1건
        assertEquals(1, state().changeCount)
        assertEquals(checkedAt, store.saved)
        assertEquals(checkedAt, state().lastCheckedAt)
        val features = state().features.associate { it.label to it.verified }
        assertEquals(true, features["내비게이션 경로 연동"])
        // Mock 내비는 차량 위치를 주지 않는다
        assertEquals(false, features["차량 위치 및 주행 거리"])
    }

    @Test
    fun `연결이 안 되면 실패로 멈추고 확인 시각을 저장하지 않는다`() = runTest(dispatcher) {
        val store = MemoryStore()
        val (_, state) = startup(connectDelayMillis = 60_000, store)

        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()

        assertTrue(state().failed)
        assertFalse(state().done)
        assertEquals(StartupStep.CONNECT, state().step)
        assertNull(state().changeCount)
        assertNull(store.saved)
    }

    @Test
    fun `이전 확인 시각을 처음부터 보여준다`() = runTest(dispatcher) {
        val store = MemoryStore().apply { saved = checkedAt.minusDays(1) }
        val (_, state) = startup(connectDelayMillis = 500, store)

        testScheduler.runCurrent()

        assertNotNull(state().lastCheckedAt)
        assertEquals(checkedAt.minusDays(1), state().lastCheckedAt)
    }
}
