package com.paircar

import com.paircar.core.model.GeoPoint
import com.paircar.data.remote.CachingRoutePreviewRepository
import com.paircar.domain.model.RouteComparison
import com.paircar.domain.repository.RoutePreview
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.usecase.RouteComparer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteComparerTest {

    private val trip = TestTrips.commute()

    @Test
    fun `우회 거리는 경유 경로에서 직행 경로를 뺀 값`() = runTest {
        val via = RouteComparer(FakeRoutes()).via(trip, listOf(trip.pickupsInOrder.first().pickup))
        assertEquals(68.3, via.route.distanceMeters / 1000.0, 1e-9)
        assertEquals(8.9, via.detourKm!!, 1e-9)
        assertEquals(540, via.comparison!!.extraSeconds)
    }

    @Test
    fun `직행 길찾기가 실패해 직선 추정이면 비교하지 않는다`() = runTest {
        val comparer = RouteComparer(FakeRoutes(directFails = true))
        assertNull(comparer.detourKm(trip, trip.pickupsInOrder.first().pickup))
    }

    @Test
    fun `픽업이 없으면 직행을 다시 묻지 않고 비교도 없다`() = runTest {
        val routes = CountingRoutes(FakeRoutes())
        val via = RouteComparer(routes).via(trip, emptyList())
        assertNull(via.comparison)
        assertEquals(1, routes.calls)
    }

    @Test
    fun `화면 단위로 반올림한다 - 0_1km, 분`() {
        val comparison = RouteComparison.of(
            via = RoutePreview(emptyList(), distanceMeters = 68_349, durationSeconds = 3_689, isApproximate = false),
            direct = RoutePreview(emptyList(), distanceMeters = 59_351, durationSeconds = 3_101, isApproximate = false),
        )!!.rounded()
        assertEquals(RouteComparison(directKm = 59.4, directSeconds = 3_120, carpoolKm = 68.3, carpoolSeconds = 3_660), comparison)
    }

    @Test
    fun `같은 정류장 경로는 한 번만 묻고, 직선 추정은 저장하지 않는다`() = runTest {
        val inner = CountingRoutes(FakeRoutes(directFails = true))
        val cached = CachingRoutePreviewRepository(inner)
        val via = trip.planStops()
        cached.preview(via)
        cached.preview(via)
        assertEquals(1, inner.calls)

        cached.preview(trip.directStops())
        cached.preview(trip.directStops())
        assertEquals(3, inner.calls)
    }

    private class CountingRoutes(private val inner: RoutePreviewRepository) : RoutePreviewRepository {
        var calls = 0
        override suspend fun preview(stops: List<GeoPoint>): RoutePreview {
            calls++
            return inner.preview(stops)
        }
    }
}
