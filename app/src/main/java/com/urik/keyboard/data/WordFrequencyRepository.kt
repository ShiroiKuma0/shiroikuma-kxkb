package com.urik.keyboard.data

import androidx.room.withTransaction
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.UserWordBigramDao
import com.urik.keyboard.data.database.UserWordFrequencyDao
import com.urik.keyboard.service.WordNormalizer
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class PendingFrequencyUpdate(
    val languageTag: String,
    val wordNormalized: String,
    var incrementCount: Int = 1
)

private data class PendingBigramUpdate(
    val languageTag: String,
    val wordANormalized: String,
    val wordBNormalized: String,
    var incrementCount: Int = 1
)

@Singleton
class WordFrequencyRepository
@Inject
constructor(
    private val database: KeyboardDatabase,
    private val userWordFrequencyDao: UserWordFrequencyDao,
    private val userWordBigramDao: UserWordBigramDao,
    private val wordNormalizer: WordNormalizer,
    cacheMemoryManager: CacheMemoryManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val frequencyCache: ManagedCache<String, Int> =
        cacheMemoryManager.createCache(
            name = "user_word_frequency_cache",
            maxSize = USER_FREQUENCY_CACHE_SIZE
        )

    private val bigramCache: ManagedCache<String, LinkedHashSet<String>> =
        cacheMemoryManager.createCache(
            name = "user_bigram_cache",
            maxSize = BIGRAM_CACHE_SIZE
        )

    @Volatile
    private var topBigramsCache = ConcurrentHashMap<String, Map<String, LinkedHashSet<String>>>()

    // The most-typed words per language, as the database holds them. Read on every keystroke by the
    // typed-history predictor, so it is cached and refreshed only when a flush actually changes the table;
    // words still waiting in [pendingFrequencyUpdates] are merged in at read time, never cached, so a word
    // typed for the FIRST time is predictable immediately rather than after the write debounce.
    @Volatile
    private var topWordsCache = ConcurrentHashMap<String, List<Pair<String, Int>>>()

    private val writeScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var frequencyFlushJob: Job? = null
    private var bigramFlushJob: Job? = null
    private val frequencyWriteMutex = Mutex()
    private val bigramWriteMutex = Mutex()
    private val pendingFrequencyUpdates = ConcurrentHashMap<String, PendingFrequencyUpdate>()
    private val pendingBigramUpdates = ConcurrentHashMap<String, PendingBigramUpdate>()
    private val flushCount =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    private fun normalizeWord(word: String, languageTag: String): String = wordNormalizer.normalize(word, languageTag)

    private fun buildCacheKey(languageTag: String, normalizedWord: String): String = "${languageTag}_$normalizedWord"

    /** Increments typed but not yet written out — part of your history the moment the word is committed. */
    private fun pendingDelta(cacheKey: String): Int = pendingFrequencyUpdates[cacheKey]?.incrementCount ?: 0

    fun incrementFrequency(word: String, languageTag: String): Result<Unit> {
        if (word.isBlank()) {
            return Result.success(Unit)
        }

        val normalized = normalizeWord(word, languageTag)
        val cacheKey = buildCacheKey(languageTag, normalized)

        // The cache holds the count as the DATABASE has it; increments ride in [pendingFrequencyUpdates]
        // until the flush and are added by every read. Writing an optimistic count here used to OVERWRITE a
        // real standing whenever the entry had been evicted — "typed fifty times" came back as "typed once",
        // and the typed-history predictor ranks candidates by exactly this number.

        pendingFrequencyUpdates.merge(
            cacheKey,
            PendingFrequencyUpdate(languageTag, normalized)
        ) { existing, _ -> existing.apply { incrementCount++ } }

        scheduleFrequencyFlush()

        return Result.success(Unit)
    }

    private fun scheduleFrequencyFlush() {
        frequencyFlushJob?.cancel()
        frequencyFlushJob =
            writeScope.launch {
                delay(WRITE_DEBOUNCE_MS)
                flushPendingFrequencyUpdates()
            }
    }

    private suspend fun flushPendingFrequencyUpdates() {
        frequencyWriteMutex.withLock {
            if (pendingFrequencyUpdates.isEmpty()) return

            val updates = pendingFrequencyUpdates.toMap()
            pendingFrequencyUpdates.clear()

            val timestamp = System.currentTimeMillis()

            try {
                database.withTransaction {
                    updates.values.forEach { update ->
                        userWordFrequencyDao.incrementFrequencyBy(
                            languageTag = update.languageTag,
                            wordNormalized = update.wordNormalized,
                            amount = update.incrementCount,
                            lastUsed = timestamp
                        )
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "WordFrequencyRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "flushPendingFrequencyUpdates")
                )
            }
            // The stored counts just moved, so every cached count it touched is stale. The most-typed list
            // is NOT dropped — it is moved by the same deltas, because reloading it is a 2 000-row query and
            // the next thing to ask for it is a keystroke.
            updates.keys.forEach { frequencyCache.invalidate(it) }
            applyFlushedToTopWords(updates)
        }
        pruneIfNeeded()
    }

    suspend fun getFrequency(word: String, languageTag: String): Int = withContext(defaultDispatcher) {
        try {
            if (word.isBlank()) {
                return@withContext 0
            }

            val normalized = normalizeWord(word, languageTag)
            val cacheKey = buildCacheKey(languageTag, normalized)

            frequencyCache.getIfPresent(cacheKey)?.let {
                return@withContext it + pendingDelta(cacheKey)
            }

            val frequency =
                withContext(ioDispatcher) {
                    userWordFrequencyDao.findWord(languageTag, normalized)?.frequency ?: 0
                }

            frequencyCache.put(cacheKey, frequency)

            return@withContext frequency + pendingDelta(cacheKey)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "WordFrequencyRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "getFrequency")
            )
            return@withContext 0
        }
    }

    suspend fun getFrequencies(words: List<String>, languageTag: String): Map<String, Int> =
        withContext(defaultDispatcher) {
            try {
                if (words.isEmpty()) {
                    return@withContext emptyMap()
                }

                val results = mutableMapOf<String, Int>()
                val wordsToFetch = mutableListOf<String>()

                for (word in words) {
                    if (word.isBlank()) {
                        results[word] = 0
                        continue
                    }

                    val normalized = normalizeWord(word, languageTag)
                    val cacheKey = buildCacheKey(languageTag, normalized)

                    frequencyCache.getIfPresent(cacheKey)?.let { cached ->
                        results[word] = cached + pendingDelta(cacheKey)
                    } ?: run {
                        wordsToFetch.add(normalized)
                    }
                }

                if (wordsToFetch.isNotEmpty()) {
                    val fetched =
                        withContext(ioDispatcher) {
                            userWordFrequencyDao.findWords(languageTag, wordsToFetch)
                        }

                    val fetchedMap = fetched.associate { it.wordNormalized to it.frequency }

                    for (word in words) {
                        if (word in results) continue

                        val normalized = normalizeWord(word, languageTag)
                        val frequency = fetchedMap[normalized] ?: 0
                        val cacheKey = buildCacheKey(languageTag, normalized)

                        frequencyCache.put(cacheKey, frequency)
                        results[word] = frequency + pendingDelta(cacheKey)
                    }
                }

                return@withContext results
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "WordFrequencyRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "getFrequencies")
                )
                return@withContext words.associateWith { 0 }
            }
        }

    /**
     * Your most-typed words in [languageTag], highest count first — the history the cluster bar predicts
     * from. The stored list is read once and then carried forward by the flush; words still pending are
     * merged in here, so a word committed a moment ago already predicts. A word that stands outside the
     * stored top [limit] is counted from its pending increments alone until the list is next loaded, which
     * only ever understates a word you rarely type.
     */
    suspend fun topWords(languageTag: String, limit: Int): List<Pair<String, Int>> = withContext(defaultDispatcher) {
            try {
                val stored =
                    topWordsCache[languageTag] ?: withContext(ioDispatcher) {
                        userWordFrequencyDao
                            .getMostFrequentWords(languageTag, limit)
                            .map { it.wordNormalized to it.frequency }
                    }.also { topWordsCache[languageTag] = it }

                val pending = pendingFrequencyUpdates.values.filter { it.languageTag == languageTag }
                if (pending.isEmpty()) return@withContext stored

                val merged = LinkedHashMap<String, Int>(stored.size + pending.size)
                stored.forEach { (word, count) -> merged[word] = count }
                pending.forEach { update ->
                    merged[update.wordNormalized] = (merged[update.wordNormalized] ?: 0) + update.incrementCount
                }
                merged.map { it.key to it.value }.sortedByDescending { it.second }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "WordFrequencyRepository",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "topWords")
                )
                emptyList()
            }
        }

    /**
     * Forget that [word] was ever typed in [languageTag] — what a long-press on a candidate does to a word
     * that leads only because of your own history. Returns true when there was something to forget. The
     * pending increments go FIRST: a word typed a moment ago is still waiting to be written, and the next
     * flush would otherwise put the row straight back.
     */
    suspend fun forget(word: String, languageTag: String): Boolean = withContext(ioDispatcher) {
        if (word.isBlank()) return@withContext false
        try {
            val normalized = normalizeWord(word, languageTag)
            val cacheKey = buildCacheKey(languageTag, normalized)

            val hadPending = pendingFrequencyUpdates.remove(cacheKey) != null
            frequencyCache.invalidate(cacheKey)
            topWordsCache[languageTag]?.let { cached ->
                topWordsCache[languageTag] = cached.filterNot { it.first == normalized }
            }

            val deleted = userWordFrequencyDao.deleteForLanguage(languageTag, normalized)
            hadPending || deleted > 0
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "WordFrequencyRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "forget")
            )
            false
        }
    }

    /** Carry one flush's increments into the cached most-typed list, keeping it in step with the table. */
    private fun applyFlushedToTopWords(updates: Map<String, PendingFrequencyUpdate>) {
        if (topWordsCache.isEmpty()) return
        updates.values.groupBy { it.languageTag }.forEach { (languageTag, written) ->
            val cached = topWordsCache[languageTag] ?: return@forEach
            val merged = LinkedHashMap<String, Int>(cached.size + written.size)
            cached.forEach { (word, count) -> merged[word] = count }
            written.forEach { update ->
                merged[update.wordNormalized] = (merged[update.wordNormalized] ?: 0) + update.incrementCount
            }
            topWordsCache[languageTag] = merged.map { it.key to it.value }
        }
    }

    fun clearCache() {
        frequencyFlushJob?.cancel()
        bigramFlushJob?.cancel()

        writeScope.launch {
            flushPendingFrequencyUpdates()
            flushPendingBigramUpdates()
        }

        frequencyCache.invalidateAll()
        bigramCache.invalidateAll()
        topBigramsCache.clear()
        topWordsCache.clear()
    }

    fun recordBigram(wordA: String, wordB: String, languageTag: String): Result<Unit> {
        if (wordA.isBlank() || wordB.isBlank()) {
            return Result.success(Unit)
        }

        val normalizedA = normalizeWord(wordA, languageTag)
        val normalizedB = normalizeWord(wordB, languageTag)
        val cacheKey = buildBigramCacheKey(languageTag, normalizedA)
        val bigramKey = "${languageTag}_${normalizedA}_$normalizedB"

        bigramCache.invalidate(cacheKey)
        topBigramsCache.remove(languageTag)

        pendingBigramUpdates.merge(
            bigramKey,
            PendingBigramUpdate(languageTag, normalizedA, normalizedB)
        ) { existing, _ -> existing.apply { incrementCount++ } }

        scheduleBigramFlush()

        return Result.success(Unit)
    }

    private fun scheduleBigramFlush() {
        bigramFlushJob?.cancel()
        bigramFlushJob =
            writeScope.launch {
                delay(WRITE_DEBOUNCE_MS)
                flushPendingBigramUpdates()
            }
    }

    private suspend fun flushPendingBigramUpdates() {
        bigramWriteMutex.withLock {
            if (pendingBigramUpdates.isEmpty()) return

            val updates = pendingBigramUpdates.toMap()
            pendingBigramUpdates.clear()

            val timestamp = System.currentTimeMillis()

            try {
                database.withTransaction {
                    updates.values.forEach { update ->
                        userWordBigramDao.incrementBigramBy(
                            languageTag = update.languageTag,
                            wordANormalized = update.wordANormalized,
                            wordBNormalized = update.wordBNormalized,
                            amount = update.incrementCount,
                            lastUsed = timestamp
                        )
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "WordFrequencyRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "flushPendingBigramUpdates")
                )
            }
        }
    }

    suspend fun getBigramPredictions(
        wordA: String,
        languageTag: String,
        limit: Int = MAX_BIGRAM_PREDICTIONS
    ): Set<String> = withContext(defaultDispatcher) {
        try {
            if (wordA.isBlank()) {
                return@withContext emptySet()
            }

            val normalizedA = normalizeWord(wordA, languageTag)
            val cacheKey = buildBigramCacheKey(languageTag, normalizedA)

            bigramCache.getIfPresent(cacheKey)?.let { cached ->
                return@withContext if (cached.size <= limit) {
                    cached
                } else {
                    LinkedHashSet(cached.take(limit))
                }
            }

            topBigramsCache[languageTag]?.get(normalizedA)?.let { cached ->
                return@withContext if (cached.size <= limit) {
                    cached
                } else {
                    LinkedHashSet(cached.take(limit))
                }
            }

            val predictions =
                withContext(ioDispatcher) {
                    userWordBigramDao.getPredictions(languageTag, normalizedA, limit)
                }

            val predictionsSet = LinkedHashSet(predictions)
            bigramCache.put(cacheKey, predictionsSet)

            return@withContext predictionsSet
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "WordFrequencyRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "getBigramPredictions")
            )
            return@withContext emptySet()
        }
    }

    suspend fun preloadTopBigrams(languageTag: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val topBigrams = userWordBigramDao.getTopBigrams(languageTag, BIGRAM_CACHE_SIZE)

            val bigramMap =
                topBigrams
                    .groupBy { it.wordANormalized }
                    .mapValues { (_, bigrams) ->
                        bigrams
                            .sortedByDescending { it.frequency }
                            .mapTo(LinkedHashSet()) { it.wordBNormalized }
                    }

            topBigramsCache[languageTag] = bigramMap

            Result.success(Unit)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "WordFrequencyRepository",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "preloadTopBigrams")
            )
            Result.failure(e)
        }
    }

    private suspend fun pruneIfNeeded() {
        val count = flushCount.incrementAndGet()
        if (count % PRUNING_INTERVAL_FLUSHES != 0) return

        withContext(ioDispatcher) {
            try {
                val cutoff = System.currentTimeMillis() - FREQUENCY_PRUNING_CUTOFF_MS
                userWordFrequencyDao.pruneStaleEntries(cutoff)
                userWordFrequencyDao.enforceMaxRows(MAX_FREQUENCY_ROWS)
                userWordBigramDao.pruneStaleEntries(cutoff)
                userWordBigramDao.enforceMaxRows(MAX_BIGRAM_ROWS)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "WordFrequencyRepository",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "pruneIfNeeded")
                )
            }
        }
    }

    private fun buildBigramCacheKey(languageTag: String, normalizedWordA: String): String =
        "bigram_${languageTag}_$normalizedWordA"

    private companion object {
        const val WRITE_DEBOUNCE_MS = 300L
        const val USER_FREQUENCY_CACHE_SIZE = 2000
        const val BIGRAM_CACHE_SIZE = 100
        const val MAX_BIGRAM_PREDICTIONS = 3
        const val PRUNING_INTERVAL_FLUSHES = 50
        const val FREQUENCY_PRUNING_CUTOFF_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_FREQUENCY_ROWS = 10_000
        const val MAX_BIGRAM_ROWS = 50_000
    }
}
