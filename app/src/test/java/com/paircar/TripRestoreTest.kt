package com.paircar

import com.paircar.data.local.SavedTripState
import com.paircar.data.local.TripStateStore
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.usecase.FinishCarpoolUseCase
import com.paircar.domain.usecase.MemoryTripSessionStore
import com.paircar.domain.usecase.TripRecorder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** Pleos 창 닫기 = 앱 프로세스 종료. 다시 열면 같은 저장소로 새 객체가 만들어지는 것을 흉내 낸다. */
class TripRestoreTest {

    private class MemoryStateStore : TripStateStore {
        var state: SavedTripState? = null
        override fun load() = state
        override fun save(state: SavedTripState) {
            this.state = state
        }
    }

    private val stateStore = MemoryStateStore()
    private val sessionStore = MemoryTripSessionStore()

    private fun repository(now: LocalDateTime = LocalDateTime.of(2026, 11, 2, 7, 53)) =
        MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() }, now = { now }, stateStore = stateStore)

    @Test
    fun `trip in progress comes back after the app is closed`() = runTest {
        val before = repository()
        before.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        before.updateBoardingStatus("p-1", BoardingStatus.BOARDED)

        val after = repository()

        val trip = after.todayTrip.value!!
        assertEquals(CarpoolStatus.IN_PROGRESS, trip.status)
        assertEquals(BoardingStatus.BOARDED, trip.passengers.single { it.id == "p-1" }.boardingStatus)
        assertEquals(LocalTime.of(7, 53), trip.passengers.single { it.id == "p-1" }.boardedAt)
    }

    @Test
    fun `saved state from another day is ignored`() = runTest {
        repository().updateTripStatus(CarpoolStatus.IN_PROGRESS)

        val tomorrow = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute(TestTrips.commute().date.plusDays(1)) }, stateStore = stateStore)

        assertEquals(CarpoolStatus.SCHEDULED, tomorrow.todayTrip.value!!.status)
    }

    @Test
    fun `record keeps the real start time after reopening`() = runTest {
        val records = MemoryTripRecords()
        val startedAt = LocalDateTime.of(2026, 11, 2, 7, 41)
        TripRecorder(records, SequenceEnergy(VehicleEnergy(odometerKm = 100.0)), FakeRoutes(), sessionStore) { startedAt }.start()
        val carpool = repository()
        carpool.updateTripStatus(CarpoolStatus.IN_PROGRESS)

        // 앱이 다시 켜져 새 기록기가 만들어진다
        val reopened = TripRecorder(records, SequenceEnergy(VehicleEnergy(odometerKm = 160.0)), FakeRoutes(), sessionStore) {
            LocalDateTime.of(2026, 11, 2, 8, 33)
        }
        FinishCarpoolUseCase(repository(), reopened)(arrivedByNavi = true)

        val record = records.records.value.single()
        assertEquals(LocalTime.of(7, 41), record.startTime)
        assertEquals(60.0, record.distanceKm, 1e-9)
    }
}
