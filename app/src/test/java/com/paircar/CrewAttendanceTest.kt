package com.paircar

import com.paircar.data.mock.MockCarpoolData
import com.paircar.domain.model.Attendance
import com.paircar.domain.usecase.buildCarpoolRoutePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class CrewAttendanceTest {

    private val trip = MockCarpoolData.todayTrip()

    @Test
    fun `absent crew member is not a waypoint`() {
        val absent = trip.passengers.single { it.attendance == Attendance.ABSENT }

        assertEquals(listOf("차수아"), trip.pickupsInOrder.map { it.name })
        assertEquals(false, absent.isPendingPickup)
        assertEquals(listOf("판교역 2번 출구"), buildCarpoolRoutePlan(trip).request.waypoints.map { it.name })
        assertEquals(3, trip.planStops().size)
    }

    @Test
    fun `absent member still belongs to the crew`() {
        assertEquals(listOf("차수아", "한민서"), trip.acceptedPassengers.map { it.name })
    }

    @Test
    fun `changed pickup is listed only for today's waypoints`() {
        val changed = trip.changedPickups.single()
        assertEquals("차수아", changed.name)
        assertEquals("판교역 3번 출구", changed.previousPickup?.name)
        assertEquals("판교역 2번 출구", changed.pickup.name)

        val absentChanged = trip.copy(
            passengers = trip.passengers.map { if (it.name == "차수아") it.copy(attendance = Attendance.ABSENT) else it },
        )
        assertEquals(emptyList<String>(), absentChanged.changedPickups.map { it.name })
    }

    @Test
    fun `scheduled duration is departure to arrival`() {
        // 07:10 → 08:34
        assertEquals(84 * 60, trip.scheduledDurationSeconds)
    }
}
