package com.urik.keyboard.data

import com.urik.keyboard.data.database.UserDictionaryDao
import com.urik.keyboard.data.database.UserDictionaryEntry
import com.urik.keyboard.data.database.UserDictionaryKind
import com.urik.keyboard.service.WordNormalizer
import com.urik.keyboard.utils.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single gateway to the unified, per-language **user dictionary** (words + shortcuts + Japanese
 * reading→kanji). Every read on the hot suggestion path goes through an in-memory per-language cache so the
 * IME never blocks on Room; writes (add / use / edit / delete) update Room and refresh the cache.
 *
 * Entries are strictly language-scoped — [matchesFor] only ever returns entries of the requested language,
 * so a word taught in one language can never surface in another.
 */
@Singleton
class UserDictionaryRepository
@Inject
constructor(
    private val dao: UserDictionaryDao,
    private val normalizer: WordNormalizer
) {
    /**
     * A loaded entry with its diacritic-folded lookup key precomputed. [foldedKey] is what cluster /
     * accent-insensitive matching compares against (e.g. "teď" → "ted"); [value] is the inserted text.
     */
    data class Entry(
        val id: Long,
        val languageTag: String,
        val kind: UserDictionaryKind,
        val matchKey: String,
        val value: String,
        val foldedKey: String,
        val frequency: Int,
        val addedAt: Long,
        val lastUsed: Long
    )

    private val cache = ConcurrentHashMap<String, List<Entry>>()
    private val loadMutex = Mutex()

    private var writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Loaded, language-scoped snapshot — loads from Room on first request for a language, then caches. */
    suspend fun matchesFor(languageTag: String): List<Entry> {
        cache[languageTag]?.let { return it }
        loadMutex.withLock {
            cache[languageTag]?.let { return it }
            val loaded = loadLanguage(languageTag)
            cache[languageTag] = loaded
            return loaded
        }
    }

    /** Synchronous best-effort snapshot (no DB hit). Empty if the language hasn't been loaded yet. */
    fun cachedMatchesFor(languageTag: String): List<Entry> = cache[languageTag] ?: emptyList()

    private suspend fun loadLanguage(languageTag: String): List<Entry> =
        try {
            withContext(ioDispatcher) { dao.getForLanguage(languageTag).map { it.toEntry() } }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UserDictionaryRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "loadLanguage", "language" to languageTag)
            )
            emptyList()
        }

    private fun UserDictionaryEntry.toEntry(): Entry =
        Entry(
            id = id,
            languageTag = languageTag,
            kind = UserDictionaryKind.fromTag(kind) ?: UserDictionaryKind.WORD,
            matchKey = matchKey,
            value = value,
            foldedKey = normalizer.stripDiacritics(matchKey).lowercase(),
            frequency = frequency,
            addedAt = addedAt,
            lastUsed = lastUsed
        )

    /**
     * Add a new entry (or, if an identical one exists, bump its frequency). An explicit add seeds with
     * [ADDED_SEED_FREQUENCY] so it is immediately top-ranked. Refreshes the language cache.
     */
    suspend fun add(
        languageTag: String,
        kind: UserDictionaryKind,
        matchKey: String,
        value: String,
        seedFrequency: Int = ADDED_SEED_FREQUENCY
    ) {
        val key = matchKey.trim()
        val v = value.trim()
        if (languageTag.isBlank() || key.isEmpty() || v.isEmpty()) return
        try {
            withContext(ioDispatcher) {
                dao.upsertIncrement(languageTag, kind.tag, key, v, seedFrequency, System.currentTimeMillis())
            }
            reload(languageTag)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UserDictionaryRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "add", "language" to languageTag)
            )
        }
    }

    /**
     * Fire-and-forget: a word was just committed — promote any matching entry of this language so repeated
     * phrases climb. Safe on the hot commit path (runs off the caller's thread).
     */
    fun recordUse(languageTag: String, committedValue: String) {
        val v = committedValue.trim()
        if (languageTag.isBlank() || v.isEmpty()) return
        writeScope.launch {
            try {
                val changed = dao.incrementByValue(languageTag, v, System.currentTimeMillis())
                if (changed > 0) reload(languageTag)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "UserDictionaryRepository",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "recordUse", "language" to languageTag)
                )
            }
        }
    }

    suspend fun updateEntry(id: Long, languageTag: String, matchKey: String, value: String) {
        val key = matchKey.trim()
        val v = value.trim()
        if (id <= 0L || key.isEmpty() || v.isEmpty()) return
        try {
            withContext(ioDispatcher) { dao.updateEntry(id, key, v, System.currentTimeMillis()) }
            reload(languageTag)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UserDictionaryRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "updateEntry", "language" to languageTag)
            )
        }
    }

    suspend fun deleteById(id: Long, languageTag: String) {
        try {
            withContext(ioDispatcher) { dao.deleteById(id) }
            reload(languageTag)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UserDictionaryRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "deleteById", "language" to languageTag)
            )
        }
    }

    /** Drop and reload a single language's cache from Room. */
    suspend fun reload(languageTag: String) {
        cache[languageTag] = loadLanguage(languageTag)
    }

    // ---- Export / import hooks (for the planned granular export/import module) ----

    /** Every entry across every language, for export. */
    suspend fun exportAll(): List<UserDictionaryEntry> =
        try {
            withContext(ioDispatcher) { dao.getAll() }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "UserDictionaryRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "exportAll")
            )
            emptyList()
        }

    private companion object {
        /** Explicitly-added/registered entries seed here — at or above the "≥2 → top candidate" threshold. */
        const val ADDED_SEED_FREQUENCY = 2
    }
}
