package com.paircar

import com.paircar.data.local.CrewInfoStore
import com.paircar.data.local.SavedTripState
import com.paircar.data.mock.MockCarpoolData
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.domain.model.Attendance
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.Crew
import com.paircar.domain.model.CrewInfo
import com.paircar.feature.crew.CrewViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class CrewEditTest {

    private class MemoryCrewInfo(var saved: CrewInfo? = null) : CrewInfoStore {
        override fun load() = saved
        override fun save(info: CrewInfo) {
            saved = info
        }
    }

    @Test
    fun `moving departure shifts arrival and pickups by the same amount`() {
        val trip = TestTrips.commute().rescheduled(LocalTime.of(7, 20))

        assertEquals(LocalTime.of(7, 20), trip.departureTime)
        assertEquals(LocalTime.of(8, 15), trip.estimatedArrivalTime)
        assertEquals(LocalTime.of(7, 32), trip.passengers.first { it.id == "p-1" }.estimatedPickupTime)
    }

    @Test
    fun `days label reads like the design`() {
        assertEquals("월-금", Crew.WEEKDAYS.label())
        assertEquals("매일", DayOfWeek.entries.toSet().label())
        assertEquals("주말", setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).label())
        assertEquals("월·수·목", setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY).label())
    }

    private fun Set<DayOfWeek>.label() = MockCarpoolData.crew.copy(days = this).daysLabel

    @Test
    fun `crew info needs a name and at least one day`() {
        val ok = CrewInfo("판교 출근 크루", LocalTime.of(7, 10), Crew.WEEKDAYS)

        assertNull(CrewViewModel.validateCrewInfo(ok))
        assertEquals("크루 이름을 입력해 주세요", CrewViewModel.validateCrewInfo(ok.copy(name = "  ")))
        assertEquals("반복 운행 요일을 하나 이상 골라 주세요", CrewViewModel.validateCrewInfo(ok.copy(days = emptySet())))
    }

    @Test
    fun `attendance is limited to two pickups and only before departure`() {
        val trip = TestTrips.commute()
        // 수락 2명은 이미 참석 → 그대로면 2곳
        assertNull(CrewViewModel.validateAttendance(trip, emptyMap()))
        // 요청 중인 사람은 경유지가 아니라 세지 않는다
        assertNull(CrewViewModel.validateAttendance(trip, mapOf("p-3" to Attendance.ATTENDING)))

        val started = trip.copy(status = CarpoolStatus.IN_PROGRESS)
        assertEquals(
            "운행을 시작한 뒤에는 픽업 정보를 바꿀 수 없어요",
            CrewViewModel.validateAttendance(started, mapOf("p-1" to Attendance.ABSENT)),
        )
    }

    @Test
    fun `saved crew info renames the crew and moves today's schedule`() = runTest {
        val store = MemoryCrewInfo()
        val repository = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() }, crewInfoStore = store)

        repository.updateCrewInfo(CrewInfo("양재 출근", LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY)))

        assertEquals("양재 출근", repository.crew.value?.name)
        assertEquals("월", repository.crew.value?.daysLabel)
        assertEquals(LocalTime.of(7, 30), repository.todayTrip.value?.departureTime)

        // 앱을 다시 켜도 저장한 시각으로 시작한다
        val reopened = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() }, crewInfoStore = store)
        assertEquals(LocalTime.of(7, 30), reopened.todayTrip.value?.departureTime)
        assertEquals("양재 출근", reopened.crew.value?.name)
    }

    @Test
    fun `trip already on the road keeps its times`() = runTest {
        val repository = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        repository.updateTripStatus(CarpoolStatus.IN_PROGRESS)

        repository.updateCrewInfo(CrewInfo("양재 출근", LocalTime.of(7, 30), Crew.WEEKDAYS))

        assertEquals(LocalTime.of(7, 40), repository.todayTrip.value?.departureTime)
    }

    @Test
    fun `absence survives an app restart`() = runTest {
        val repository = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        repository.updateAttendance("p-1", Attendance.ABSENT)

        val saved = SavedTripState.of(repository.todayTrip.value!!)
        val restored = saved.applyTo(TestTrips.commute())

        assertEquals(Attendance.ABSENT, restored.passengers.first { it.id == "p-1" }.attendance)
        assertEquals(listOf("p-2"), restored.pickupsInOrder.map { it.id })
    }
}
