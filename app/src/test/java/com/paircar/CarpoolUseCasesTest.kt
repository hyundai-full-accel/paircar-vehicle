package com.paircar

import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.TripKind
import com.paircar.domain.model.TripStopType
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.usecase.ApplyNaviEventUseCase
import com.paircar.domain.usecase.FinishCarpoolUseCase
import com.paircar.domain.usecase.StartCarpoolGuidanceUseCase
import com.paircar.domain.usecase.TripRecorder
import com.paircar.vehicle.navi.MockNaviRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class CarpoolUseCasesTest {

    private val records = MemoryTripRecords()

    private fun recorder(vararg energy: VehicleEnergy, times: List<LocalDateTime> = emptyList()): TripRecorder {
        val clock = ArrayDeque(times)
        return TripRecorder(records, SequenceEnergy(*energy), FakeRoutes()) {
            clock.removeFirstOrNull() ?: LocalDateTime.of(2026, 11, 2, 8, 30)
        }
    }

    @Test
    fun `starting guidance without navi connection keeps trip scheduled`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        val navi = MockNaviRepository(backgroundScope)

        val result = StartCarpoolGuidanceUseCase(carpool, navi, recorder())(carpool.todayTrip.value!!)

        assertEquals(true, result is NaviCommandResult.Rejected)
        assertEquals(CarpoolStatus.SCHEDULED, carpool.todayTrip.value!!.status)
    }

    @Test
    fun `starting guidance marks trip in progress`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        val navi = MockNaviRepository(backgroundScope, connectDelayMillis = 0)
        navi.connect()
        runCurrent()

        val result = StartCarpoolGuidanceUseCase(carpool, navi, recorder())(carpool.todayTrip.value!!)

        assertEquals(NaviCommandResult.Requested, result)
        assertEquals(CarpoolStatus.IN_PROGRESS, carpool.todayTrip.value!!.status)
    }

    @Test
    fun `navi arrival events update boarding and trip status`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        carpool.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        val apply = ApplyNaviEventUseCase(carpool, FinishCarpoolUseCase(carpool, recorder()))

        apply(NaviEvent.WaypointArrived("판교역 1번 출구"))
        assertEquals(BoardingStatus.ARRIVED_AT_PICKUP, carpool.todayTrip.value!!.passengers.first { it.id == "p-1" }.boardingStatus)

        carpool.updateBoardingStatus("p-1", BoardingStatus.BOARDED)
        apply(NaviEvent.DestinationArrived("현대자동차 양재사옥"))

        val trip = carpool.todayTrip.value!!
        assertEquals(CarpoolStatus.COMPLETED, trip.status)
        assertEquals(BoardingStatus.DROPPED_OFF, trip.passengers.first { it.id == "p-1" }.boardingStatus)
    }

    @Test
    fun `arrival saves a carpool record with the boarded pickup`() = runTest {
        val boardedAt = LocalDateTime.of(2026, 11, 2, 7, 53)
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() }, now = { boardedAt })
        val tripRecorder = recorder(
            // 에뮬레이터처럼 주행거리가 0.0 에서 안 바뀐다
            VehicleEnergy(odometerKm = 0.0),
            VehicleEnergy(odometerKm = 0.0),
            times = listOf(LocalDateTime.of(2026, 11, 2, 7, 41), LocalDateTime.of(2026, 11, 2, 8, 33)),
        )
        carpool.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        tripRecorder.start()
        carpool.updateBoardingStatus("p-1", BoardingStatus.BOARDED)

        ApplyNaviEventUseCase(carpool, FinishCarpoolUseCase(carpool, tripRecorder))(NaviEvent.DestinationArrived("현대자동차 양재사옥"))

        val record = records.records.value.single()
        assertEquals(TripKind.CARPOOL, record.kind)
        assertEquals(LocalTime.of(7, 41), record.startTime)
        assertEquals(LocalTime.of(8, 33), record.endTime)
        assertEquals(listOf(TripStopType.DEPART, TripStopType.PICKUP, TripStopType.ARRIVE), record.stops.map { it.type })
        assertEquals(LocalTime.of(7, 53), record.stops[1].time)
        assertEquals(listOf("김민지"), record.passengerNames)
        // 주행거리가 안 바뀌면 경로 거리로 대신한다
        assertEquals(DistanceSource.ROUTE_ESTIMATE, record.distanceSource)
        assertEquals(68.3, record.distanceKm, 1e-9)
        assertEquals(8.9, record.detourKm!!, 1e-9)
        assertEquals(true, record.arrivedByNavi)
        assertEquals(true, tripRecorder.lastFinish.value?.saved)
    }

    @Test
    fun `detour is left empty when route lookup falls back to a straight line`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        val tripRecorder = TripRecorder(records, SequenceEnergy(), FakeRoutes(directFails = true))
        carpool.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        tripRecorder.start()
        carpool.updateBoardingStatus("p-1", BoardingStatus.BOARDED)

        FinishCarpoolUseCase(carpool, tripRecorder)(arrivedByNavi = true)

        val record = records.records.value.single()
        assertEquals(TripKind.CARPOOL, record.kind)
        assertEquals(null, record.detourKm)
    }

    @Test
    fun `odometer difference is used when the car reports it`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        val tripRecorder = recorder(VehicleEnergy(odometerKm = 10_592.0), VehicleEnergy(odometerKm = 10_650.5))
        carpool.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        tripRecorder.start()

        FinishCarpoolUseCase(carpool, tripRecorder)(arrivedByNavi = false)

        val record = records.records.value.single()
        assertEquals(TripKind.SOLO, record.kind)
        assertEquals(DistanceSource.ODOMETER, record.distanceSource)
        assertEquals(58.5, record.distanceKm, 1e-9)
        assertEquals(null, record.detourKm)
        assertEquals(false, record.arrivedByNavi)
    }
}
