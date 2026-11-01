package com.paircar

import com.paircar.domain.model.CarpoolAction
import com.paircar.domain.model.DrivingPolicy
import com.paircar.domain.model.DrivingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingPolicyTest {

    private val driving = DrivingState(restricted = true, source = DrivingState.Source.CAR_UX_RESTRICTIONS)
    private val parked = DrivingState(restricted = false, source = DrivingState.Source.CAR_UX_RESTRICTIONS)

    @Test
    fun `everything is allowed while parked`() {
        assertTrue(CarpoolAction.entries.all { DrivingPolicy.isAllowed(it, parked) })
    }

    @Test
    fun `only glanceable single-tap actions are allowed while driving`() {
        val allowed = CarpoolAction.entries.filter { DrivingPolicy.isAllowed(it, driving) }.toSet()

        assertEquals(
            setOf(CarpoolAction.VIEW_DRIVING_SUMMARY, CarpoolAction.OPEN_NAVIGATION, CarpoolAction.START_GUIDANCE),
            allowed,
        )
    }
}
