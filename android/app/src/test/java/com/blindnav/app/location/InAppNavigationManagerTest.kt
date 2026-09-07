package com.blindnav.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppNavigationManagerTest {

    @Test
    fun testEstimatedWalkingMinutesCalculation() {
        val manager = InAppNavigationManager()
        assertEquals(0, manager.getEstimatedWalkingMinutes(0f))
        assertEquals(1, manager.getEstimatedWalkingMinutes(80f))
        assertEquals(3, manager.getEstimatedWalkingMinutes(240f))
        assertEquals(10, manager.getEstimatedWalkingMinutes(800f))
    }

    @Test
    fun testFormatDistance() {
        val manager = InAppNavigationManager()
        assertEquals("0 m", manager.formatDistance(0f))
        assertEquals("450 m", manager.formatDistance(450f))
        assertEquals("999 m", manager.formatDistance(999f))
        assertEquals("1.0 km", manager.formatDistance(1000f))
        assertEquals("1.5 km", manager.formatDistance(1500f))
    }

    @Test
    fun testInitialState() {
        val manager = InAppNavigationManager()
        assertEquals(false, manager.isNavigating)
        assertTrue(manager.currentRoute.isEmpty())
        assertEquals("", manager.destinationName)
    }
}
