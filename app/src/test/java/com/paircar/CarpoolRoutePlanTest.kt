package com.paircar

import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.usecase.buildCarpoolRoutePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarpoolRoutePlanTest {

    private val trip = TestTrips.commute()

    @Test
    fun `accepted pickups become waypoints in pickup-time order`() {
        val plan = buildCarpoolRoutePlan(trip)

        assertEquals(listOf("판교역 1번 출구", "양재시민의숲역 1번 출구"), plan.request.waypoints.map { it.name })
        assertEquals(trip.destination, plan.request.destination)
        assertTrue(plan.deferredPickups.isEmpty())
    }

    @Test
    fun `more than two pending pickups are deferred`() {
        val allAccepted = trip.copy(passengers = trip.passengers.map { it.copy(matchStatus = MatchStatus.ACCEPTED) })

        val plan = buildCarpoolRoutePlan(allAccepted)

        assertEquals(2, plan.request.waypoints.size)
        // 시간순: 서현(07:47) → 판교(07:52) | 청계산(08:15) → 양재시민의숲(08:25)
        assertEquals(listOf("서현역 4번 출구", "판교역 1번 출구"), plan.request.waypoints.map { it.name })
        assertEquals(listOf("최도윤", "이준호"), plan.deferredPickups.map { it.name })
    }

    @Test
    fun `boarded passengers are not routed again`() {
        val firstBoarded = trip.copy(
            passengers = trip.passengers.map {
                if (it.id == "p-1") it.copy(boardingStatus = BoardingStatus.BOARDED) else it
            },
        )

        val plan = buildCarpoolRoutePlan(firstBoarded)

        assertEquals(listOf("양재시민의숲역 1번 출구"), plan.request.waypoints.map { it.name })
    }
}
