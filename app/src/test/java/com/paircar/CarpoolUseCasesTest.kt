package com.paircar

import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.usecase.ApplyNaviEventUseCase
import com.paircar.domain.usecase.StartCarpoolGuidanceUseCase
import com.paircar.vehicle.navi.MockNaviRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarpoolUseCasesTest {

    @Test
    fun `starting guidance without navi connection keeps trip scheduled`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        val navi = MockNaviRepository(backgroundScope)

        val result = StartCarpoolGuidanceUseCase(carpool, navi)(carpool.todayTrip.value!!)

        assertEquals(true, result is NaviCommandResult.Rejected)
        assertEquals(CarpoolStatus.SCHEDULED, carpool.todayTrip.value!!.status)
    }

    @Test
    fun `starting guidance marks trip in progress`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        val navi = MockNaviRepository(backgroundScope, connectDelayMillis = 0)
        navi.connect()
        runCurrent()

        val result = StartCarpoolGuidanceUseCase(carpool, navi)(carpool.todayTrip.value!!)

        assertEquals(NaviCommandResult.Requested, result)
        assertEquals(CarpoolStatus.IN_PROGRESS, carpool.todayTrip.value!!.status)
    }

    @Test
    fun `navi arrival events update boarding and trip status`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        carpool.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        val apply = ApplyNaviEventUseCase(carpool)

        apply(NaviEvent.WaypointArrived("판교역 1번 출구"))
        assertEquals(BoardingStatus.ARRIVED_AT_PICKUP, carpool.todayTrip.value!!.passengers.first { it.id == "p-1" }.boardingStatus)

        carpool.updateBoardingStatus("p-1", BoardingStatus.BOARDED)
        apply(NaviEvent.DestinationArrived("현대자동차 양재사옥"))

        val trip = carpool.todayTrip.value!!
        assertEquals(CarpoolStatus.COMPLETED, trip.status)
        assertEquals(BoardingStatus.DROPPED_OFF, trip.passengers.first { it.id == "p-1" }.boardingStatus)
    }
}
