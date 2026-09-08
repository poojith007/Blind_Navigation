package com.blindnav.app.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencySosHandlerTest {

    @Test
    fun testFormatEmergencyMessageWithAddressAndMapsUrl() {
        val address = "Connaught Place, New Delhi, India"
        val mapsUrl = "https://maps.google.com/?q=28.6315,77.2167"

        val message = EmergencySosHandler.formatEmergencyMessage(
            location = null,
            address = address,
            mapsUrl = mapsUrl
        )

        assertEquals(
            "Emergency alert from Blind Navigation. I may need assistance. My current location: Connaught Place, New Delhi, India (https://maps.google.com/?q=28.6315,77.2167).",
            message
        )
        assertTrue(message.contains("Emergency alert from Blind Navigation."))
        assertTrue(message.contains(address))
        assertTrue(message.contains(mapsUrl))
    }

    @Test
    fun testFormatEmergencyMessageWithMapsUrlOnly() {
        val mapsUrl = "https://maps.google.com/?q=12.9716,77.5946"

        val message = EmergencySosHandler.formatEmergencyMessage(
            location = null,
            address = null,
            mapsUrl = mapsUrl
        )

        assertEquals(
            "Emergency alert from Blind Navigation. I may need assistance. My current location: https://maps.google.com/?q=12.9716,77.5946.",
            message
        )
        assertTrue(message.contains("Emergency alert from Blind Navigation."))
        assertTrue(message.contains(mapsUrl))
    }

    @Test
    fun testFormatEmergencyMessageWhenLocationUnavailable() {
        // Location is unavailable (GPS off or permission not granted)
        val message = EmergencySosHandler.formatEmergencyMessage(
            location = null,
            address = null,
            mapsUrl = null
        )

        assertEquals(
            "Emergency alert from Blind Navigation. I may need assistance. My current location could not be obtained.",
            message
        )
        // Must NEVER fabricate or include a false location or link
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("null"))
        assertFalse(message.contains("Lat:"))
    }
}
