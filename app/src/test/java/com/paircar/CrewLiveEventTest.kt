package com.paircar

import com.paircar.core.model.GeoPoint
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.data.server.SseParser
import com.paircar.domain.model.Attendance
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CrewLiveEvent
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.model.WaypointProgress
import com.paircar.domain.model.WaypointSlot
import com.paircar.domain.repository.NaviRepository
import com.paircar.domain.usecase.ApplyCrewLiveEventUseCase
import com.paircar.domain.usecase.CarpoolRouteSync
import com.paircar.domain.usecase.riderRemains
import com.paircar.vehicle.navi.MockNaviRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun `이벤트 이름과 data 를 빈 줄에서 끊고 keepalive 주석은 버린다`() {
        val parser = SseParser()
        val lines = listOf(
            "event:connected", "data:connected", "",
            ":keepalive", "",
            "event:rider.delayed", "data:{\"type\":\"rider.delayed\",\"data\":{\"minutes\":5}}", "",
            "data: a", "data: b", "",
        )
        val events = lines.mapNotNull(parser::feed)
        assertEquals(
            listOf(
                "connected" to "connected",
                "rider.delayed" to "{\"type\":\"rider.delayed\",\"data\":{\"minutes\":5}}",
                "message" to "a\nb",
            ),
            events,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ApplyCrewLiveEventTest {

    private class RecordingNavi(private val inner: NaviRepository) : NaviRepository by inner {
        val requests = mutableListOf<RouteRequest>()
        val removed = mutableListOf<WaypointSlot>()
        override fun removeWaypoint(slot: WaypointSlot): NaviCommandResult =
            inner.removeWaypoint(slot).also { removed += slot }
        override fun requestRoute(request: RouteRequest): NaviCommandResult =
            inner.requestRoute(request).also { requests += request }
    }

    private val notices = mutableListOf<String>()

    private fun TestScope.setUp(): Triple<MockCarpoolRepository, RecordingNavi, ApplyCrewLiveEventUseCase> {
        val carpool = MockCarpoolRepository(latencyMillis = 0, tripFactory = { TestTrips.commute() })
        val navi = RecordingNavi(MockNaviRepository(backgroundScope, connectDelayMillis = 0))
        navi.connect()
        runCurrent()
        val routeSync = CarpoolRouteSync(navi)
        return Triple(carpool, navi, ApplyCrewLiveEventUseCase(carpool, routeSync, notify = { t, _ -> notices += t }).also {
            routeSync.request(carpool.todayTrip.value!!)
        })
    }

    private suspend fun MockCarpoolRepository.start() = updateTripStatus(CarpoolStatus.IN_PROGRESS)

    @Test
    fun `동승자가 불참하면 내비에서 그 경유지만 지운다`() = runTest {
        val (carpool, navi, apply) = setUp()
        carpool.start()
        assertEquals(listOf("판교역 1번 출구", "양재시민의숲역 1번 출구"), navi.requests.last().waypoints.map { it.name })

        apply(CrewLiveEvent.RiderAbsent("p-1", "차수아"))

        assertEquals(Attendance.ABSENT, carpool.todayTrip.value!!.passengers.first { it.id == "p-1" }.attendance)
        // 경로를 다시 요청하지 않는다 (내비 경로 선택 화면이 다시 뜨지 않는다)
        assertEquals(1, navi.requests.size)
        assertEquals(listOf(WaypointSlot.FIRST), navi.removed)
        assertEquals(listOf("차수아님이 오늘은 안 타요"), notices)
    }

    @Test
    fun `탈 지점이 바뀌면 그 지점으로 경유지를 바꾸고 바뀌기 전 지점을 남긴다`() = runTest {
        val (carpool, navi, apply) = setUp()
        carpool.start()
        val before = carpool.todayTrip.value!!.passengers.first { it.id == "p-1" }.pickup

        apply(CrewLiveEvent.RiderPlaceChanged("p-1", "차수아", Place("판교역 4번 출구", "", GeoPoint(37.3955, 127.1120))))

        val passenger = carpool.todayTrip.value!!.passengers.first { it.id == "p-1" }
        assertEquals("판교역 4번 출구", passenger.pickup.name)
        assertEquals(before.address, passenger.pickup.address)
        assertEquals(before, passenger.previousPickup)
        assertEquals("판교역 4번 출구", navi.requests.last().waypoints.first().name)
    }

    @Test
    fun `출발 전 변경은 운행 정보만 바꾸고 내비에는 요청하지 않는다`() = runTest {
        val (carpool, navi, apply) = setUp()
        val requested = navi.requests.size

        apply(CrewLiveEvent.RiderAbsent("p-1", "차수아"))

        assertEquals(Attendance.ABSENT, carpool.todayTrip.value!!.passengers.first { it.id == "p-1" }.attendance)
        assertEquals(requested, navi.requests.size)
    }

    @Test
    fun `지각·나와 있음은 알림만 띄우고, 오늘 운행에 없는 사람도 알린다`() = runTest {
        val (carpool, navi, apply) = setUp()
        carpool.start()
        val requested = navi.requests.size
        val before = carpool.todayTrip.value

        apply(CrewLiveEvent.RiderDelayed("p-1", "차수아", 5))
        apply(CrewLiveEvent.RiderHere(null, "웹가입"))
        apply(CrewLiveEvent.RiderAbsent(null, "웹가입"))

        assertEquals(listOf("차수아님이 5분 늦어요", "웹가입님이 나와 있어요", "웹가입님이 오늘은 안 타요"), notices)
        assertEquals(before, carpool.todayTrip.value)
        assertEquals(requested, navi.requests.size)
    }

    @Test
    fun `내비 경유지의 남은 시간·거리를 동승자와 짝짓는다`() {
        val trip = TestTrips.commute()
        val waiting = trip.pickupsInOrder
        val guidance = GuidanceState(
            waypoints = listOf(
                WaypointProgress(waiting[0].pickup.name, remainDistanceMeters = 250, remainTimeSeconds = 60),
                WaypointProgress("이름 다른 경유지", remainDistanceMeters = 5_000, remainTimeSeconds = 600),
            ),
        )
        val remains = riderRemains(trip, guidance)
        assertEquals(60 to 250, remains[waiting[0].id])
        assertEquals(600 to 5_000, remains[waiting[1].id])
        assertNull(riderRemains(trip, GuidanceState())[waiting[0].id])
        assertTrue(remains.size == 2)
    }
}
