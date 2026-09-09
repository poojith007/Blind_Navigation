package com.blindnav.app.location

import android.location.Location
import com.blindnav.app.db.OfflineAreaEntity
import com.blindnav.app.network.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsConfidenceTest {

    private class TestLocation(
        private val hasAcc: Boolean = false,
        private val acc: Float = 0f
    ) : Location("test") {
        override fun hasAccuracy(): Boolean = hasAcc
        override fun getAccuracy(): Float = acc
    }

    @Test
    fun testNullLocationYieldsUnavailable() {
        val confidence = GpsConfidence.evaluate(null)
        assertEquals(GpsConfidence.UNAVAILABLE, confidence)
        assertEquals("GPS UNAVAILABLE", confidence.label)
        assertEquals("🔴", confidence.displayIcon)
    }

    @Test
    fun testLocationWithoutAccuracyYieldsWeak() {
        val loc = TestLocation(hasAcc = false)
        val confidence = GpsConfidence.evaluate(loc)
        assertEquals(GpsConfidence.WEAK, confidence)
        assertEquals("GPS WEAK", confidence.label)
        assertEquals("🟡", confidence.displayIcon)
    }

    @Test
    fun testHighAccuracyYieldsGoodConfidence() {
        val loc = TestLocation(hasAcc = true, acc = 6.5f)
        val confidence = GpsConfidence.evaluate(loc)
        assertEquals(GpsConfidence.GOOD, confidence)
        assertEquals("GPS GOOD", confidence.label)
        assertEquals("🟢", confidence.displayIcon)
    }

    @Test
    fun testMarginalAccuracyYieldsWeakConfidence() {
        val loc = TestLocation(hasAcc = true, acc = 25.0f)
        val confidence = GpsConfidence.evaluate(loc)
        assertEquals(GpsConfidence.WEAK, confidence)
    }

    @Test
    fun testDegradedAccuracyYieldsUnavailableConfidence() {
        val loc = TestLocation(hasAcc = true, acc = 48.0f)
        val confidence = GpsConfidence.evaluate(loc)
        assertEquals(GpsConfidence.UNAVAILABLE, confidence)
    }

    @Test
    fun testConnectionStatusAttributes() {
        assertEquals("ONLINE", ConnectionStatus.ONLINE.label)
        assertEquals("🟢", ConnectionStatus.ONLINE.displayIcon)

        assertEquals("OFFLINE", ConnectionStatus.OFFLINE.label)
        assertEquals("🔴", ConnectionStatus.OFFLINE.displayIcon)

        assertEquals("SYNCING", ConnectionStatus.SYNCING.label)
        assertEquals("🔄", ConnectionStatus.SYNCING.displayIcon)

        assertEquals("DOWNLOADING", ConnectionStatus.DOWNLOADING.label)
        assertEquals("⬇️", ConnectionStatus.DOWNLOADING.displayIcon)
    }

    @Test
    fun testOfflineAreaFormattedSize() {
        val smallArea = OfflineAreaEntity(
            areaName = "Test Micro Zone",
            coverageDescription = "Small block",
            storageBytes = 450 * 1024L
        )
        assertEquals("450 KB", smallArea.formattedSize)

        val largeArea = OfflineAreaEntity(
            areaName = "Bangalore Central",
            coverageDescription = "Urban Core",
            storageBytes = 14_500_000L
        )
        assertEquals("13.8 MB", largeArea.formattedSize)
    }
}
