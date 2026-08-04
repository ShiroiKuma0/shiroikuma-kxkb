package com.urik.keyboard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.markUserUnlocked
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * The per-(app · fold-state · orientation) layout binding: each app remembers a language+layout pair
 * PER GEOMETRY, so semi-folded Termux and folded Termux keep different boards instead of overwriting
 * one shared value (the old per-language-only active layout).
 */
@RunWith(RobolectricTestRunner::class)
class PerAppLayoutBindingTest {
    private lateinit var context: Context
    private lateinit var repository: SettingsRepository
    private lateinit var dataStore: DataStore<Preferences>

    private val bindingsKey = stringPreferencesKey("per_app_layout_bindings")

    /** The package/geometry separator the store uses (SettingsRepository.APP_GEOMETRY_SEP). */
    private val sep = "\u001F"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.markUserUnlocked()
        repository = SettingsRepository(
            context,
            mock<KeyboardDatabase>(),
            mock<CacheMemoryManager>(),
            mock<WordFrequencyRepository>()
        )
        val field = SettingsRepository::class.java.getDeclaredField("realDataStore")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        dataStore = field.get(repository) as DataStore<Preferences>
    }

    @Before
    fun clearDataStore() = runTest {
        dataStore.edit { it.clear() }
    }

    @Test
    fun `the same app keeps a different layout in each geometry`() = runTest {
        repository.setPerAppLayoutBinding("com.termux", "semi_port", "cs", "cs_qwertz_5r10c")
        repository.setPerAppLayoutBinding("com.termux", "folded_port", "cs", "cs_3p2_5r9c")

        assertEquals("cs" to "cs_qwertz_5r10c", repository.getPerAppLayoutBinding("com.termux", "semi_port"))
        assertEquals("cs" to "cs_3p2_5r9c", repository.getPerAppLayoutBinding("com.termux", "folded_port"))
    }

    @Test
    fun `portrait and landscape of one fold state are independent`() = runTest {
        repository.setPerAppLayoutBinding("com.termux", "unfolded_port", "en", "en_qwerty_5r10c")
        repository.setPerAppLayoutBinding("com.termux", "unfolded_land", "en", "en_column_5r13c")

        assertEquals("en" to "en_qwerty_5r10c", repository.getPerAppLayoutBinding("com.termux", "unfolded_port"))
        assertEquals("en" to "en_column_5r13c", repository.getPerAppLayoutBinding("com.termux", "unfolded_land"))
    }

    @Test
    fun `different apps in the same geometry are independent`() = runTest {
        repository.setPerAppLayoutBinding("com.termux", "semi_port", "gnu", "gnu_qwerty_5r10c")
        repository.setPerAppLayoutBinding("org.thoughtcrime.securesms", "semi_port", "cs", "cs_qwertz_5r10c")

        assertEquals("gnu" to "gnu_qwerty_5r10c", repository.getPerAppLayoutBinding("com.termux", "semi_port"))
        assertEquals(
            "cs" to "cs_qwertz_5r10c",
            repository.getPerAppLayoutBinding("org.thoughtcrime.securesms", "semi_port")
        )
    }

    @Test
    fun `rebinding one geometry leaves the others untouched`() = runTest {
        repository.setPerAppLayoutBinding("com.termux", "semi_port", "cs", "cs_qwertz_5r10c")
        repository.setPerAppLayoutBinding("com.termux", "folded_port", "cs", "cs_3p2_5r9c")

        repository.setPerAppLayoutBinding("com.termux", "semi_port", "ru", "ru_yaverty_5r10c")

        assertEquals("ru" to "ru_yaverty_5r10c", repository.getPerAppLayoutBinding("com.termux", "semi_port"))
        assertEquals("cs" to "cs_3p2_5r9c", repository.getPerAppLayoutBinding("com.termux", "folded_port"))
    }

    @Test
    fun `an unbound app or geometry reads back null so the caller can fall back`() = runTest {
        repository.setPerAppLayoutBinding("com.termux", "semi_port", "cs", "cs_qwertz_5r10c")

        assertNull(repository.getPerAppLayoutBinding("com.termux", "unfolded_land"))
        assertNull(repository.getPerAppLayoutBinding("com.other.app", "semi_port"))
        assertNull(repository.getPerAppLayoutBinding("", "semi_port"))
        assertNull(repository.getPerAppLayoutBinding("com.termux", ""))
    }

    @Test
    fun `a malformed stored value reads back null instead of a half pair`() = runTest {
        dataStore.edit {
            it[bindingsKey] = listOf(
                "com.a" + sep + "semi_port\tnoseparator",
                "com.b" + sep + "semi_port\t|cs_qwertz_5r10c",
                "com.c" + sep + "semi_port\tcs|",
                "com.d" + sep + "semi_port\tcs|cs_qwertz_5r10c"
            ).joinToString("\n")
        }

        assertNull(repository.getPerAppLayoutBinding("com.a", "semi_port"))
        assertNull(repository.getPerAppLayoutBinding("com.b", "semi_port"))
        assertNull(repository.getPerAppLayoutBinding("com.c", "semi_port"))
        assertEquals("cs" to "cs_qwertz_5r10c", repository.getPerAppLayoutBinding("com.d", "semi_port"))
    }

    @Test
    fun `a blank language or layout is never written`() = runTest {
        repository.setPerAppLayoutBinding("com.termux", "semi_port", "", "cs_qwertz_5r10c")
        repository.setPerAppLayoutBinding("com.termux", "folded_port", "cs", "")

        assertNull(repository.getPerAppLayoutBinding("com.termux", "semi_port"))
        assertNull(repository.getPerAppLayoutBinding("com.termux", "folded_port"))
    }

    @Test
    fun `bindings ride along in the raw backup values`() = runTest {
        repository.setPerAppLayoutBinding("com.termux", "semi_port", "cs", "cs_qwertz_5r10c")

        val raw = repository.exportRawBackupValues(
            setOf(SettingsRepository.RAW_KEY_PER_APP_LAYOUT_BINDINGS)
        )

        assertTrue(raw.containsKey(SettingsRepository.RAW_KEY_PER_APP_LAYOUT_BINDINGS))

        dataStore.edit { it.clear() }
        repository.importRawBackupValues(raw)

        assertEquals("cs" to "cs_qwertz_5r10c", repository.getPerAppLayoutBinding("com.termux", "semi_port"))
    }
}
