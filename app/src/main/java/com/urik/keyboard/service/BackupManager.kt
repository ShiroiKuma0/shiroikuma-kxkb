package com.urik.keyboard.service

import android.content.Context
import com.urik.keyboard.R
import com.urik.keyboard.data.BfuLayoutPrefs
import com.urik.keyboard.data.CustomLayoutStore
import com.urik.keyboard.data.LayoutEntry
import com.urik.keyboard.data.LayoutRegistry
import com.urik.keyboard.data.database.CustomKeyMapping
import com.urik.keyboard.data.database.CustomKeyMappingDao
import com.urik.keyboard.data.database.LearnedWord
import com.urik.keyboard.data.database.LearnedWordDao
import com.urik.keyboard.data.database.UserDictionaryDao
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.data.database.WordSource
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One independently selectable section of a backup. Each part maps to a single JSON file inside the export
 * ZIP. [labelRes] is the user-facing name; [fileName] is the entry name in the archive; [defaultSelected]
 * controls whether its checkbox starts ticked.
 */
enum class BackupPart(val id: String, val fileName: String, val labelRes: Int, val defaultSelected: Boolean = true) {
    APPEARANCE("appearance", "appearance.json", R.string.backup_part_appearance),
    SETTINGS("settings", "settings.json", R.string.backup_part_settings),
    LAYOUTS("layouts", "layouts.json", R.string.backup_part_layouts),
    USER_DICTIONARY("user_dictionary", "user_dictionary.json", R.string.backup_part_user_dictionary),
    LEARNED_WORDS("learned_words", "learned_words.json", R.string.backup_part_learned_words),
    NEXT_WORD("next_word", "next_word.json", R.string.backup_part_next_word),
    BLACKLIST("blacklist", "blacklist.json", R.string.backup_part_blacklist),
    PER_APP("per_app", "per_app.json", R.string.backup_part_per_app);

    companion object {
        fun fromId(id: String): BackupPart? = entries.firstOrNull { it.id == id }
    }
}

