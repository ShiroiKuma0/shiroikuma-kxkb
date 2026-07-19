package com.urik.keyboard.service.voice

import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.settings.KeyboardSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * The voice-language resolution matrix: dictation follows the layout language; the long-press flip
 * switches to the pair's other language (English, or Czech when the primary already is English);
 * GNU counts as English; auto-detect overrides everything.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceInputControllerTest {
    private val languageManager: LanguageManager = mock()
    private lateinit var controller: VoiceInputController

    @Before
    fun setUp() {
        controller = VoiceInputController(ApplicationProvider.getApplicationContext(), languageManager)
    }

    private fun layoutLanguage(code: String) {
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow(code))
    }

    @Test
    fun `dictation follows the layout language`() {
        for (code in listOf("cs", "ru", "ja", "en")) {
            layoutLanguage(code)
            assertEquals(code, controller.resolveLanguage(KeyboardSettings()))
        }
    }

    @Test
    fun `flip switches a non-English layout to English`() {
        val flipped = KeyboardSettings(voiceUseAlternate = true)
        for (code in listOf("cs", "ru", "ja")) {
            layoutLanguage(code)
            assertEquals("en", controller.resolveLanguage(flipped))
        }
    }

    @Test
    fun `flip switches an English layout to Czech`() {
        layoutLanguage("en")
        assertEquals("cs", controller.resolveLanguage(KeyboardSettings(voiceUseAlternate = true)))
    }

    @Test
    fun `gnu counts as English, flipping to Czech`() {
        layoutLanguage("gnu")
        assertEquals("en", controller.resolveLanguage(KeyboardSettings()))
        assertEquals("cs", controller.resolveLanguage(KeyboardSettings(voiceUseAlternate = true)))
    }

    @Test
    fun `hyphenated gnu variants resolve like gnu`() {
        layoutLanguage("gnu-xl")
        assertEquals("en", controller.resolveLanguage(KeyboardSettings()))
    }

    @Test
    fun `auto-detect overrides everything`() {
        layoutLanguage("cs")
        assertEquals(
            "auto",
            controller.resolveLanguage(KeyboardSettings(voiceAutoDetect = true, voiceUseAlternate = true))
        )
    }
}
