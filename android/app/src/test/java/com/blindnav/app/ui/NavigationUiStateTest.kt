package com.blindnav.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationUiStateTest {

    @Test
    fun testAll14StatesPresent() {
        val states = NavigationUiState.values()
        assertEquals(14, states.size)

        val stateNames = states.map { it.stateName }.toSet()
        assertEquals(14, stateNames.size)

        // Verify key states
        assertTrue(states.contains(NavigationUiState.HOME))
        assertTrue(states.contains(NavigationUiState.DESTINATION_SEARCH))
        assertTrue(states.contains(NavigationUiState.ROUTE_PREVIEW))
        assertTrue(states.contains(NavigationUiState.NAV_PATH_CLEAR))
        assertTrue(states.contains(NavigationUiState.NAV_APPROACHING_TURN))
        assertTrue(states.contains(NavigationUiState.OBSTACLE_WARNING))
        assertTrue(states.contains(NavigationUiState.CRITICAL_STOP))
        assertTrue(states.contains(NavigationUiState.DESTINATION_REACHED))
        assertTrue(states.contains(NavigationUiState.PATH_UNCLEAR))
        assertTrue(states.contains(NavigationUiState.GPS_WEAK))
        assertTrue(states.contains(NavigationUiState.OFFLINE))
        assertTrue(states.contains(NavigationUiState.CAMERA_UNAVAILABLE))
        assertTrue(states.contains(NavigationUiState.GUARDIAN_EMERGENCY))
        assertTrue(states.contains(NavigationUiState.SETTINGS))
    }

    @Test
    fun testDualEncodingPresentsIconsAndText() {
        for (state in NavigationUiState.values()) {
            assertTrue("State ${state.name} must have a display icon", state.displayIcon.isNotBlank())
            assertTrue("State ${state.name} must have a non-empty headline", state.defaultHeadline.isNotBlank())
            assertTrue("State ${state.name} must have a human-readable name", state.stateName.isNotBlank())
        }
    }

    @Test
    fun testSafetyCriticalClassification() {
        assertTrue(NavigationUiState.CRITICAL_STOP.isSafetyCritical)
        assertTrue(NavigationUiState.OBSTACLE_WARNING.isSafetyCritical)
        assertTrue(NavigationUiState.PATH_UNCLEAR.isSafetyCritical)
        assertTrue(NavigationUiState.GUARDIAN_EMERGENCY.isSafetyCritical)
        assertTrue(NavigationUiState.CAMERA_UNAVAILABLE.isSafetyCritical)

        assertFalse(NavigationUiState.HOME.isSafetyCritical)
        assertFalse(NavigationUiState.NAV_PATH_CLEAR.isSafetyCritical)
        assertFalse(NavigationUiState.SETTINGS.isSafetyCritical)
    }

    @Test
    fun testIsNavigatingFlag() {
        assertTrue(NavigationUiState.NAV_PATH_CLEAR.isNavigating)
        assertTrue(NavigationUiState.NAV_APPROACHING_TURN.isNavigating)
        assertTrue(NavigationUiState.OBSTACLE_WARNING.isNavigating)
        assertTrue(NavigationUiState.CRITICAL_STOP.isNavigating)
        assertTrue(NavigationUiState.PATH_UNCLEAR.isNavigating)

        assertFalse(NavigationUiState.HOME.isNavigating)
        assertFalse(NavigationUiState.DESTINATION_SEARCH.isNavigating)
        assertFalse(NavigationUiState.ROUTE_PREVIEW.isNavigating)
        assertFalse(NavigationUiState.DESTINATION_REACHED.isNavigating)
        assertFalse(NavigationUiState.SETTINGS.isNavigating)
    }

    @Test
    fun testSafeFailurePerceptionRule() {
        // Safe failure state MUST have unclear headline and safety critical flag
        val unclearState = NavigationUiState.PATH_UNCLEAR
        assertTrue(unclearState.isSafetyCritical)
        assertTrue(unclearState.defaultHeadline.contains("PATH UNCLEAR"))
        assertFalse(unclearState.defaultHeadline.contains("PATH CLEAR"))
    }
}
