package com.urik.keyboard.data

import android.content.Context
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import com.urik.keyboard.utils.isUserUnlocked
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class LayoutErrorEntry(val count: Int, val lastErrorTime: Long)

/**
 * Circuit breaker for failed layout loads.
 *
 * Prevents repeated I/O attempts for missing/corrupt layout files.
 * Evicts oldest entries when capacity exceeded (LRU).
 */
private class BoundedLayoutErrorTracker(private val maxSize: Int) {
    private val entries =
        object : LinkedHashMap<String, LayoutErrorEntry>(maxSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LayoutErrorEntry>?): Boolean =
                size > maxSize
        }

    @Synchronized
    fun recordError(key: String): Int {
        val current = entries[key]
        val newCount = (current?.count ?: 0) + 1
        entries[key] = LayoutErrorEntry(newCount, System.currentTimeMillis())
        return newCount
    }

    @Synchronized
    fun getErrorCount(key: String): Int = entries[key]?.count ?: 0

    @Synchronized
    fun getLastErrorTime(key: String): Long = entries[key]?.lastErrorTime ?: 0L

    @Synchronized
    fun remove(key: String) = entries.remove(key)

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun cleanExpired(expiryMs: Long) {
        val now = System.currentTimeMillis()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastErrorTime > expiryMs) {
                iterator.remove()
            }
        }
    }
}

