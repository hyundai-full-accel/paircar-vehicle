package com.paircar

import com.paircar.core.model.GeoPoint
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.data.server.MemberPlaces
import com.paircar.data.server.ServerAttendance
import com.paircar.data.server.ServerCrew
import com.paircar.data.server.ServerPlace
import com.paircar.data.server.buildTodayTrip
import com.paircar.domain.model.Attendance
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CrewLiveEvent
import com.paircar.domain.model.CrewMember
import com.paircar.domain.model.Place
import com.paircar.domain.usecase.ApplyCrewLiveEventUseCase
import com.paircar.domain.usecase.CarpoolRouteSync
import com.paircar.vehicle.navi.MockNaviRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ServerScheduleTest {

    private val date = LocalDate.of(2026, 11, 9)
    private val home = ServerPlace(1, "집", 37.537, 127.085)
    private val work = ServerPlace(2, "회사", 37.1557, 126.8267)
    private val gate = ServerPlace(3, "판교역 2번 출구", 37.3947, 127.1115)
    private val backGate = ServerPlace(4, "판교역 4번 출구", 37.3955, 127.112)
    private val joined = LocalDateTime.of(2026, 11, 7, 9, 0)

    private val crew = ServerCrew(
        crewId = 7,
        name = "판교 출근 크루",
        members = listOf(
            CrewMember("10", "최민혁", isDriver = true, joinedAt = joined),
            CrewMember("11", "차수아", isDriver = false, joinedAt = joined, passengerId = "p-1"),
            CrewMember("12", "웹가입", isDriver = false, joinedAt = joined, passengerId = "cm-12"),
        ),
        departureTime = LocalTime.of(8, 0),
        origin = home,
        destination = work,
        memberPlaces = mapOf(11L to MemberPlaces(gate, work), 12L to MemberPlaces(null, null)),
    )

    /** 출발지 → 탈 곳 20분, 전체 경로 70분 */
    private val travel: suspend (List<GeoPoint>) -> Int? = { stops -> if (stops.size == 2 && stops.last() != work.let { GeoPoint(it.latitude, it.longitude) }) 1_200 else 4_200 }

    @Test
    fun `서버 일정·탈 곳으로 오늘 운행을 만들고 탈 곳이 없는 멤버는 뺀다`() = runTest {
        val trip = buildTodayTrip(date, crew, TestTrips.commute(date), travelSeconds = travel)!!
        assertEquals(LocalTime.of(8, 0), trip.departureTime)
        assertEquals(LocalTime.of(9, 10), trip.estimatedArrivalTime)
        assertEquals("집", trip.origin.name)
        assertEquals("최민혁", trip.driver.name)
        assertEquals(listOf("p-1"), trip.passengers.map { it.id })
        val rider = trip.passengers.single()
        assertEquals("판교역 2번 출구", rider.pickup.name)
        assertEquals(LocalTime.of(8, 20), rider.estimatedPickupTime)
        assertEquals(Attendance.ATTENDING, rider.attendance)
        assertNull(rider.previousPickup)
    }

    @Test
    fun `오늘 출석에서 불참·다른 탈 곳을 반영하고 평소 탈 곳을 경유지 변경 전 값으로 둔다`() = runTest {
        val today = crew.copy(todayAttendance = mapOf(11L to ServerAttendance(11, "차수아", attending = true, boardPlace = backGate, alightPlace = work, changed = true)))
        val rider = buildTodayTrip(date, today, TestTrips.commute(date), travelSeconds = travel)!!.passengers.single()
        assertEquals("판교역 4번 출구", rider.pickup.name)
        assertEquals("판교역 2번 출구", rider.previousPickup?.name)

        val absent = crew.copy(todayAttendance = mapOf(11L to ServerAttendance(11, "차수아", attending = false, boardPlace = gate, alightPlace = work, changed = true)))
        assertEquals(Attendance.ABSENT, buildTodayTrip(date, absent, TestTrips.commute(date), travelSeconds = travel)!!.passengers.single().attendance)
    }

    @Test
    fun `출발 시각·출발지·목적지 중 하나라도 없으면 서버 일정으로 만들지 않는다`() = runTest {
        assertNull(buildTodayTrip(date, crew.copy(departureTime = null), TestTrips.commute(date), travelSeconds = travel))
        assertNull(buildTodayTrip(date, crew.copy(origin = null), TestTrips.commute(date), travelSeconds = travel))
    }

    @Test
    fun `출발 전에만 서버 일정으로 바꾸고 탑승 상태는 이어간다`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute(date) })
        val serverTrip = buildTodayTrip(date, crew, TestTrips.commute(date), travelSeconds = travel)!!

        carpool.applySchedule(serverTrip)
        assertEquals(listOf("p-1"), carpool.todayTrip.value!!.passengers.map { it.id })

        carpool.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        carpool.updateBoardingStatus("p-1", BoardingStatus.BOARDED)
        carpool.applySchedule(serverTrip.copy(departureTime = LocalTime.of(9, 0)))
        assertEquals(LocalTime.of(8, 0), carpool.todayTrip.value!!.departureTime)
        assertEquals(BoardingStatus.BOARDED, carpool.todayTrip.value!!.passengers.single().boardingStatus)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceChangedEventTest {

    @Test
    fun `출발 전 출석 변경은 오늘 운행에 반영하고, 다른 날은 알림만 띄운다`() = runTest {
        val today = LocalDate.of(2026, 11, 2)
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute(today) })
        val navi = MockNaviRepository(backgroundScope, connectDelayMillis = 0).also { it.connect() }
        runCurrent()
        val notices = mutableListOf<String>()
        val apply = ApplyCrewLiveEventUseCase(carpool, CarpoolRouteSync(navi), notify = { t, _ -> notices += t })

        apply(CrewLiveEvent.RiderAttendanceChanged("p-1", "차수아", today.plusDays(1), attending = false, pickup = null))
        assertEquals(Attendance.ATTENDING, carpool.todayTrip.value!!.passengers.first { it.id == "p-1" }.attendance)

        apply(CrewLiveEvent.RiderAttendanceChanged("p-1", "차수아", today, attending = false, pickup = null))
        assertEquals(Attendance.ABSENT, carpool.todayTrip.value!!.passengers.first { it.id == "p-1" }.attendance)

        val back = Place("판교역 4번 출구", "", GeoPoint(37.3955, 127.112))
        apply(CrewLiveEvent.RiderAttendanceChanged("p-1", "차수아", today, attending = true, pickup = back))
        val passenger = carpool.todayTrip.value!!.passengers.first { it.id == "p-1" }
        assertEquals(Attendance.ATTENDING, passenger.attendance)
        assertEquals("판교역 4번 출구", passenger.pickup.name)

        assertEquals(
            listOf("차수아님이 11월 3일은 안 타요", "차수아님이 오늘은 안 타요", "차수아님이 오늘은 판교역 4번 출구에서 타요"),
            notices,
        )
        assertTrue(notices.size == 3)
    }

    @Test
    fun `새 크루원 참여·평소 탈 곳 변경은 크루를 다시 받고 알린다`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute(LocalDate.of(2026, 11, 2)) })
        val navi = MockNaviRepository(backgroundScope, connectDelayMillis = 0).also { it.connect() }
        runCurrent()
        val notices = mutableListOf<String>()
        var reloads = 0
        val apply = ApplyCrewLiveEventUseCase(carpool, CarpoolRouteSync(navi), notify = { t, _ -> notices += t }, onCrewChanged = { reloads++ })

        apply(CrewLiveEvent.MemberJoined("송연지"))
        apply(CrewLiveEvent.RiderUsualPlacesChanged("p-1", "차수아", Place("대원천 빌라", "", GeoPoint(37.44, 127.13))))
        apply(CrewLiveEvent.RiderUsualPlacesChanged("p-1", "차수아", null))

        assertEquals(3, reloads)
        assertEquals(listOf("송연지님이 크루에 들어왔어요", "차수아님이 평소 대원천 빌라에서 타요", "차수아님이 평소 탈 곳을 바꿨어요"), notices)
    }
}

