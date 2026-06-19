@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.data

import android.content.Context
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.CacheMemoryManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests KeyboardRepository layout resolution using real assets.
 *
 * Uses Robolectric to access actual layout files from src/main/assets/layouts/,
 * verifying that locale -> file -> parsed layout resolves correctly with no mocked I/O.
 *
 * The shipped roster is now cs/en/gnu/ja/ru only (per-language layout families, re-imported from the
 * futokxkb v2 design data); the old single-file-per-language layouts and the ar/bg/el/fa/uk families are
 * gone by design, so the tests that asserted on them were removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KeyboardRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: KeyboardRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()
        val cacheMemoryManager = CacheMemoryManager(context)
        val settingsFlow = MutableStateFlow(KeyboardSettings())
        val settingsRepository = mock<SettingsRepository>()
        whenever(settingsRepository.settings).thenReturn(settingsFlow)
        repository = KeyboardRepository(context, cacheMemoryManager, settingsRepository)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private suspend fun loadLetters(lang: String) =
        repository.getLayoutForMode(KeyboardMode.LETTERS, Locale.forLanguageTag(lang))

    // ── Russian ──────────────────────────────────────────────────────────────

    @Test
    fun `Russian layout resolves to Cyrl script`() = runTest {
        val result = loadLetters("ru")
        assertTrue("ru layout must load without error", result.isSuccess)
        assertEquals("Cyrl", result.getOrNull()?.script)
    }

    @Test
    fun `Russian layout letter keys are all Cyrillic`() = runTest {
        // The current Russian roster (registry default ru_6r8c) is a compass/cluster board: its letters are
        // FlickKey centres, not flat Character keys. Same intent — every single-character LETTER face is
        // Cyrillic — over the FlickKey letter keys (digits, punctuation and chord labels like "Ctrl" are
        // non-LETTER or multi-character, so they're excluded).
        val letterFaces = loadLetters("ru").getOrNull()!!.rows.flatten()
            .filterIsInstance<KeyboardKey.FlickKey>()
            .filter { it.type == KeyboardKey.KeyType.LETTER }
            .map { it.center }
            .filter { it.length == 1 && it.first().isLetter() }
        assertTrue("Russian layout must have letter keys", letterFaces.isNotEmpty())
        letterFaces.forEach { face ->
            assertTrue(
                "Letter face '$face' contains non-Cyrillic characters",
                face.all { it in 'Ѐ'..'ӿ' }
            )
        }
    }

    // Removed-language tests deleted by design (roster is cs/en/gnu/ja/ru only):
    //   Ukrainian (uk), Arabic (ar), Farsi (fa), Greek (el), Bulgarian (bg) and the BDS alt-layout — those
    //   layouts no longer ship.

    // ── GNU compass ────────────────────────────────────────────────────────────

    @Test
    fun `GNU layout loads all three modes as flick keys`() = runTest {
        val letters = repository.getLayoutForMode(KeyboardMode.LETTERS, Locale.forLanguageTag("gnu"))
        assertTrue("gnu letters must load", letters.isSuccess)
        assertEquals("Latn", letters.getOrNull()?.script)
        val flicks = letters.getOrNull()!!.rows.flatten().filterIsInstance<KeyboardKey.FlickKey>()
        assertTrue("gnu must have many compass keys", flicks.size > 30)
        assertTrue(
            "gnu numbers (altPage) must load",
            repository.getLayoutForMode(KeyboardMode.NUMBERS, Locale.forLanguageTag("gnu")).isSuccess
        )
        assertTrue(
            "gnu symbols (altPage) must load",
            repository.getLayoutForMode(KeyboardMode.SYMBOLS, Locale.forLanguageTag("gnu")).isSuccess
        )
    }

    @Test
    fun `GNU layout parses centre action, layer and chord bindings`() = runTest {
        val flicks = repository.getLayoutForMode(KeyboardMode.LETTERS, Locale.forLanguageTag("gnu"))
            .getOrNull()!!.rows.flatten().filterIsInstance<KeyboardKey.FlickKey>()

        val esc = flicks.first { it.center == "Esc" }
        assertTrue("Esc centre is an escape action", esc.bindings["center"] is KeyboardKey.FlickBinding.Action)
        assertTrue("Esc up switches layer", esc.bindings["up"] is KeyboardKey.FlickBinding.Layer)

        val c = flicks.first { it.center == "c" }
        val down = c.bindings["down"]
        assertTrue("c down is a chord", down is KeyboardKey.FlickBinding.Chord)
        assertEquals("C-c", (down as KeyboardKey.FlickBinding.Chord).spec)
        assertEquals("č", c.right)
    }

    // ── Japanese ──────────────────────────────────────────────────────────────
    //
    // `ja` resolves to the registry default `ja_gojuon`, a compass/cluster grid board (not the old flick
    // 12-key). The kana flick-variant tests (あ/や up/down/left/right), the per-key kana action keys
    // (DAKUTEN/HANDAKUTEN/SMALL_KANA/EMOJI/NEXT_CANDIDATE/COMMIT_CANDIDATE), the JP symbols-punctuation
    // row and the symbols_secondary mode no longer exist in any ja_* layout, so those assertions were
    // dropped. LANGUAGE_SWITCH still ships on the `ja_ketai` (携帯/flick) layout and is verified there.

    @Test
    fun `Japanese layout resolves to Hira script`() = runTest {
        val result = loadLetters("ja")
        assertTrue("ja layout must load without error", result.isSuccess)
        assertEquals("Hira", result.getOrNull()?.script)
    }

    @Test
    fun `Japanese layout letters mode contains FlickKey instances`() = runTest {
        val layout = loadLetters("ja").getOrNull()!!
        val flickKeys = layout.rows.flatten().filterIsInstance<KeyboardKey.FlickKey>()
        assertTrue("Japanese layout must have FlickKey instances", flickKeys.isNotEmpty())
    }

    @Test
    fun `Japanese ja_ketai layout contains LANGUAGE_SWITCH action key`() = runTest {
        val layout = repository.loadLayoutById("ja_ketai", KeyboardMode.LETTERS)
        assertNotNull("ja_ketai layout must load", layout)
        val languageSwitchKey = layout!!.rows.flatten()
            .filterIsInstance<KeyboardKey.Action>()
            .find { it.action == KeyboardKey.ActionType.LANGUAGE_SWITCH }
        assertNotNull("LANGUAGE_SWITCH action key must exist in the ja_ketai layout", languageSwitchKey)
    }
}