/** Outcome of an export/import: a human line per part that succeeded, plus any per-part error labels. */
data class BackupResult(val lines: List<String>, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * The modular export/import engine. An export is a single ZIP — one JSON file per selected [BackupPart] plus
 * a `manifest.json`. Import reads the ZIP and applies each selected part that is present, **merging** per row
 * (insert-or-replace / additive) rather than wiping, so a restore never destroys data a part didn't cover and
 * re-importing the same file is idempotent. Each part is isolated: one failing part is reported but never
 * aborts the others. All I/O runs off the main thread.
 *
 * Runs only when the device is unlocked (the Settings UI that drives it is post-unlock), so the
 * credential-protected stores it touches are available.
 */
@Singleton
class BackupManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val customKeyMappingDao: CustomKeyMappingDao,
    private val userDictionaryDao: UserDictionaryDao,
    private val learnedWordDao: LearnedWordDao,
    private val userWordFrequencyDao: UserWordFrequencyDao,
    private val userWordBigramDao: UserWordBigramDao,
    private val blacklistRepository: BlacklistRepository
) {
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Write the selected [parts] to [out] as a backup ZIP. [appVersion] is recorded in the manifest.
     *
     * [onProgress] is invoked after each part is written, with `(done, total, part label)` — the headless
     * automation path ([com.urik.keyboard.automation.StateExportReceiver]) turns those into real-count progress
     * broadcasts. The UI panel and the receiver are the two thin callers of this one engine.
     */
    suspend fun export(
        parts: Set<BackupPart>,
        out: OutputStream,
        appVersion: String,
        onProgress: ((done: Int, total: Int, partLabel: String) -> Unit)? = null
    ): BackupResult =
        withContext(ioDispatcher) {
            val lines = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val included = mutableListOf<String>()
            val total = BackupPart.entries.count { it in parts }
            var done = 0
            ZipOutputStream(out).use { zip ->
                for (part in BackupPart.entries) {
                    if (part !in parts) continue
                    try {
                        val (payload, count) = exportPart(part)
                        zip.putNextEntry(ZipEntry(part.fileName))
                        zip.write(payload.toString().toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        included.add(part.id)
                        lines.add(label(part, count))
                    } catch (e: Exception) {
                        logPart("export", part, e)
                        errors.add(context.getString(part.labelRes))
                    }
                    done++
                    onProgress?.invoke(done, total, context.getString(part.labelRes))
                }
                val manifest = JSONObject()
                    .put("format", FORMAT)
                    .put("version", FORMAT_VERSION)
                    .put("app", context.packageName)
                    .put("appVersion", appVersion)
                    .put("createdAt", System.currentTimeMillis())
                    .put("parts", JSONArray(included))
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            BackupResult(lines, errors)
        }

    /** Read a backup ZIP from [input] and apply the selected [parts] that it contains. */
    suspend fun import(parts: Set<BackupPart>, input: InputStream): BackupResult = withContext(ioDispatcher) {
        val files = readZip(input)
        val lines = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (part in BackupPart.entries) {
            if (part !in parts) continue
            val raw = files[part.fileName] ?: continue
            try {
                val count = importPart(part, JSONObject(raw))
                lines.add(label(part, count))
            } catch (e: Exception) {
                logPart("import", part, e)
                errors.add(context.getString(part.labelRes))
            }
        }
        LayoutRegistry.invalidate()
        BackupResult(lines, errors)
    }

    /** The parts a backup ZIP actually contains, per its manifest (for showing/pre-ticking on import). */
    suspend fun partsInBackup(input: InputStream): Set<BackupPart> = withContext(ioDispatcher) {
        val files = readZip(input)
        val manifest = files[MANIFEST]?.let { runCatching { JSONObject(it) }.getOrNull() }
        val ids = manifest?.optJSONArray("parts")
        if (ids != null) {
            (0 until ids.length()).mapNotNull { BackupPart.fromId(ids.optString(it)) }.toSet()
        } else {
            // No manifest (or unreadable): fall back to whichever part files are present.
            BackupPart.entries.filter { files.containsKey(it.fileName) }.toSet()
        }
    }

    // ---- per-part export ------------------------------------------------------------------------------

    private suspend fun exportPart(part: BackupPart): Pair<JSONObject, Int> = when (part) {
        BackupPart.APPEARANCE -> {
            val raw = settingsRepository.exportRawBackupValues(
                setOf(SettingsRepository.RAW_KEY_PER_GEOMETRY_LOOK, SettingsRepository.RAW_KEY_LIBRARY_LOOK)
            )
            JSONObject().put("raw", JSONObject(raw)) to raw.size
        }

        BackupPart.SETTINGS -> {
            val prefs = settingsRepository.exportPreferences().getOrDefault(emptyMap())
            val mappings = customKeyMappingDao.getAllMappings()
            val raw = settingsRepository.exportRawBackupValues(
                setOf(
                    SettingsRepository.RAW_KEY_CUSTOM_SUGGESTIONS,
                    SettingsRepository.RAW_KEY_CUSTOM_SUGGESTIONS_BY_LANG
                )
            )
            val mappingsArr = JSONArray()
            mappings.forEach { m ->
                mappingsArr.put(JSONObject().put("baseKey", m.baseKey).put("customSymbol", m.customSymbol))
            }
            JSONObject()
                .put("preferences", JSONObject(prefs))
                .put("customKeyMappings", mappingsArr)
                .put("raw", JSONObject(raw)) to prefs.size
        }

        BackupPart.LAYOUTS -> {
            val entries = CustomLayoutStore.customEntries(context)
            val arr = JSONArray()
            entries.forEach { e ->
                val text = CustomLayoutStore.customLayoutText(context, e.id) ?: return@forEach
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("lang", e.lang)
                        .put("name", e.name)
                        .put("kind", e.kind)
                        .put("width", e.width)
                        .apply { e.derivedFrom?.let { put("derivedFrom", it) } }
                        .put("json", text)
                )
            }
            val raw = settingsRepository.exportRawBackupValues(
                setOf(
                    SettingsRepository.RAW_KEY_ACTIVE_LAYOUT_BY_LANGUAGE,
                    SettingsRepository.RAW_KEY_VISIBLE_LAYOUTS_BY_LANGUAGE
                )
            )
            JSONObject()
                .put("customLayouts", arr)
                .put("stockNames", JSONObject(CustomLayoutStore.stockNameOverrides(context)))
                // The lock-screen (BFU) layout pick lives in device-protected prefs, outside DataStore —
                // carried here so a restore doesn't silently drop it back to the default.
                .put("bfuLayout", BfuLayoutPrefs.layoutId(context))
                .put("raw", JSONObject(raw)) to arr.length()
        }

        BackupPart.USER_DICTIONARY -> {
            val rows = userDictionaryDao.getAll()
            val arr = JSONArray()
            rows.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("languageTag", r.languageTag)
                        .put("kind", r.kind)
                        .put("matchKey", r.matchKey)
                        .put("value", r.value)
                        .put("frequency", r.frequency)
                        .put("addedAt", r.addedAt)
                        .put("lastUsed", r.lastUsed)
                )
            }
            JSONObject().put("entries", arr) to arr.length()
        }

        BackupPart.LEARNED_WORDS -> {
            val words = learnedWordDao.getAllLearnedWords()
            val wordsArr = JSONArray()
            words.forEach { w ->
                wordsArr.put(
                    JSONObject()
                        .put("word", w.word)
                        .put("wordNormalized", w.wordNormalized)
                        .put("languageTag", w.languageTag)
                        .put("frequency", w.frequency)
                        .put("source", w.source.name)
                        .put("createdAt", w.createdAt)
                        .put("lastUsed", w.lastUsed)
                )
            }
            val freqArr = JSONArray()
            userWordFrequencyDao.getAll().forEach { f ->
                freqArr.put(
                    JSONObject()
                        .put("languageTag", f.languageTag)
                        .put("wordNormalized", f.wordNormalized)
                        .put("frequency", f.frequency)
                        .put("lastUsed", f.lastUsed)
                )
            }
            JSONObject().put("words", wordsArr).put("frequencies", freqArr) to wordsArr.length()
        }

        BackupPart.NEXT_WORD -> {
            val arr = JSONArray()
            userWordBigramDao.getAll().forEach { b ->
                arr.put(
                    JSONObject()
                        .put("languageTag", b.languageTag)
                        .put("wordA", b.wordANormalized)
                        .put("wordB", b.wordBNormalized)
                        .put("frequency", b.frequency)
                        .put("lastUsed", b.lastUsed)
                )
            }
            JSONObject().put("bigrams", arr) to arr.length()
        }

        BackupPart.BLACKLIST -> {
            val words = blacklistRepository.getAll()
            JSONObject().put("words", JSONArray(words.toList())) to words.size
        }

        BackupPart.PER_APP -> {
            val raw = settingsRepository.exportRawBackupValues(
                setOf(SettingsRepository.RAW_KEY_PER_APP_LAYOUT_LANGUAGES)
            )
            JSONObject().put("raw", JSONObject(raw)) to raw.size
        }
    }

    // ---- per-part import ------------------------------------------------------------------------------

    private suspend fun importPart(part: BackupPart, json: JSONObject): Int = when (part) {
        BackupPart.APPEARANCE -> {
            val raw = json.optJSONObject("raw").toStringMap()
            settingsRepository.importRawBackupValues(raw)
            raw.size
        }

        BackupPart.SETTINGS -> {
            json.optJSONObject("preferences")?.toStringMap()?.let { settingsRepository.importPreferences(it) }
            val mappings = json.optJSONArray("customKeyMappings")
            var n = 0
            if (mappings != null) {
                for (i in 0 until mappings.length()) {
                    val o = mappings.getJSONObject(i)
                    val baseKey = o.optString("baseKey")
                    val symbol = o.optString("customSymbol")
                    if (baseKey.isNotBlank() && symbol.isNotBlank()) {
                        customKeyMappingDao.upsertMapping(CustomKeyMapping(baseKey = baseKey, customSymbol = symbol))
                        n++
                    }
                }
            }
            settingsRepository.importRawBackupValues(json.optJSONObject("raw").toStringMap())
            (json.optJSONObject("preferences")?.length() ?: 0) + n
        }

        BackupPart.LAYOUTS -> {
            val arr = json.optJSONArray("customLayouts")
            var n = 0
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val layoutJson = runCatching { JSONObject(o.getString("json")) }.getOrNull() ?: continue
                    val entry = LayoutEntry(
                        id = o.getString("id"),
                        lang = o.getString("lang"),
                        name = o.optString("name", o.getString("id")),
                        kind = o.optString("kind", ""),
                        width = o.optString("width", ""),
                        derivedFrom = o.optString("derivedFrom", "").takeIf { it.isNotEmpty() }
                    )
                    CustomLayoutStore.saveLayout(context, entry, layoutJson)
                    n++
                }
            }
            json.optJSONObject("stockNames")?.let { names ->
                names.keys().forEach { id -> CustomLayoutStore.setStockName(context, id, names.optString(id)) }
            }
            json.optString("bfuLayout").takeIf { it.isNotBlank() }?.let { BfuLayoutPrefs.setLayoutId(context, it) }
            settingsRepository.importRawBackupValues(json.optJSONObject("raw").toStringMap())
            n
        }

        BackupPart.USER_DICTIONARY -> {
            val arr = json.optJSONArray("entries") ?: JSONArray()
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                userDictionaryDao.importRow(
                    languageTag = o.getString("languageTag"),
                    kind = o.getString("kind"),
                    matchKey = o.getString("matchKey"),
                    value = o.getString("value"),
                    frequency = o.optInt("frequency", 1),
                    addedAt = o.optLong("addedAt", now),
                    lastUsed = o.optLong("lastUsed", now)
                )
            }
            arr.length()
        }

        BackupPart.LEARNED_WORDS -> {
            val words = json.optJSONArray("words") ?: JSONArray()
            for (i in 0 until words.length()) {
                val o = words.getJSONObject(i)
                val word = o.getString("word")
                val source = runCatching { WordSource.valueOf(o.optString("source")) }.getOrDefault(WordSource.IMPORTED)
                learnedWordDao.importWordWithMerge(
                    LearnedWord.create(
                        word = word,
                        wordNormalized = o.getString("wordNormalized"),
                        languageTag = o.getString("languageTag"),
                        frequency = o.optInt("frequency", 1),
                        source = source,
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        lastUsed = o.optLong("lastUsed", System.currentTimeMillis())
                    )
                )
            }
            val freqs = json.optJSONArray("frequencies") ?: JSONArray()
            for (i in 0 until freqs.length()) {
                val o = freqs.getJSONObject(i)
                userWordFrequencyDao.importRow(
                    languageTag = o.getString("languageTag"),
                    wordNormalized = o.getString("wordNormalized"),
                    frequency = o.optInt("frequency", 1),
                    lastUsed = o.optLong("lastUsed", System.currentTimeMillis())
                )
            }
            words.length()
        }

        BackupPart.NEXT_WORD -> {
            val arr = json.optJSONArray("bigrams") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                userWordBigramDao.importRow(
                    languageTag = o.getString("languageTag"),
                    wordANormalized = o.getString("wordA"),
                    wordBNormalized = o.getString("wordB"),
                    frequency = o.optInt("frequency", 1),
                    lastUsed = o.optLong("lastUsed", System.currentTimeMillis())
                )
            }
            arr.length()
        }

        BackupPart.BLACKLIST -> {
            val arr = json.optJSONArray("words") ?: JSONArray()
            val words = (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
            blacklistRepository.addAll(words)
            words.size
        }

        BackupPart.PER_APP -> {
            val raw = json.optJSONObject("raw").toStringMap()
            settingsRepository.importRawBackupValues(raw)
            raw.size
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private fun label(part: BackupPart, count: Int): String =
        context.getString(R.string.backup_part_line, context.getString(part.labelRes), count)

    private fun logPart(op: String, part: BackupPart, e: Exception) {
        ErrorLogger.logException(
            component = "BackupManager",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to op, "part" to part.id)
        )
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap { keys().forEach { k -> put(k, optString(k)) } }
    }

    private fun readZip(input: InputStream): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    companion object {
        const val FORMAT = "kxkb-backup"
        const val FORMAT_VERSION = 1
        const val MANIFEST = "manifest.json"

        /**
         * Backup filenames start with this; the UI scans the export dir for the newest such file, and the
         * automation contract writes the same name. 白い熊 keeps every sister app's backups in ONE directory,
         * so the family convention is mandatory: `<english-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip` — no version,
         * no `-export` infix, no suffix. Files written before 2026-07-25 carry
         * `shiroikuma-kxkb_<version>_<stamp>_backup.zip`; they share this prefix, so the newest-backup scan
         * and the import picker still find them.
         */
        const val EXPORT_PREFIX = "shiroikuma-kxkb_"

        /** The one backup name this app ever writes, from the UI panel and from the automation receiver alike. */
        fun exportFileName(now: Long = System.currentTimeMillis()): String =
            EXPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date(now)) + ".zip"

        /** Whether [name] is one of this app's backups (current or legacy name) rather than a sister app's. */
        fun isBackupFileName(name: String): Boolean = name.startsWith(EXPORT_PREFIX) && name.endsWith(".zip")
    }
}
