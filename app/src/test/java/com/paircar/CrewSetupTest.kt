package com.paircar

import com.paircar.core.model.GeoPoint
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CrewSetup
import com.paircar.domain.model.CrewSetupState
import com.paircar.domain.model.Place
import com.paircar.feature.crewsetup.CrewSetupForm
import com.paircar.feature.crewsetup.CrewSetupViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class CrewSetupTest {

    private val home = Place("집", "", GeoPoint(37.537, 127.085))
    private val work = Place("회사", "", GeoPoint(37.1557, 126.8267))
    private val valid = CrewSetupForm(name = "판교 출근 크루", origin = home, destination = work)

    @Test
    fun `이름·요일·출발지·도착지가 다 있어야 만들 수 있다`() {
        assertNull(CrewSetupViewModel.validate(valid))
        assertEquals("크루 이름을 입력해 주세요", CrewSetupViewModel.validate(valid.copy(name = "  ")))
        assertEquals("반복 운행 요일을 하나 이상 골라 주세요", CrewSetupViewModel.validate(valid.copy(days = emptySet())))
        assertEquals("출발지를 정해 주세요", CrewSetupViewModel.validate(valid.copy(origin = null)))
        assertEquals("도착지를 정해 주세요", CrewSetupViewModel.validate(valid.copy(destination = null)))
        // 크루 안에서 장소 이름은 하나뿐이다
        assertEquals("출발지와 도착지 이름을 다르게 정해 주세요", CrewSetupViewModel.validate(valid.copy(destination = work.copy(name = "집"))))
        assertTrue(CrewSetupViewModel.validate(valid.copy(name = "가".repeat(21)))!!.contains("20자"))
    }

    @Test
    fun `서버 없이 쓸 때 크루를 만들면 동승자 없는 오늘 운행이 생긴다`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { null }, crew = null)
        assertEquals(CrewSetupState.NEEDS_SETUP, carpool.setupState.value)

        carpool.setupCrew(CrewSetup("판교 출근 크루", LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY), home, work))

        assertEquals(CrewSetupState.READY, carpool.setupState.value)
        assertEquals("판교 출근 크루", carpool.crew.value!!.name)
        val trip = carpool.todayTrip.value!!
        assertEquals(LocalTime.of(7, 30), trip.departureTime)
        assertEquals("집", trip.origin.name)
        assertEquals("회사", trip.destination.name)
        assertTrue(trip.passengers.isEmpty())
        assertEquals(CarpoolStatus.SCHEDULED, trip.status)
    }
}
