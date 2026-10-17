package com.blindnav.app.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguagePreferencesTest {

    @After
    fun tearDown() {
        // Reset to default English after tests
        LocaleManager.setLanguage(SupportedLanguage.ENGLISH)
        LocaleManager.onLanguageChanged = null
    }

    @Test
    fun testDefaultFallbackOnNullOrBlank() {
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode(null))
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode(""))
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode("   "))
    }

    @Test
    fun testFallbackOnUnsupportedLanguageCode() {
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode("fr-FR"))
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode("es-ES"))
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode("unknown"))
    }

    @Test
    fun testParseEnglishCodes() {
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode("en"))
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode("en-US"))
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode("en-IN"))
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromCode("EN_US"))
    }

    @Test
    fun testParseHindiCodes() {
        assertEquals(SupportedLanguage.HINDI, SupportedLanguage.fromCode("hi"))
        assertEquals(SupportedLanguage.HINDI, SupportedLanguage.fromCode("hi-IN"))
        assertEquals(SupportedLanguage.HINDI, SupportedLanguage.fromCode("HI-in"))
    }

    @Test
    fun testParseKannadaCodes() {
        assertEquals(SupportedLanguage.KANNADA, SupportedLanguage.fromCode("kn"))
        assertEquals(SupportedLanguage.KANNADA, SupportedLanguage.fromCode("kn-IN"))
        assertEquals(SupportedLanguage.KANNADA, SupportedLanguage.fromCode("KN-in"))
    }

    @Test
    fun testLanguageProperties() {
        assertEquals("en-IN", SupportedLanguage.ENGLISH.code)
        assertEquals("en", SupportedLanguage.ENGLISH.locale.language)
        assertEquals("IN", SupportedLanguage.ENGLISH.locale.country)

        assertEquals("hi-IN", SupportedLanguage.HINDI.code)
        assertEquals("hi", SupportedLanguage.HINDI.locale.language)
        assertEquals("IN", SupportedLanguage.HINDI.locale.country)

        assertEquals("kn-IN", SupportedLanguage.KANNADA.code)
        assertEquals("kn", SupportedLanguage.KANNADA.locale.language)
        assertEquals("IN", SupportedLanguage.KANNADA.locale.country)
    }

    @Test
    fun testLocaleManagerLanguageSwitchAndCallback() {
        var notifiedLang: SupportedLanguage? = null
        LocaleManager.onLanguageChanged = { lang ->
            notifiedLang = lang
        }

        LocaleManager.setLanguage(SupportedLanguage.HINDI)
        assertEquals(SupportedLanguage.HINDI, LocaleManager.currentLanguage)
        assertEquals("hi", LocaleManager.currentLocale.language)
        assertEquals(SupportedLanguage.HINDI, notifiedLang)

        // Switching to same language should not notify again
        notifiedLang = null
        LocaleManager.setLanguage(SupportedLanguage.HINDI)
        assertEquals(null, notifiedLang)

        // Switching to Kannada
        LocaleManager.setLanguageByCode("kn-IN")
        assertEquals(SupportedLanguage.KANNADA, LocaleManager.currentLanguage)
        assertEquals("kn", LocaleManager.currentLocale.language)
        assertEquals(SupportedLanguage.KANNADA, notifiedLang)

        // Switching back to English
        LocaleManager.setLanguageByCode("en-IN")
        assertEquals(SupportedLanguage.ENGLISH, LocaleManager.currentLanguage)
        assertEquals("en", LocaleManager.currentLocale.language)
        assertEquals(SupportedLanguage.ENGLISH, notifiedLang)
    }
}