class PrepareNextTripTest {

    @Test
    fun `끝난 운행은 출발 전으로 돌리고 탑승 상태를 처음으로, 참석 여부는 그대로 둔다`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        carpool.updateAttendance("p-2", Attendance.ABSENT)
        carpool.updateTripStatus(CarpoolStatus.IN_PROGRESS)
        carpool.prepareNextTrip()
        assertEquals(CarpoolStatus.IN_PROGRESS, carpool.todayTrip.value!!.status)

        carpool.updateBoardingStatus("p-1", BoardingStatus.BOARDED)
        carpool.updateTripStatus(CarpoolStatus.COMPLETED)
        carpool.prepareNextTrip()

        val trip = carpool.todayTrip.value!!
        assertEquals(CarpoolStatus.SCHEDULED, trip.status)
        assertTrue(trip.passengers.all { it.boardingStatus == BoardingStatus.WAITING && it.boardedAt == null })
        assertEquals(Attendance.ABSENT, trip.passengers.first { it.id == "p-2" }.attendance)
    }

    @Test
    fun `서버 모드처럼 예시 운행 없이 시작하면 서버 일정이 올 때까지 비어 있다`() = runTest {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { null }, crew = null)
        carpool.refresh()
        assertNull(carpool.todayTrip.value)
        assertNull(carpool.crew.value)
    }
}
