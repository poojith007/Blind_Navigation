package com.blindnav.app.voice

import com.blindnav.app.i18n.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserMultilingualTest {

    @Test
    fun testLanguageChangeCommands_English() {
        val hindiReq = VoiceCommandParser.parse("change language to hindi")
        assertTrue(hindiReq is VoiceIntent.ChangeLanguage && hindiReq.targetLanguage == SupportedLanguage.HINDI)

        val kannadaReq = VoiceCommandParser.parse("switch to kannada please")
        assertTrue(kannadaReq is VoiceIntent.ChangeLanguage && kannadaReq.targetLanguage == SupportedLanguage.KANNADA)

        val engReq = VoiceCommandParser.parse("speak in english")
        assertTrue(engReq is VoiceIntent.ChangeLanguage && engReq.targetLanguage == SupportedLanguage.ENGLISH)
    }

    @Test
    fun testLanguageChangeCommands_Hindi() {
        val hindiCmd1 = VoiceCommandParser.parse("हिंदी में बोलो")
        assertTrue(hindiCmd1 is VoiceIntent.ChangeLanguage && hindiCmd1.targetLanguage == SupportedLanguage.HINDI)

        val hindiCmd2 = VoiceCommandParser.parse("hindi bhasha")
        assertTrue(hindiCmd2 is VoiceIntent.ChangeLanguage && hindiCmd2.targetLanguage == SupportedLanguage.HINDI)

        val knFromHi = VoiceCommandParser.parse("कन्नड़ में बात करो")
        assertTrue(knFromHi is VoiceIntent.ChangeLanguage && knFromHi.targetLanguage == SupportedLanguage.KANNADA)

        val enFromHi = VoiceCommandParser.parse("अंग्रेजी में बोलो")
        assertTrue(enFromHi is VoiceIntent.ChangeLanguage && enFromHi.targetLanguage == SupportedLanguage.ENGLISH)
    }

    @Test
    fun testLanguageChangeCommands_Kannada() {
        val knCmd1 = VoiceCommandParser.parse("ಕನ್ನಡದಲ್ಲಿ ಮಾತನಾಡಿ")
        assertTrue(knCmd1 is VoiceIntent.ChangeLanguage && knCmd1.targetLanguage == SupportedLanguage.KANNADA)

        val knCmd2 = VoiceCommandParser.parse("kannada dalli mathanadi")
        assertTrue(knCmd2 is VoiceIntent.ChangeLanguage && knCmd2.targetLanguage == SupportedLanguage.KANNADA)

        val hiFromKn = VoiceCommandParser.parse("ಹಿಂದಿಯಲ್ಲಿ ಮಾತನಾಡಿ")
        assertTrue(hiFromKn is VoiceIntent.ChangeLanguage && hiFromKn.targetLanguage == SupportedLanguage.HINDI)

        val enFromKn = VoiceCommandParser.parse("ಇಂಗ್ಲಿಷ್‌ನಲ್ಲಿ ಮಾತನಾಡಿ")
        assertTrue(enFromKn is VoiceIntent.ChangeLanguage && enFromKn.targetLanguage == SupportedLanguage.ENGLISH)
    }

    @Test
    fun testNavigationCommands_Hindi() {
        val nav1 = VoiceCommandParser.parse("एमजी रोड जाओ")
        assertTrue(nav1 is VoiceIntent.NavigateTo)
        assertEquals("एमजी रोड", (nav1 as VoiceIntent.NavigateTo).destination)

        val nav2 = VoiceCommandParser.parse("अस्पताल का रास्ता")
        assertTrue(nav2 is VoiceIntent.NavigateTo)
        assertEquals("अस्पताल", (nav2 as VoiceIntent.NavigateTo).destination)

        val nav3 = VoiceCommandParser.parse("मेट्रो स्टेशन ले चलो")
        assertTrue(nav3 is VoiceIntent.NavigateTo)
        assertEquals("मेट्रो स्टेशन", (nav3 as VoiceIntent.NavigateTo).destination)

        val nav4 = VoiceCommandParser.parse("railway station jao")
        assertTrue(nav4 is VoiceIntent.NavigateTo)
        assertEquals("railway station", (nav4 as VoiceIntent.NavigateTo).destination)
    }

    @Test
    fun testNavigationCommands_Kannada() {
        val nav1 = VoiceCommandParser.parse("ಬಸ್ ನಿಲ್ದಾಣಕ್ಕೆ ಹೋಗು")
        assertTrue(nav1 is VoiceIntent.NavigateTo)
        assertEquals("ಬಸ್ ನಿಲ್ದಾಣ", (nav1 as VoiceIntent.NavigateTo).destination)

        val nav2 = VoiceCommandParser.parse("ಆಸ್ಪತ್ರೆಗೆ ದಾರಿ")
        assertTrue(nav2 is VoiceIntent.NavigateTo)
        assertEquals("ಆಸ್ಪತ್ರೆ", (nav2 as VoiceIntent.NavigateTo).destination)

        val nav3 = VoiceCommandParser.parse("ರೈಲ್ವೆ ನಿಲ್ದಾಣಕ್ಕೆ ಕರೆದುಕೊಂಡು ಹೋಗು")
        assertTrue(nav3 is VoiceIntent.NavigateTo)
        assertEquals("ರೈಲ್ವೆ ನಿಲ್ದಾಣ", (nav3 as VoiceIntent.NavigateTo).destination)

        val nav4 = VoiceCommandParser.parse("park ge dari")
        assertTrue(nav4 is VoiceIntent.NavigateTo)
        assertEquals("park", (nav4 as VoiceIntent.NavigateTo).destination)
    }

    @Test
    fun testEmergencySos_Multilingual() {
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("मदद करो"))
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("बचाओ"))
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("आपतकालीन"))
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("madad karo"))
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("bachao"))

        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("ಸಹಾಯ ಮಾಡಿ"))
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("ಕಾಪಾಡಿ"))
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("ತುರ್ತು ಪರಿಸ್ಥಿತಿ"))
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("sahaya madi"))
        assertEquals(VoiceIntent.EmergencySos, VoiceCommandParser.parse("kapadi"))
    }

    @Test
    fun testCancelEmergency_Multilingual() {
        assertEquals(VoiceIntent.CancelEmergency, VoiceCommandParser.parse("रद्द करो"))
        assertEquals(VoiceIntent.CancelEmergency, VoiceCommandParser.parse("मैं ठीक हूँ"))
        assertEquals(VoiceIntent.CancelEmergency, VoiceCommandParser.parse("radd karo"))

        assertEquals(VoiceIntent.CancelEmergency, VoiceCommandParser.parse("ರದ್ದು ಮಾಡಿ"))
        assertEquals(VoiceIntent.CancelEmergency, VoiceCommandParser.parse("ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ"))
        assertEquals(VoiceIntent.CancelEmergency, VoiceCommandParser.parse("raddu madi"))
    }

    @Test
    fun testStopAndSafety_Multilingual() {
        // Immediate safety halt commands
        assertEquals(VoiceIntent.StopSafety, VoiceCommandParser.parse("रुको"))
        assertEquals(VoiceIntent.StopSafety, VoiceCommandParser.parse("रुकिए"))
        assertEquals(VoiceIntent.StopSafety, VoiceCommandParser.parse("ruko"))
        assertEquals(VoiceIntent.StopSafety, VoiceCommandParser.parse("ನಿಲ್ಲು"))
        assertEquals(VoiceIntent.StopSafety, VoiceCommandParser.parse("ನಿಲ್ಲಿಸಿ"))
        assertEquals(VoiceIntent.StopSafety, VoiceCommandParser.parse("nillisi"))

        // Navigation route stopping commands
        assertEquals(VoiceIntent.StopNavigation, VoiceCommandParser.parse("नेविगेशन बंद करो"))
        assertEquals(VoiceIntent.StopNavigation, VoiceCommandParser.parse("ಮಾರ್ಗದರ್ಶನ ನಿಲ್ಲಿಸಿ"))
        assertEquals(VoiceIntent.StopNavigation, VoiceCommandParser.parse("ನ್ಯಾವಿಗೇಷನ್ ನಿಲ್ಲಿಸಿ"))
    }

    @Test
    fun testLocationInquiry_Multilingual() {
        assertEquals(VoiceIntent.LocationInquiry, VoiceCommandParser.parse("मैं कहाँ हूँ"))
        assertEquals(VoiceIntent.LocationInquiry, VoiceCommandParser.parse("मेरी लोकेशन बताओ"))
        assertEquals(VoiceIntent.LocationInquiry, VoiceCommandParser.parse("kahan hun main"))

        assertEquals(VoiceIntent.LocationInquiry, VoiceCommandParser.parse("ನಾನು ಎಲ್ಲಿದ್ದೇನೆ"))
        assertEquals(VoiceIntent.LocationInquiry, VoiceCommandParser.parse("ನನ್ನ ಸ್ಥಳ ಎಲ್ಲಿದೆ"))
        assertEquals(VoiceIntent.LocationInquiry, VoiceCommandParser.parse("yellidene"))
    }

    @Test
    fun testObstacleInquiry_Multilingual() {
        assertEquals(VoiceIntent.ObstacleInquiry, VoiceCommandParser.parse("सामने क्या है"))
        assertEquals(VoiceIntent.ObstacleInquiry, VoiceCommandParser.parse("रुकावट क्या है"))
        assertEquals(VoiceIntent.ObstacleInquiry, VoiceCommandParser.parse("kya hai samne"))

        assertEquals(VoiceIntent.ObstacleInquiry, VoiceCommandParser.parse("ಮುಂದೆ ಏನಿದೆ"))
        assertEquals(VoiceIntent.ObstacleInquiry, VoiceCommandParser.parse("ಅಡಚಣೆ ಏನಿದೆ"))
        assertEquals(VoiceIntent.ObstacleInquiry, VoiceCommandParser.parse("yenu ide munde"))
    }

    @Test
    fun testRepeatLast_Multilingual() {
        assertEquals(VoiceIntent.RepeatLast, VoiceCommandParser.parse("फिर से बोलो"))
        assertEquals(VoiceIntent.RepeatLast, VoiceCommandParser.parse("दोहराएं"))
        assertEquals(VoiceIntent.RepeatLast, VoiceCommandParser.parse("phir se bolo"))

        assertEquals(VoiceIntent.RepeatLast, VoiceCommandParser.parse("ಮತ್ತೊಮ್ಮೆ ಹೇಳಿ"))
        assertEquals(VoiceIntent.RepeatLast, VoiceCommandParser.parse("ಪುನರಾವರ್ತಿಸಿ"))
        assertEquals(VoiceIntent.RepeatLast, VoiceCommandParser.parse("innomme heli"))
    }

    @Test
    fun testCallGuardian_Multilingual() {
        assertEquals(VoiceIntent.CallGuardian, VoiceCommandParser.parse("अभिभावक को फोन करो"))
        assertEquals(VoiceIntent.CallGuardian, VoiceCommandParser.parse("गार्डियन को कॉल करो"))
        assertEquals(VoiceIntent.CallGuardian, VoiceCommandParser.parse("guardian ko call karo"))

        assertEquals(VoiceIntent.CallGuardian, VoiceCommandParser.parse("ಪೋಷಕರಿಗೆ ಕರೆ ಮಾಡಿ"))
        assertEquals(VoiceIntent.CallGuardian, VoiceCommandParser.parse("ರಕ್ಷಕರಿಗೆ ಕರೆ ಮಾಡಿ"))
        assertEquals(VoiceIntent.CallGuardian, VoiceCommandParser.parse("guardian ge call madi"))
    }

    @Test
    fun testStatusAndHelp_Multilingual() {
        assertEquals(VoiceIntent.StatusInquiry, VoiceCommandParser.parse("स्थिति बताओ"))
        assertEquals(VoiceIntent.StatusInquiry, VoiceCommandParser.parse("सब ठीक है"))
        assertEquals(VoiceIntent.StatusInquiry, VoiceCommandParser.parse("ಸ್ಥಿತಿ ಹೇಳಿ"))

        assertEquals(VoiceIntent.Help, VoiceCommandParser.parse("मदद"))
        assertEquals(VoiceIntent.Help, VoiceCommandParser.parse("सहायता"))
        assertEquals(VoiceIntent.Help, VoiceCommandParser.parse("ಸಹಾಯ"))
        assertEquals(VoiceIntent.Help, VoiceCommandParser.parse("ಮಾರ್ಗದರ್ಶನ ಬೇಕು"))
    }
}