@Singleton
class KeyboardRepository
@Inject
constructor(
    @ApplicationContext private val context: Context,
    cacheMemoryManager: CacheMemoryManager,
    private val settingsRepository: com.urik.keyboard.settings.SettingsRepository
) {
    private val layoutCache: ManagedCache<String, KeyboardLayout> =
        cacheMemoryManager.createCache(
            name = "keyboard_layouts",
            maxSize = LAYOUT_CACHE_SIZE
        )

    private val failedLocales = mutableSetOf<String>()
    private val errorTracker = BoundedLayoutErrorTracker(maxSize = LAYOUT_ERROR_TRACKER_MAX_SIZE)

    private val maxLayoutRetries = MAX_LAYOUT_RETRIES
    private val layoutErrorCooldownMs = LAYOUT_ERROR_COOLDOWN_MS
    private val errorStateExpiryMs = LAYOUT_ERROR_STATE_EXPIRY_MS

    private var lastErrorCleanup = System.currentTimeMillis()

    private fun cleanupExpiredErrors() {
        val now = System.currentTimeMillis()

        if (now - lastErrorCleanup > LAYOUT_ERROR_CLEANUP_INTERVAL_MS) {
            errorTracker.cleanExpired(errorStateExpiryMs)

            val iterator = failedLocales.iterator()
            while (iterator.hasNext()) {
                val localeTag = iterator.next()
                val lastError = errorTracker.getLastErrorTime(localeTag)
                if (lastError > 0 && now - lastError > errorStateExpiryMs) {
                    iterator.remove()
                }
            }

            lastErrorCleanup = now
        }
    }

    /**
     * Fallback cascade on missing assets:
     * 1. Full locale (en-US) → 2. Language only (en) → 3. Hardcoded QWERTY
     *
     * Circuit breaker skips known-broken locales (3 failures, 60s cooldown).
     */
    suspend fun getLayoutForMode(
        mode: KeyboardMode,
        locale: Locale,
        currentAction: KeyboardKey.ActionType = KeyboardKey.ActionType.ENTER
    ): Result<KeyboardLayout> = withContext(Dispatchers.IO) {
        // BFU / Direct Boot: the active-layout setting is in locked storage and prediction isn't available.
        // Load the BFU pick (device-protected storage) straight from the APK assets, so the keyboard works on
        // the lock screen without touching credential-protected storage. Never fails — see [loadBfuLayout].
        if (!context.isUserUnlocked) {
            return@withContext Result.success(loadBfuLayout(mode, currentAction, locale))
        }
        val settings = settingsRepository.settings.first()
        val layoutIdentifier = resolveLayoutIdentifier(settings.alternativeKeyboardLayout, locale.toLanguageTag())

        val cacheKey = "${layoutIdentifier}_${mode.name}_${currentAction.name}"

        layoutCache.getIfPresent(cacheKey)?.let { cachedLayout ->
            return@withContext Result.success(cachedLayout)
        }

        return@withContext try {
            // Stamp the registry id onto the layout so the IME can read it synchronously (per-(app·layout·
            // geometry) size resolution on the show path must not block on an async id lookup).
            val layout = loadLayoutFromAssets(mode, layoutIdentifier, currentAction, locale).copy(id = layoutIdentifier)
            layoutCache.put(cacheKey, layout)
            Result.success(layout)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * The registry id of the layout the keyboard would load for [language] right now — the 1D-switcher active
     * layout → registry default → bundled fallback, honouring the alternative-layout setting. Exposed so the
     * IME can key the per-(app·layout·geometry) size override by the SAME id [getLayoutForMode] loads. BFU →
     * the forced bundled keymap.
     */
    suspend fun resolveActiveLayoutId(language: String): String = withContext(Dispatchers.IO) {
        if (!context.isUserUnlocked) return@withContext bfuLayoutId()
        resolveLayoutIdentifier(settingsRepository.settings.first().alternativeKeyboardLayout, language)
    }

    /**
     * The layout id the lock screen shows: the user's pick ([BfuLayoutPrefs], device-protected storage) when
     * it still resolves to a bundled asset, else the hard fallback [BfuLayoutPrefs.DEFAULT_LAYOUT_ID]. Custom
     * layouts are deliberately not resolvable here — their store is locked before first unlock.
     */
    private fun bfuLayoutId(): String {
        val picked = BfuLayoutPrefs.layoutId(context)
        return if (picked != BfuLayoutPrefs.DEFAULT_LAYOUT_ID && bundledLayoutExists(picked)) {
            picked
        } else {
            BfuLayoutPrefs.DEFAULT_LAYOUT_ID
        }
    }

    /** Whether `layouts/<id>.json` is present in the APK assets (always readable, lock screen included). */
    private fun bundledLayoutExists(id: String): Boolean = try {
        context.assets.open("layouts/$id.json").close()
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * The lock-screen keyboard, resolved along a fallback chain that CANNOT fail: the BFU pick → the bundled
     * GNU QWERTY 10c → the built-in code QWERTY ([getFallbackLayout]). A throw on this path would leave the
     * user staring at a PIN field with no keyboard, so every step is caught.
     */
    private suspend fun loadBfuLayout(
        mode: KeyboardMode,
        currentAction: KeyboardKey.ActionType,
        locale: Locale
    ): KeyboardLayout {
        val candidates = listOf(bfuLayoutId(), BfuLayoutPrefs.DEFAULT_LAYOUT_ID).distinct()
        for (id in candidates) {
            val layout = try {
                if (bundledLayoutExists(id)) {
                    loadLayoutFromAssets(mode, id, currentAction, locale).copy(id = id)
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
            if (layout != null) return layout
        }
        return getFallbackLayout(mode, currentAction)
    }

    private suspend fun resolveLayoutIdentifier(
        alternativeLayout: com.urik.keyboard.settings.AlternativeKeyboardLayout,
        language: String
    ): String =
        when (alternativeLayout) {
            com.urik.keyboard.settings.AlternativeKeyboardLayout.DEFAULT -> {
                // Per-language active layout (1D switcher) -> registry default -> bundled <lang>.json.
                val registry = LayoutRegistry.load(context)
                settingsRepository.getActiveLayoutForLanguage(language)?.takeIf { registry.has(it) }
                    ?: registry.defaultFor(language) ?: language
            }
            com.urik.keyboard.settings.AlternativeKeyboardLayout.QWERTY -> "en"
            com.urik.keyboard.settings.AlternativeKeyboardLayout.AZERTY -> "azerty"
            com.urik.keyboard.settings.AlternativeKeyboardLayout.QWERTZ -> "qwertz"
            com.urik.keyboard.settings.AlternativeKeyboardLayout.DVORAK -> "dvorak"
            com.urik.keyboard.settings.AlternativeKeyboardLayout.COLEMAK -> "colemak"
            com.urik.keyboard.settings.AlternativeKeyboardLayout.WORKMAN -> "workman"
            com.urik.keyboard.settings.AlternativeKeyboardLayout.HCESAR -> "hcesar"
            com.urik.keyboard.settings.AlternativeKeyboardLayout.BDS -> "bds"
        }

    /**
     * Load an arbitrary registry layout by its id (`layouts/<id>.json`), off the active-layout path — for
     * the Library preview. Cached by id+mode; returns null only if it can't be parsed at all.
     */
    suspend fun loadLayoutById(
        id: String,
        mode: KeyboardMode = KeyboardMode.LETTERS,
        currentAction: KeyboardKey.ActionType = KeyboardKey.ActionType.ENTER
    ): KeyboardLayout? = withContext(Dispatchers.IO) {
        val cacheKey = "preview_${id}_${mode.name}_${currentAction.name}"
        layoutCache.getIfPresent(cacheKey)?.let { return@withContext it }
        return@withContext try {
            loadLayoutFromAssets(mode, id, currentAction, Locale.getDefault()).also {
                layoutCache.put(cacheKey, it)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse an in-memory layout [JSONObject] into a [KeyboardLayout] for [mode], off any store/asset/cache —
     * for the Library's git-history preview, which renders a version of a layout as it was at a past commit
     * (the JSON comes straight from a git blob, never the live store). Returns null if it can't be parsed.
     */
    fun layoutFromJson(
        layoutData: JSONObject,
        mode: KeyboardMode = KeyboardMode.LETTERS,
        currentAction: KeyboardKey.ActionType = KeyboardKey.ActionType.ENTER
    ): KeyboardLayout? = try {
        parseLayoutForMode(layoutData, mode, currentAction)
    } catch (_: Exception) {
        null
    }

    /**
     * Drop all cached parsed layouts so a layout that was just edited in the visual editor re-parses from
     * the custom store on the next [loadLayoutById] / active-layout load (id-keyed entries would be stale).
     */
    fun invalidateLayoutCache() = layoutCache.invalidateAll()

    private suspend fun loadLayoutFromAssets(
        mode: KeyboardMode,
        layoutIdentifier: String,
        currentAction: KeyboardKey.ActionType,
        originalLocale: Locale
    ): KeyboardLayout = withContext(Dispatchers.IO) {
        cleanupExpiredErrors()

        if (shouldSkipLocale(layoutIdentifier)) {
            return@withContext getFallbackLayout(mode, currentAction)
        }

        return@withContext try {
            val layoutData = loadLayoutDataFromAssets(context, layoutIdentifier)
            parseLayoutForMode(layoutData, mode, currentAction).also {
                errorTracker.remove(layoutIdentifier)
                failedLocales.remove(layoutIdentifier)
            }
        } catch (_: FileNotFoundException) {
            handleAssetError(layoutIdentifier)
            if (layoutIdentifier in setOf("dvorak", "colemak", "workman", "hcesar", "bds")) {
                tryLoadFallback(context, "en", mode, currentAction)
            } else {
                tryLanguageFallback(context, originalLocale, mode, currentAction)
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "KeyboardRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "loadLayout")
            )
            handleAssetError(layoutIdentifier)
            getFallbackLayout(mode, currentAction)
        }
    }

    private fun loadLayoutDataFromAssets(context: Context, localeTag: String): JSONObject {
        // A user's custom (edited/duplicated) layout overrides the bundled asset of the same id.
        CustomLayoutStore.customLayoutText(context, localeTag)?.let { return JSONObject(it) }

        val filename = "$localeTag.json"

        return context.assets.open("layouts/$filename").bufferedReader().use { reader ->
            val jsonContent = reader.readText()

            if (jsonContent.isBlank()) {
                error("Layout file $filename is empty")
            }

            JSONObject(jsonContent)
        }
    }

    private fun parseLayoutForMode(
        layoutData: JSONObject,
        mode: KeyboardMode,
        currentAction: KeyboardKey.ActionType
    ): KeyboardLayout {
        if (!layoutData.has("modes")) {
            error("Layout data missing 'modes' section")
        }

        val isRTL = layoutData.optBoolean("isRTL", false)
        val script = layoutData.optString("script", "Latn")
        val showFlickHints = layoutData.optBoolean("showFlickHints", false)
        val hardwareKeymap = layoutData.optBoolean("hardwareKeymap", false)

        val modes = layoutData.getJSONObject("modes")
        val modeKey = mode.name.lowercase()

        // The letters page's row count — carried on EVERY mode so the height clamp uses one reference and the
        // keyboard stays the same height across pages (see KeyboardLayout.referenceRows).
        val referenceRows = modes.optJSONObject("letters")?.optJSONArray("rows")?.length() ?: 0

        if (!modes.has(modeKey)) {
            // The Number pad (alt3) is a fixed shared design supplied by the fallback, so layouts need not
            // each declare a `numpad` section — render the canonical calc-pad whenever it is absent.
            if (mode == KeyboardMode.NUMPAD) {
                return KeyboardLayout(
                    mode = mode,
                    rows = getFallbackNumpadLayout(currentAction),
                    isRTL = isRTL,
                    script = script,
                    showFlickHints = showFlickHints,
                    referenceRows = referenceRows
                )
            }
            error("Layout data missing mode: $modeKey")
        }

        val modeData = modes.getJSONObject(modeKey)
        val rowsArray = modeData.getJSONArray("rows")

        val rows = mutableListOf<List<KeyboardKey>>()

        for (i in 0 until rowsArray.length()) {
            val rowArray = rowsArray.getJSONArray(i)
            val row = mutableListOf<KeyboardKey>()

            for (j in 0 until rowArray.length()) {
                val keyData = rowArray.getJSONObject(j)
                val key = parseKeyFromJson(keyData, currentAction)
                row.add(key)
            }

            rows.add(row)
        }

        return KeyboardLayout(
            mode = mode,
            rows = rows,
            isRTL = isRTL,
            script = script,
            showFlickHints = showFlickHints,
            hardwareKeymap = hardwareKeymap,
            referenceRows = referenceRows
        )
    }

    // The parse/emit logic lives in the shared, lossless [KeyboardJsonCodec] so the editor and the converter
    // round-trip a key through the model the same way. This stays a thin delegate (rebase-friendly).
    private fun parseKeyFromJson(keyData: JSONObject, currentAction: KeyboardKey.ActionType): KeyboardKey =
        KeyboardJsonCodec.parseKey(keyData, currentAction)

    private suspend fun tryLanguageFallback(
        context: Context,
        locale: Locale,
        mode: KeyboardMode,
        currentAction: KeyboardKey.ActionType
    ): KeyboardLayout = withContext(Dispatchers.IO) {
        val languageOnly = locale.language

        if (languageOnly != locale.toLanguageTag() && !shouldSkipLocale(languageOnly)) {
            return@withContext try {
                val layoutData = loadLayoutDataFromAssets(context, languageOnly)
                parseLayoutForMode(layoutData, mode, currentAction)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "KeyboardRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "tryLanguageFallback")
                )
                handleAssetError(languageOnly)
                getFallbackLayout(mode, currentAction)
            }
        }

        return@withContext getFallbackLayout(mode, currentAction)
    }

    private suspend fun tryLoadFallback(
        context: Context,
        fallbackIdentifier: String,
        mode: KeyboardMode,
        currentAction: KeyboardKey.ActionType
    ): KeyboardLayout = withContext(Dispatchers.IO) {
        return@withContext try {
            val layoutData = loadLayoutDataFromAssets(context, fallbackIdentifier)
            parseLayoutForMode(layoutData, mode, currentAction)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "KeyboardRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "tryLoadFallback")
            )
            getFallbackLayout(mode, currentAction)
        }
    }

    private fun getFallbackLayout(mode: KeyboardMode, currentAction: KeyboardKey.ActionType): KeyboardLayout {
        val rows =
            when (mode) {
                KeyboardMode.LETTERS -> getFallbackLettersLayout(currentAction)
                KeyboardMode.NUMBERS -> getFallbackNumbersLayout(currentAction)
                KeyboardMode.SYMBOLS -> getFallbackSymbolsLayout(currentAction)
                KeyboardMode.SYMBOLS_SECONDARY -> getFallbackSymbolsSecondaryLayout(currentAction)
                KeyboardMode.NUMPAD -> getFallbackNumpadLayout(currentAction)
            }
        return KeyboardLayout(mode = mode, rows = rows)
    }

    private fun getFallbackLettersLayout(actionType: KeyboardKey.ActionType): List<List<KeyboardKey>> = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
        },
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.LETTER)
        },
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.LETTER)
        },
        listOf(
            KeyboardKey.Action(KeyboardKey.ActionType.SHIFT),
            KeyboardKey.Character("z", KeyboardKey.KeyType.LETTER),
            KeyboardKey.Character("x", KeyboardKey.KeyType.LETTER),
            KeyboardKey.Character("c", KeyboardKey.KeyType.LETTER),
            KeyboardKey.Character("v", KeyboardKey.KeyType.LETTER),
            KeyboardKey.Character("b", KeyboardKey.KeyType.LETTER),
            KeyboardKey.Character("n", KeyboardKey.KeyType.LETTER),
            KeyboardKey.Character("m", KeyboardKey.KeyType.LETTER),
            KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        ),
        listOf<KeyboardKey>(
            KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS),
            KeyboardKey.Action(KeyboardKey.ActionType.SPACE),
            KeyboardKey.Action(actionType)
        )
    )

    private fun getFallbackNumbersLayout(actionType: KeyboardKey.ActionType): List<List<KeyboardKey>> = listOf(
        listOf("1", "2", "3").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.NUMBER)
        },
        listOf("4", "5", "6").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.NUMBER)
        },
        listOf("7", "8", "9").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.NUMBER)
        },
        listOf(
            KeyboardKey.Character(".", KeyboardKey.KeyType.PUNCTUATION),
            KeyboardKey.Character("0", KeyboardKey.KeyType.NUMBER),
            KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        ),
        listOf<KeyboardKey>(
            KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_LETTERS),
            KeyboardKey.Action(KeyboardKey.ActionType.SPACE),
            KeyboardKey.Action(actionType)
        )
    )

    /**
     * The dedicated Number pad ([KeyboardMode.NUMPAD]) — re-derived from the Multiling "num" keypad (its one
     * canonical design): an even 6-column × 4-row calculator grid. Columns 0-1 carry the math/punctuation
     * operators, columns 2-4 the 1-9 numpad with 0 below, and column 5 the edit keys. Every key is given an
     * explicit width of 1 cell so the functional-key heuristic does not inflate Backspace / Space / Enter and
     * break the grid (getKeyWeight honours an explicit width over its heuristics). The bottom-left is a `←`
     * back-to-letters key — a compass key with the same centre `alpha0` layer binding the GNU alt pages use
     * (which also makes it functional-coloured like Space/Enter); the Multiling pad toggled via its persistent
     * bar, so an on-page return key is needed here.
     */
    private fun getFallbackNumpadLayout(actionType: KeyboardKey.ActionType): List<List<KeyboardKey>> {
        fun num(c: String) = KeyboardKey.Character(c, KeyboardKey.KeyType.NUMBER, width = 1f)
        fun sym(c: String) = KeyboardKey.Character(c, KeyboardKey.KeyType.SYMBOL, width = 1f)
        fun punct(c: String) = KeyboardKey.Character(c, KeyboardKey.KeyType.PUNCTUATION, width = 1f)
        fun act(a: KeyboardKey.ActionType) = KeyboardKey.Action(a, width = 1f)
        val back = KeyboardKey.FlickKey(
            center = "←",
            up = null, right = null, down = null, left = null,
            type = KeyboardKey.KeyType.SYMBOL,
            bindings = mapOf("center" to KeyboardKey.FlickBinding.Layer("alpha0")),
            width = 1f
        )
        return listOf(
            listOf(sym("#"), sym(";"), num("1"), num("2"), num("3"), act(KeyboardKey.ActionType.BACKSPACE)),
            listOf(sym("*"), sym("/"), num("4"), num("5"), num("6"), sym("~")),
            listOf(sym("+"), sym("-"), num("7"), num("8"), num("9"), sym(":")),
            listOf(
                back,
                punct(","),
                punct("."),
                num("0"),
                act(KeyboardKey.ActionType.SPACE),
                act(actionType)
            )
        )
    }

    private fun getFallbackSymbolsLayout(actionType: KeyboardKey.ActionType): List<List<KeyboardKey>> = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
        },
        listOf("!", "@", "#", "$", "%", "^", "&", "*").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
        },
        listOf("-", "_", "=", "+", "[", "]", "{", "}").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
        },
        listOf(";", ":", "'", "\"", ",", ".", "<", ">")
            .map {
                KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
            },
        listOf<KeyboardKey>(
            KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_NUMBERS),
            KeyboardKey.Action(KeyboardKey.ActionType.SPACE),
            KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE),
            KeyboardKey.Action(actionType)
        )
    )

    private fun getFallbackSymbolsSecondaryLayout(actionType: KeyboardKey.ActionType): List<List<KeyboardKey>> = listOf(
        listOf("~", "`", "\u2022", "\u2014", "\u2013", "\u00B7", "\u03C0", "\u00F7", "\u00D7", "\u00B1").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
        },
        listOf("\u00A3", "\u00A5", "\u20AC", "\u00A2", "\u00B0", "\u00AE", "\u00A9", "\u2122", "\u221E", "\u00A7").map {
            KeyboardKey.Character(it, KeyboardKey.KeyType.SYMBOL)
        },
        listOf(
            KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_SYMBOLS),
            KeyboardKey.Character("\u201C", KeyboardKey.KeyType.SYMBOL),
            KeyboardKey.Character("\u201D", KeyboardKey.KeyType.SYMBOL),
            KeyboardKey.Character("\u00AB", KeyboardKey.KeyType.SYMBOL),
            KeyboardKey.Character("\u00BB", KeyboardKey.KeyType.SYMBOL),
            KeyboardKey.Character("\u2039", KeyboardKey.KeyType.SYMBOL),
            KeyboardKey.Character("\u203A", KeyboardKey.KeyType.SYMBOL),
            KeyboardKey.Character("\u201E", KeyboardKey.KeyType.SYMBOL),
            KeyboardKey.Character("\u2026", KeyboardKey.KeyType.SYMBOL),
            KeyboardKey.Action(KeyboardKey.ActionType.BACKSPACE)
        ),
        listOf<KeyboardKey>(
            KeyboardKey.Action(KeyboardKey.ActionType.MODE_SWITCH_LETTERS),
            KeyboardKey.Action(KeyboardKey.ActionType.SPACE),
            KeyboardKey.Action(actionType)
        )
    )

    private fun shouldSkipLocale(localeTag: String): Boolean {
        val errorCount = errorTracker.getErrorCount(localeTag)
        val lastError = errorTracker.getLastErrorTime(localeTag)
        val now = System.currentTimeMillis()

        return errorCount >= maxLayoutRetries && now - lastError < layoutErrorCooldownMs
    }

    private fun handleAssetError(localeTag: String) {
        val errorCount = errorTracker.recordError(localeTag)

        if (errorCount >= maxLayoutRetries) {
            failedLocales.add(localeTag)
        }
    }

    /** Call when changing keyboard settings or on memory pressure. */
    fun cleanup() {
        layoutCache.invalidateAll()
        failedLocales.clear()
        errorTracker.clear()
    }

    private companion object {
        const val LAYOUT_CACHE_SIZE = 20
        const val MAX_LAYOUT_RETRIES = 3
        const val LAYOUT_ERROR_COOLDOWN_MS = 60000L
        const val LAYOUT_ERROR_STATE_EXPIRY_MS = 3600000L
        const val LAYOUT_ERROR_CLEANUP_INTERVAL_MS = 600000L
        const val LAYOUT_ERROR_TRACKER_MAX_SIZE = 20
    }
}
