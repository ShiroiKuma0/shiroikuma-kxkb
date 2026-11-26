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

    @Test
    fun `activating an edited copy moves the apps that remembered its original`() = runTest {
        // The 2026-11-20 fault: every app was bound to the stock GNU 15c, so the edited copy that had just been
        // activated was switched away from on the next focus.
        repository.setPerAppLayoutBinding("shiroikuma.memo", "semi_port", "gnu", "gnu_5r15c")
        repository.setPerAppLayoutBinding("shiroikuma.memo", "unfolded_port", "gnu", "gnu_5r15c_copy")
        repository.setPerAppLayoutBinding("com.termux", "folded_port", "gnu", "gnu_5r10c")
        repository.setPerAppLayoutBinding("com.termux", "semi_port", "cs", "cs_3p2_5r9c")

        val moved = repository.rebindPerAppLayouts(
            "gnu", setOf("gnu_5r15c", "gnu_5r15c_copy", "gnu_5r15c_copy2"), "gnu_5r15c_copy2"
        )

        assertEquals(2, moved.getOrThrow())
        assertEquals("gnu" to "gnu_5r15c_copy2", repository.getPerAppLayoutBinding("shiroikuma.memo", "semi_port"))
        assertEquals("gnu" to "gnu_5r15c_copy2", repository.getPerAppLayoutBinding("shiroikuma.memo", "unfolded_port"))
        // Another board of the same language, and another language, stay where they were.
        assertEquals("gnu" to "gnu_5r10c", repository.getPerAppLayoutBinding("com.termux", "folded_port"))
        assertEquals("cs" to "cs_3p2_5r9c", repository.getPerAppLayoutBinding("com.termux", "semi_port"))
    }

    @Test
    fun `each fold state keeps its own board for a language, whatever the app`() = runTest {
        repository.setGeometryLayout("semi_port", "cs", "cs_3p2_5r9c_copy")
        repository.setGeometryLayout("folded_port", "cs", "cs_3p2_5r4c")
        repository.setGeometryLayout("folded_port", "en", "en_5r10c")

        assertEquals("cs_3p2_5r9c_copy", repository.getGeometryLayout("semi_port", "cs"))
        assertEquals("cs_3p2_5r4c", repository.getGeometryLayout("folded_port", "cs"))
        assertEquals("en_5r10c", repository.getGeometryLayout("folded_port", "en"))
        assertEquals(null, repository.getGeometryLayout("unfolded_port", "cs"))
    }

    @Test
    fun `a fold state never picked in takes the board most apps use there`() = runTest {
        repository.setPerAppLayoutBinding("a.one", "folded_port", "cs", "cs_3p2_5r4c")
        repository.setPerAppLayoutBinding("a.two", "folded_port", "cs", "cs_3p2_5r4c")
        repository.setPerAppLayoutBinding("a.three", "folded_port", "cs", "cs_5r10c")
        repository.setPerAppLayoutBinding("a.one", "semi_port", "cs", "cs_3p2_5r9c")
        repository.setPerAppLayoutBinding("a.one", "folded_port_x", "cs", "cs_wrong")

        assertEquals("cs_3p2_5r4c", repository.getGeometryLayout("folded_port", "cs"))
        assertEquals("cs_3p2_5r9c", repository.getGeometryLayout("semi_port", "cs"))
        assertEquals(null, repository.getGeometryLayout("folded_port", "en"))

        repository.setGeometryLayout("folded_port", "cs", "cs_5r10c")
        assertEquals("cs_5r10c", repository.getGeometryLayout("folded_port", "cs"))
    }

    @Test
    fun `a rebind moves the fold-state boards of that language only`() = runTest {
        repository.setGeometryLayout("semi_port", "cs", "cs_3p2_5r9c")
        repository.setGeometryLayout("folded_port", "cs", "cs_3p2_5r4c")
        repository.setGeometryLayout("semi_port", "en", "cs_3p2_5r9c")

        repository.rebindPerAppLayouts("cs", setOf("cs_3p2_5r9c"), "cs_3p2_5r9c_copy")

        assertEquals("cs_3p2_5r9c_copy", repository.getGeometryLayout("semi_port", "cs"))
        assertEquals("cs_3p2_5r4c", repository.getGeometryLayout("folded_port", "cs"))
        assertEquals("cs_3p2_5r9c", repository.getGeometryLayout("semi_port", "en"))
    }

    @Test
    fun `a remembered stock opens its copy, and a copy that exists opens itself`() {
        fun entry(id: String, from: String?) = com.urik.keyboard.data.LayoutEntry(
            id = id, lang = "gnu", name = "GNU 15c", kind = "", width = "", derivedFrom = from
        )
        val entries = listOf(entry("gnu_5r15c_copy", "gnu_5r15c"), entry("gnu_5r15c_copy2", "gnu_5r15c"))
        val store = com.urik.keyboard.data.CustomLayoutStore

        // Two copies of one board are two boards: the folded one is not replaced by the active semi one.
        assertEquals(
            "gnu_5r15c_copy",
            store.standInFor("gnu_5r15c_copy", "gnu", "gnu_5r15c_copy2", entries, null)
        )
        // The stock follows the active copy of it…
        assertEquals(
            "gnu_5r15c_copy2",
            store.standInFor("gnu_5r15c", "gnu", "gnu_5r15c_copy2", entries, null)
        )
        // …or, with another board active, the one copy the switcher offers in its place.
        assertEquals(
            "gnu_5r15c_copy",
            store.standInFor("gnu_5r15c", "gnu", "gnu_5r10c", entries, setOf("gnu_5r15c_copy", "gnu_5r10c"))
        )
        // A stock the switcher still offers, or with no copy, stays itself.
        assertEquals(
            "gnu_5r15c",
            store.standInFor("gnu_5r15c", "gnu", "gnu_5r10c", entries, setOf("gnu_5r15c", "gnu_5r15c_copy"))
        )
        assertEquals("gnu_5r10c", store.standInFor("gnu_5r10c", "gnu", "gnu_5r15c_copy", entries, null))
    }
}
