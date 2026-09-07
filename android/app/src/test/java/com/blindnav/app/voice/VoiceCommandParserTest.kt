package com.blindnav.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    @Test
    fun testLocationInquiryCommands() {
        assertEquals(VoiceIntent.LocationInquiry, VoiceCommandParser.parse("where am i"))
        assertEquals(VoiceIntent.LocationInquiry, VoiceCommandParser.parse("What is my current location?"))
        assertEquals(VoiceIntent.LocationInquiry, VoiceCommandParser.parse("tell me my address"))
    }

    @Test
    fun testEmergencyAndCancelCommands() {
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("Emergency SOS!"))
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("danger help me"))

        assertEquals(VoiceIntent.CancelEmergency, VoiceCommandParser.parse("cancel"))
        assertEquals(VoiceIntent.CancelEmergency, VoiceCommandParser.parse("I am ok, abort"))
        assertEquals(VoiceIntent.CancelEmergency, VoiceCommandParser.parse("false alarm"))
    }

    @Test
    fun testRepeatAndHistoryCommands() {
        assertEquals(VoiceIntent.RepeatLast, VoiceCommandParser.parse("repeat that"))
        assertEquals(VoiceIntent.RepeatLast, VoiceCommandParser.parse("what was that"))
        assertEquals(VoiceIntent.RepeatLast, VoiceCommandParser.parse("say again"))

        assertEquals(VoiceIntent.HistoryInquiry, VoiceCommandParser.parse("show detection history"))
        assertEquals(VoiceIntent.HistoryInquiry, VoiceCommandParser.parse("recent obstacles"))
    }

    @Test
    fun testTorchAndLightingCommands() {
        val torchOn = VoiceCommandParser.parse("Turn on light please")
        assertTrue(torchOn is VoiceIntent.ToggleTorch && torchOn.enable)

        val torchOnDirect = VoiceCommandParser.parse("torch on")
        assertTrue(torchOnDirect is VoiceIntent.ToggleTorch && torchOnDirect.enable)

        val torchOff = VoiceCommandParser.parse("flash off")
        assertTrue(torchOff is VoiceIntent.ToggleTorch && !torchOff.enable)
    }

    @Test
    fun testDetectionToggleCommands() {
        val pauseIntent = VoiceCommandParser.parse("pause detection")
        assertTrue(pauseIntent is VoiceIntent.ToggleDetection && pauseIntent.pause)

        val resumeIntent = VoiceCommandParser.parse("resume scanning")
        assertTrue(resumeIntent is VoiceIntent.ToggleDetection && !resumeIntent.pause)
    }

    @Test
    fun testSpeedAdjustmentCommands() {
        val faster = VoiceCommandParser.parse("speak faster")
        assertTrue(faster is VoiceIntent.AdjustSpeed && faster.faster)

        val slower = VoiceCommandParser.parse("slow down")
        assertTrue(slower is VoiceIntent.AdjustSpeed && !slower.faster)
    }

    @Test
    fun testSetEmergencyContact() {
        val intent = VoiceCommandParser.parse("set emergency contact to 555-019-2834")
        assertTrue(intent is VoiceIntent.SetEmergencyContact)
        assertEquals("5550192834", (intent as VoiceIntent.SetEmergencyContact).phoneNumber)
    }

    @Test
    fun testHelpAndStatus() {
        assertEquals(VoiceIntent.Help, VoiceCommandParser.parse("help me with commands"))
        assertEquals(VoiceIntent.StatusInquiry, VoiceCommandParser.parse("how are you doing / status"))
    }

    @Test
    fun testNavigateToCommands() {
        val nav1 = VoiceCommandParser.parse("navigate to central park")
        assertTrue(nav1 is VoiceIntent.NavigateTo)
        assertEquals("central park", (nav1 as VoiceIntent.NavigateTo).destination)

        val nav2 = VoiceCommandParser.parse("take me to city hall")
        assertTrue(nav2 is VoiceIntent.NavigateTo)
        assertEquals("city hall", (nav2 as VoiceIntent.NavigateTo).destination)

        val nav3 = VoiceCommandParser.parse("directions to pharmacy")
        assertTrue(nav3 is VoiceIntent.NavigateTo)
        assertEquals("pharmacy", (nav3 as VoiceIntent.NavigateTo).destination)
    }

    @Test
    fun testUnknownCommand() {
        val unknown = VoiceCommandParser.parse("play music on spotify")
        assertTrue(unknown is VoiceIntent.Unknown)
    }
}
