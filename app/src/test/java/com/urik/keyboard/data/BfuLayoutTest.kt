package com.urik.keyboard.data

import android.content.Context
import android.os.UserManager
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.CacheMemoryManager
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * The before-first-unlock (Direct Boot) layout: which board the lock screen shows, and — the point of the
 * whole fallback chain — that a bad pick degrades instead of leaving the PIN field with no keyboard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BfuLayoutTest {
    private lateinit var context: Context
    private lateinit var repository: KeyboardRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // Before first unlock: credential-protected storage is locked, so the BFU path runs.
        Shadows.shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val settingsRepository = mock<SettingsRepository>()
        whenever(settingsRepository.settings).thenReturn(MutableStateFlow(KeyboardSettings()))
        repository = KeyboardRepository(context, CacheMemoryManager(context), settingsRepository)
    }

    private suspend fun bfuLayoutId(): String? =
        repository.getLayoutForMode(KeyboardMode.LETTERS, Locale.forLanguageTag("en")).getOrNull()?.id

    @Test
    fun `unset pick shows the GNU QWERTY 10c default`() = runTest {
        assertEquals("gnu_qwerty_5r10c", BfuLayoutPrefs.layoutId(context))
        assertEquals("gnu_qwerty_5r10c", bfuLayoutId())
    }

    @Test
    fun `a picked bundled layout is what the lock screen loads`() = runTest {
        BfuLayoutPrefs.setLayoutId(context, "gnu_5r15c")
        assertEquals("gnu_5r15c", BfuLayoutPrefs.layoutId(context))
        assertEquals("gnu_5r15c", bfuLayoutId())
    }

    @Test
    fun `a pick that no longer exists falls back to the default instead of failing`() = runTest {
        BfuLayoutPrefs.setLayoutId(context, "no_such_layout")
        val result = repository.getLayoutForMode(KeyboardMode.LETTERS, Locale.forLanguageTag("en"))
        assertTrue("the lock screen must always get a keyboard", result.isSuccess)
        assertEquals("gnu_qwerty_5r10c", result.getOrNull()?.id)
    }

    @Test
    fun `a malformed id is ignored rather than stored`() {
        BfuLayoutPrefs.setLayoutId(context, "../../etc/passwd")
        assertEquals("gnu_qwerty_5r10c", BfuLayoutPrefs.layoutId(context))
    }

    @Test
    fun `every BFU mode resolves while locked`() = runTest {
        BfuLayoutPrefs.setLayoutId(context, "gnu_qwerty_5r10c")
        KeyboardMode.entries.forEach { mode ->
            val result = repository.getLayoutForMode(mode, Locale.forLanguageTag("en"))
            assertTrue("$mode must resolve before first unlock", result.isSuccess)
        }
    }
}
