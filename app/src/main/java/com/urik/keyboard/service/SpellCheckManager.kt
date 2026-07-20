@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.content.Context
import com.urik.keyboard.dictionary.BareFormRemovelist
import com.urik.keyboard.dictionary.LevenshteinAutomaton
import com.urik.keyboard.dictionary.UrikDictionary
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.utils.CacheMemoryManager
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.ManagedCache
import com.urik.keyboard.utils.MemoryPressureSubscriber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spelling suggestion with confidence score.
 *
 * @property source "learned" (user data), "dictionary" (built-in), or "completion" (predictive)
 */
data class SpellingSuggestion(
    val word: String,
    val confidence: Double,
    val ranking: Int,
    val source: String = "unknown",
    val preserveCase: Boolean = false
)

/**
 * Spell checking and suggestion generation using URIK dictionary format.
 */
@Singleton
class SpellCheckManager
@Inject
constructor(
    private val context: Context,
    private val languageManager: LanguageManager,
    private val wordLearningEngine: WordLearningEngine,
    private val wordFrequencyRepository: com.urik.keyboard.data.WordFrequencyRepository,
    private val wordNormalizer: WordNormalizer,
    cacheMemoryManager: CacheMemoryManager,
    private val blacklistRepository: BlacklistRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fatFingerExpander: FatFingerExpander = FatFingerExpander(),
    // The unified per-language user dictionary (explicit words + shortcuts). Nullable so the many direct
    // test constructions stay valid; the real instance is supplied by the Hilt provider.
    private val userDictionaryRepository: com.urik.keyboard.data.UserDictionaryRepository? = null
) : MemoryPressureSubscriber {
    private val initializationComplete = CompletableDeferred<Boolean>()
    private var initializationJob: Job? = null
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val urikDictionaries = ConcurrentHashMap<String, UrikDictionary>()

    @Volatile private var currentLanguage: String = "en"

    @Volatile private var cachedLocale: Locale = Locale.forLanguageTag("en")

    private val suggestionCache: ManagedCache<String, List<SpellingSuggestion>> =
        cacheMemoryManager.createCache(
            name = "spell_suggestions",
            maxSize = SUGGESTION_CACHE_SIZE
        )

    private val dictionaryCache: ManagedCache<String, Boolean> =
        cacheMemoryManager.createCache(
            name = "dictionary_cache",
            maxSize = DICTIONARY_CACHE_SIZE
        )

    private val blacklistedWords = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var isInitialized = false

    @Volatile
    var isDegradedMode: Boolean = false
        private set

    @Volatile
    private var cachedKeyPositions = emptyMap<Char, android.graphics.PointF>()

    @Volatile
    private var cachedAverageKeySpacing = 0.0

    @Volatile
    private var cachedAdjacentKeyMap = emptyMap<Char, Set<Char>>()

    init {
        cacheMemoryManager.registerPressureSubscriber(this)

        initializationJob =
            initScope.launch {
                val success =
                    try {
                        withContext(ioDispatcher) {
                            loadBlacklist()
                            initializeUrik()
                        }
                        true
                    } catch (e: Exception) {
                        ErrorLogger.logException(
                            component = "SpellCheckManager",
                            severity = ErrorLogger.Severity.HIGH,
                            exception = e,
                            context = mapOf("phase" to "initialization")
                        )
                        false
                    }
                if (!success) {
                    isDegradedMode = true
                }
                initializationComplete.complete(success)
            }

        initScope.launch {
            languageManager.activeLanguages.collect { newLanguages ->
                if (isInitialized) {
                    newLanguages.forEach { languageCode ->
                        withContext(ioDispatcher) {
                            preloadLanguage(languageCode)
                        }
                    }
                }
            }
        }

        initScope.launch {
            languageManager.effectiveDictionaryLanguages.collect {
                if (isInitialized) {
                    suggestionCache.invalidateAll()
                    dictionaryCache.invalidateAll()
                }
            }
        }

        initScope.launch {
            languageManager.keyPositions.collect { positions ->
                cachedKeyPositions = positions
                val avgSpacing =
                    if (positions.size >= 2) {
                        calculateAverageKeySpacing(positions)
                    } else {
                        0.0
                    }
                cachedAverageKeySpacing = avgSpacing
                cachedAdjacentKeyMap =
                    if (positions.isNotEmpty() && avgSpacing > 0.0) {
                        fatFingerExpander.buildAdjacentKeyMap(positions, avgSpacing)
                    } else {
                        emptyMap()
                    }
            }
        }
    }

    private suspend fun ensureInitialized(): Boolean = withTimeoutOrNull(INITIALIZATION_TIMEOUT_MS) {
        initializationComplete.await()
    } ?: run {
        isDegradedMode = true
        ErrorLogger.logException(
            component = "SpellCheckManager",
            severity = ErrorLogger.Severity.CRITICAL,
            exception = TimeoutException("Initialization timeout after ${INITIALIZATION_TIMEOUT_MS}ms"),
            context = mapOf("phase" to "ensureInitialized")
        )
        false
    }

    private suspend fun loadBlacklist() {
        blacklistedWords.addAll(blacklistRepository.getAll())
    }

    private fun initializeUrik() {
        currentLanguage = getCurrentLanguage()
        val dict = loadUrikDictionary(currentLanguage)
        if (dict != null) {
            urikDictionaries[currentLanguage] = dict
            isInitialized = true
        } else {
            error("Failed to load URIK dictionary for $currentLanguage")
        }
    }

    private fun preloadLanguage(languageCode: String) {
        if (languageCode !in KeyboardSettings.SUPPORTED_LANGUAGES) return
        if (urikDictionaries[languageCode] == null) {
            loadUrikDictionary(languageCode)?.let { urikDictionaries[languageCode] = it }
        }
    }

    private fun loadUrikDictionary(languageCode: String): UrikDictionary? = try {
        val stream = context.assets.open("dictionaries/$languageCode.urik")
        // Removed words = the hand-curated bare-form list + (if present) the generated `<lang>.removed`
        // cross-language pollution list, which strips foreign words the corpus-built dictionary inherited
        // (e.g. English "bad/bed/bag" inside the Czech dictionary). Both are honoured at lookup/candidate time.
        val removed = BareFormRemovelist.forLanguage(languageCode) + loadRemovedWords(languageCode)
        UrikDictionary(stream, removed)
    } catch (_: Exception) {
        null
    }

    /** The generated cross-language pollution list `dictionaries/<lang>.removed` (lowercase, one per line). */
    private fun loadRemovedWords(languageCode: String): Set<String> = try {
        context.assets.open("dictionaries/$languageCode.removed").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        }
    } catch (_: Exception) {
        emptySet()
    }

    private fun getUrikDictionary(languageCode: String): UrikDictionary? =
        urikDictionaries[languageCode] ?: loadUrikDictionary(languageCode)?.also {
            urikDictionaries[languageCode] = it
        }

    fun getDictFrequency(word: String, languageCode: String? = null): Long {
        val lang = languageCode ?: currentLanguage
        return getUrikDictionary(lang)?.getFrequency(word.lowercase().trim()) ?: 0L
    }

    suspend fun getUserFrequency(word: String, languageCode: String? = null): Int {
        val lang = languageCode ?: currentLanguage
        val normalized = word.lowercase().trim()
        return try {
            wordFrequencyRepository.getFrequency(normalized, lang)
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Lookup order: learned words → cache → URIK dictionary (edit distance = 0).
     * Word is normalized (lowercase, trimmed) before checking.
     */
    suspend fun isWordInDictionary(word: String): Boolean = withContext(Dispatchers.Default) {
        try {
            if (!isValidInput(word)) {
                return@withContext false
            }

            if (!ensureInitialized()) {
                return@withContext false
            }

            val effectiveLanguages = suggestionLanguages()
            val locale = getLocaleForLanguage()
            val normalizedWord = word.lowercase(locale).trim()

            for (lang in effectiveLanguages) {
                if (isWordInDictionary(normalizedWord, lang)) {
                    return@withContext true
                }
            }

            return@withContext false
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "isWordInDictionary")
            )
            return@withContext false
        }
    }

    private suspend fun isWordInDictionary(normalizedWord: String, languageCode: String): Boolean {
        val isLearned = wordLearningEngine.isWordLearned(normalizedWord)
        if (isLearned) {
            dictionaryCache.put(buildCacheKey(normalizedWord, languageCode), true)
            return true
        }

        val cacheKey = buildCacheKey(normalizedWord, languageCode)
        dictionaryCache.getIfPresent(cacheKey)?.let { return it }

        val dict = getUrikDictionary(languageCode)
        if (dict != null && dict.lookup(normalizedWord)) {
            dictionaryCache.put(cacheKey, true)
            return true
        }

        val clitic = isCliticFormValid(normalizedWord, languageCode)
        dictionaryCache.put(cacheKey, clitic)
        return clitic
    }

    private suspend fun isCliticFormValid(normalizedWord: String, languageCode: String): Boolean {
        val apostropheIndex = normalizedWord.indexOf('\'')
        if (apostropheIndex <= 0 || apostropheIndex >= normalizedWord.length - 1) {
            return false
        }

        val prefix = normalizedWord.substring(0, apostropheIndex + 1)
        val suffix = normalizedWord.substring(apostropheIndex + 1)

        if (suffix.isBlank()) return false

        val dict = getUrikDictionary(languageCode)
        val prefixValid =
            wordLearningEngine.isWordLearned(prefix) ||
                dict != null &&
                dict.lookup(prefix)

        if (!prefixValid) return false

        val suffixValid =
            wordLearningEngine.isWordLearned(suffix) ||
                dict != null &&
                dict.lookup(suffix)

        if (suffixValid) {
            val cacheKey = buildCacheKey(normalizedWord, languageCode)
            dictionaryCache.put(cacheKey, true)
        }

        return suffixValid
    }

    fun getDominantContractionForm(normalizedWord: String, languageCode: String = currentLanguage): String? {
        val canonical = wordNormalizer.canonicalizeApostrophes(normalizedWord)
        if (canonical.contains('\'')) return null

        val dict = getUrikDictionary(languageCode) ?: return null
        val wordFreq = dict.getFrequency(canonical)
        if (wordFreq == 0L) return null

        for (i in 1 until canonical.length) {
            val candidate = canonical.substring(0, i) + "'" + canonical.substring(i)
            val contractionFreq = dict.getFrequency(candidate)
            if (contractionFreq > 0L && contractionFreq >= wordFreq * CONTRACTION_DOMINANCE_RATIO) {
                return candidate
            }
        }
        return null
    }

    suspend fun hasDominantContractionForm(normalizedWord: String, languageCode: String? = null): Boolean {
        val lang = languageCode ?: getCurrentLanguage()
        val canonical = wordNormalizer.canonicalizeApostrophes(normalizedWord)
        if (canonical.contains('\'')) return false

        val dict = getUrikDictionary(lang) ?: return false
        val dictFreq = dict.getFrequency(canonical)
        if (dictFreq == 0L) return false

        val userFreq = try {
            wordFrequencyRepository.getFrequency(canonical, lang)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "hasDominantContractionForm")
            )
            0
        }

        val effectiveBaseFreq = dictFreq + userFreq * USER_FREQ_CONTRACTION_WEIGHT

        for (i in 1 until canonical.length) {
            val candidate = canonical.substring(0, i) + "'" + canonical.substring(i)
            val contractionFreq = dict.getFrequency(candidate)
            if (contractionFreq > 0L && contractionFreq >= effectiveBaseFreq * CONTRACTION_DOMINANCE_RATIO) {
                return true
            }
        }
        return false
    }

    /** Uses single batch query to WordLearningEngine for learned words. */
    suspend fun areWordsInDictionary(words: List<String>): Map<String, Boolean> = withContext(Dispatchers.Default) {
        if (words.isEmpty()) {
            return@withContext emptyMap()
        }

        if (!ensureInitialized()) {
            return@withContext words.associateWith { false }
        }

        val results = mutableMapOf<String, Boolean>()
        val effectiveLanguages = suggestionLanguages()
        val locale = getLocaleForLanguage()

        val wordsToProcess = mutableListOf<Pair<String, String>>()

        for (word in words) {
            if (!isValidInput(word)) {
                results[word] = false
                continue
            }

            val normalizedWord = word.lowercase(locale).trim()

            var foundInCache = false
            for (lang in effectiveLanguages) {
                val cacheKey = buildCacheKey(normalizedWord, lang)
                dictionaryCache.getIfPresent(cacheKey)?.let { cached ->
                    if (cached) {
                        results[word] = true
                        foundInCache = true
                    }
                }
                if (foundInCache) break
            }
            if (foundInCache) continue

            wordsToProcess.add(word to normalizedWord)
        }

        val learnedStatus =
            if (wordsToProcess.isNotEmpty()) {
                try {
                    val normalizedWords = wordsToProcess.map { it.second }
                    wordLearningEngine.areWordsLearned(normalizedWords)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "SpellCheckManager",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "areWordsInDictionary_learnedCheck")
                    )
                    emptyMap()
                }
            } else {
                emptyMap()
            }

        for ((originalWord, normalizedWord) in wordsToProcess) {
            val isLearned = learnedStatus[normalizedWord] ?: false
            if (isLearned) {
                for (lang in effectiveLanguages) {
                    dictionaryCache.put(buildCacheKey(normalizedWord, lang), true)
                }
                results[originalWord] = true
                continue
            }

            var foundInDict = false
            for (lang in effectiveLanguages) {
                val dict = getUrikDictionary(lang)
                if (dict != null) {
                    val isInDictionary = dict.lookup(normalizedWord)
                    dictionaryCache.put(buildCacheKey(normalizedWord, lang), isInDictionary)
                    if (isInDictionary) {
                        foundInDict = true
                        break
                    }
                }
            }
            results[originalWord] = foundInDict
        }

        return@withContext results
    }

    /** Simpler API wrapping [getSpellingSuggestionsWithConfidence]. */
    suspend fun generateSuggestions(word: String, maxSuggestions: Int = 3): List<String> {
        return try {
            if (!ensureInitialized()) {
                return emptyList()
            }

            val suggestions = getSpellingSuggestionsWithConfidence(word)
            suggestions
                .sortedByDescending { it.confidence }
                .take(maxSuggestions)
                .map { it.word }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "generateSuggestions")
            )
            emptyList()
        }
    }

    /**
     * Combines learned words + dictionary corrections + prefix completions, ranked by confidence.
     * Learned words boosted by frequency, corrections penalized by edit distance, completions by prefix match.
     * Cached (500 entries, LRU).
     */
    suspend fun getSpellingSuggestionsWithConfidence(word: String): List<SpellingSuggestion> =
        withContext(Dispatchers.Default) {
            try {
                if (!isValidInput(word)) {
                    return@withContext emptyList()
                }

                if (!ensureInitialized()) {
                    return@withContext emptyList()
                }

                val effectiveLanguages = suggestionLanguages()
                val locale = getLocaleForLanguage()
                val normalizedWord = word.lowercase(locale).trim()

                return@withContext getSpellingSuggestions(normalizedWord, effectiveLanguages)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SpellCheckManager",
                    severity = ErrorLogger.Severity.HIGH,
                    exception = e,
                    context = mapOf("operation" to "getSpellingSuggestionsWithConfidence")
                )
                return@withContext emptyList()
            }
        }

    private suspend fun getSpellingSuggestions(
        normalizedWord: String,
        activeLanguages: List<String>
    ): List<SpellingSuggestion> {
        val cacheKey = buildCacheKey(normalizedWord, activeLanguages.sorted().joinToString(","))

        suggestionCache.getIfPresent(cacheKey)?.let { cached ->
            return cached
        }

        val allSuggestions = requestCombinedSuggestions(normalizedWord, activeLanguages)

        if (allSuggestions.isNotEmpty()) {
            suggestionCache.put(cacheKey, allSuggestions)
        }

        return allSuggestions
    }

    private suspend fun requestCombinedSuggestions(
        normalizedWord: String,
        activeLanguages: List<String>
    ): List<SpellingSuggestion> = coroutineScope {
        try {
            val effectiveLanguage = getCurrentLanguage()
            val allLanguageSuggestions =
                activeLanguages
                    .map { lang ->
                        async(Dispatchers.Default) {
                            querySingleLanguage(normalizedWord, lang)
                        }
                    }.awaitAll()
                    .flatten()

            val cap = if (clusterActive) CLUSTER_BAR_POOL else MAX_SUGGESTIONS
            mergeAndRankSuggestions(allLanguageSuggestions, cap, effectiveLanguage)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "requestCombinedSuggestions")
            )
            emptyList()
        }
    }

    private suspend fun querySingleLanguage(normalizedWord: String, languageCode: String): List<SpellingSuggestion> {
        try {
            val seenWords = mutableSetOf<String>()
            val allSuggestions = mutableListOf<SpellingSuggestion>()
            // The user's own dictionary first — it outranks everything (see queryUserDictionarySuggestions).
            allSuggestions += queryUserDictionarySuggestions(normalizedWord, languageCode, seenWords)
            // Then your own TYPED words on cluster layouts, frequency-ranked (cluster-only; see the method).
            allSuggestions += queryLearnedHeavySuggestions(normalizedWord, languageCode, seenWords)
            // Cluster prediction next so it owns the words it produces (highest, frequency-ranked).
            allSuggestions += queryClusterSuggestions(normalizedWord, languageCode, seenWords)
            allSuggestions += queryLearnedSuggestions(normalizedWord, languageCode, seenWords)
            allSuggestions += queryCompletionSuggestions(normalizedWord, languageCode, seenWords)
            allSuggestions += queryUrikSuggestions(normalizedWord, languageCode, seenWords)
            return allSuggestions.sortedByDescending { it.confidence }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "querySingleLanguage")
            )
            return emptyList()
        }
    }

    private suspend fun queryLearnedSuggestions(
        normalizedWord: String,
        languageCode: String,
        seenWords: MutableSet<String>
    ): List<SpellingSuggestion> {
        try {
            val result = mutableListOf<SpellingSuggestion>()
            val learnedSuggestions =
                wordLearningEngine.getSimilarLearnedWordsWithFrequency(
                    normalizedWord,
                    languageCode,
                    maxResults = 5
                )
            learnedSuggestions
                .filter { (word, _) -> !isWordBlacklisted(word) }
                .forEachIndexed { index, (word, frequency) ->
                    val isContraction =
                        com.urik.keyboard.utils.TextMatchingUtils
                            .isContractionSuggestion(normalizedWord, word)
                    val confidence =
                        if (isContraction) {
                            CONTRACTION_GUARANTEED_CONFIDENCE
                        } else {
                            val frequencyBoost = calculateFrequencyBoost(frequency)
                            val spatialScore = if (languageCode == "ja") {
                                0.0
                            } else {
                                calculateFullWordSpatialScore(normalizedWord, word.lowercase())
                            }
                            val hasSpatialData = languageCode != "ja" && cachedKeyPositions.isNotEmpty()
                            val effectiveBase = if (hasSpatialData && spatialScore < SPATIAL_GATE_THRESHOLD) {
                                LEARNED_WORD_BASE_CONFIDENCE * (spatialScore / SPATIAL_GATE_THRESHOLD)
                            } else {
                                LEARNED_WORD_BASE_CONFIDENCE
                            }
                            val baseConfidence = effectiveBase - index * 0.02
                            (baseConfidence + spatialScore * LEARNED_SPATIAL_WEIGHT + frequencyBoost).coerceIn(
                                LEARNED_WORD_CONFIDENCE_MIN,
                                LEARNED_WORD_CONFIDENCE_MAX
                            )
                        }
                    result.add(
                        SpellingSuggestion(
                            word = learnedSurface(word),
                            confidence = confidence,
                            ranking = index,
                            source = "learned",
                            preserveCase = word.length > 1
                        )
                    )
                    seenWords.add(word.lowercase())
                }
            return result
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "querySingleLanguage_learnedWords")
            )
            return emptyList()
        }
    }

    private suspend fun queryCompletionSuggestions(
        normalizedWord: String,
        languageCode: String,
        seenWords: MutableSet<String>
    ): List<SpellingSuggestion> {
        if (normalizedWord.length < MIN_COMPLETION_LENGTH) return emptyList()
        try {
            val result = mutableListOf<SpellingSuggestion>()
            val completions = getCompletionsForPrefix(normalizedWord, languageCode)
            val inputFreq = getUrikDictionary(languageCode)?.getFrequency(normalizedWord) ?: 0L
            val filteredCompletions =
                completions
                    .filter { (word, freq) ->
                        if (seenWords.contains(word.lowercase()) || isWordBlacklisted(word)) return@filter false
                        if (inputFreq > 0 &&
                            freq > 0 &&
                            inputFreq > freq * SUGGESTION_MAX_FREQUENCY_GAP
                        ) {
                            return@filter false
                        }
                        true
                    }
                    .take(MAX_PREFIX_COMPLETIONS)
            val userFrequencies =
                try {
                    wordFrequencyRepository.getFrequencies(
                        filteredCompletions.map { it.first },
                        languageCode
                    )
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "SpellCheckManager",
                        severity = ErrorLogger.Severity.LOW,
                        exception = e,
                        context = mapOf("operation" to "querySingleLanguage_completionFrequencies")
                    )
                    emptyMap()
                }
            filteredCompletions
                .forEachIndexed { index, (word, frequency) ->
                    val isContraction =
                        com.urik.keyboard.utils.TextMatchingUtils
                            .isContractionSuggestion(normalizedWord, word)
                    val userFrequency = userFrequencies[word] ?: 0
                    val confidence =
                        if (isContraction) {
                            CONTRACTION_GUARANTEED_CONFIDENCE
                        } else {
                            val lengthRatio = normalizedWord.length.toDouble() / word.length.toDouble()
                            val frequencyScore = ln(frequency.toDouble() + 1.0) / ln(MAX_DICT_FREQUENCY)
                            var baseConfidence =
                                COMPLETION_LENGTH_WEIGHT * lengthRatio +
                                    COMPLETION_FREQUENCY_WEIGHT * frequencyScore
                            val spatialScore = if (languageCode == "ja") {
                                0.0
                            } else {
                                calculateFullWordSpatialScore(normalizedWord, word.lowercase())
                            }
                            baseConfidence += spatialScore * COMPLETION_SPATIAL_WEIGHT
                            if (userFrequency > 0) {
                                val userFreqBoost = calculateFrequencyBoost(userFrequency)
                                baseConfidence += userFreqBoost
                            }
                            baseConfidence.coerceIn(COMPLETION_CONFIDENCE_MIN, 0.99)
                        }
                    result.add(
                        SpellingSuggestion(
                            word = word,
                            confidence = confidence,
                            ranking = index,
                            source = "completion"
                        )
                    )
                    seenWords.add(word.lowercase())
                }
            return result
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "querySingleLanguage_completions")
            )
            return emptyList()
        }
    }

    // Active layout's cluster bands: base-folded centre char -> the band's base-folded char set. Empty =
    // not a cluster layout (cluster prediction off). Set by the service on each letters-layout build.
    @Volatile
    private var clusterBands: Map<Char, Set<Char>> = emptyMap()

    /** True while a cluster layout is active — the bar then carries a larger pool ([CLUSTER_BAR_POOL]). */
    val clusterActive: Boolean get() = clusterBands.isNotEmpty()

    /** [bands] maps a cluster key's committed centre char to its full band string (e.g. 'w' -> "mwk"). */
    fun setClusterBands(bands: Map<Char, String>) {
        val folded = mutableMapOf<Char, Set<Char>>()
        for ((center, band) in bands) {
            val key = foldToBase(center) ?: continue
            val set = band.mapNotNull { foldToBase(it) }.toSet()
            if (set.size > 1) folded[key] = set // only ambiguous bands (a real cluster)
        }
        clusterBands = folded
    }

    private fun foldToBase(c: Char): Char? =
        wordNormalizer.stripDiacritics(c.toString()).lowercase().firstOrNull()

    /**
     * True when [word] contains at least one letter with an ambiguous cluster band — i.e. it was
     * typed on multi-letter cluster keys, so the raw buffer is centre-letter garbage rather than
     * the word itself. A word typed on FLAT keys (even on a layout in cluster mode) returns false.
     */
    fun hasClusterAmbiguity(word: String): Boolean =
        clusterBands.isNotEmpty() && word.any { ch -> foldToBase(ch)?.let { clusterBands[it] } != null }

    /**
     * Cluster prediction: reconstruct the per-tap allowed-sets from the typed centres (each char → its
     * cluster band, accent-folded) and enumerate dictionary words consistent with all positions, ranked by
     * frequency. The literal (the centres themselves) is just one such candidate, so the top by frequency
     * is the prediction and the literal competes for 2nd. Empty when the layout has no clusters or nothing
     * the user typed is ambiguous.
     */
    /**
     * Expanded cluster candidates for the on-demand "more candidates" pane — the same constrained DAWG walk
     * as [queryClusterSuggestions] but with a large cap, returning just the words (frequency-ranked). Run
     * only when the pane opens, so the per-keystroke path stays cheap.
     */
    fun clusterCandidatesFor(word: String, languageCode: String, maxResults: Int): List<String> {
        val bands = clusterBands
        if (bands.isEmpty() || word.isEmpty()) return emptyList()
        val folded = wordNormalizer.stripDiacritics(word).lowercase()
        val allowedSets = folded.map { ch -> bands[ch] ?: setOf(ch) }
        if (allowedSets.none { it.size > 1 }) return emptyList()
        val dict = getUrikDictionary(languageCode) ?: return emptyList()
        return dict.clusterCandidates(allowedSets, maxResults)
            .mapNotNull { (w, _) -> if (isWordBlacklisted(w)) null else w }
    }

    /**
     * The user's own dictionary (explicitly added words + shortcuts), surfaced HEAVILY and ahead of the
     * bundled dictionary, on every layout including cluster. Each entry's diacritic-folded key is matched
     * against the typed buffer — by prefix on a normal layout, and band-by-band (the same accent-folded
     * cluster bands the DAWG walk uses) on a cluster layout, so a phrase reappears from just its opening taps.
     * Ranking follows 白い熊's rule: an entry typed/added once sits near the top; used twice or more it
     * outranks even an exact dictionary match (the #1 candidate); ties break by frequency. Japanese
     * reading→surface entries are offered by the converter path, so they are skipped here.
     */
    private suspend fun queryUserDictionarySuggestions(
        normalizedWord: String,
        languageCode: String,
        seenWords: MutableSet<String>
    ): List<SpellingSuggestion> {
        val repo = userDictionaryRepository ?: return emptyList()
        if (normalizedWord.isEmpty()) return emptyList()
        return try {
            val entries = repo.matchesFor(languageCode)
            if (entries.isEmpty()) return emptyList()
            val bufFolded = wordNormalizer.stripDiacritics(normalizedWord).lowercase()
            if (bufFolded.isEmpty()) return emptyList()
            val bands = clusterBands
            val result = mutableListOf<SpellingSuggestion>()
            entries
                .asSequence()
                .filter { it.kind != com.urik.keyboard.data.database.UserDictionaryKind.JAPANESE }
                .filter { matchesTypedBuffer(it.foldedKey, bufFolded, bands) }
                .sortedByDescending { it.frequency }
                .forEach { entry ->
                    val dedupeKey = entry.value.lowercase()
                    if (dedupeKey in seenWords || isWordBlacklisted(entry.value)) return@forEach
                    seenWords.add(dedupeKey)
                    result.add(
                        SpellingSuggestion(
                            word = entry.value,
                            confidence = userDictionaryConfidence(entry.frequency),
                            ranking = 0,
                            source = "userdict",
                            preserveCase = true
                        )
                    )
                }
            result
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "queryUserDictionarySuggestions")
            )
            emptyList()
        }
    }

    /**
     * Does a user-dictionary entry (accent-folded [foldedKey]) match what the user has typed so far
     * ([bufFolded], also accent-folded)? On a normal layout this is a plain prefix test. On a cluster layout
     * each typed centre stands for its whole [bands] set, so the entry char at each position must fall in the
     * tapped key's band — a predictive, band-constrained prefix.
     */
    private fun matchesTypedBuffer(foldedKey: String, bufFolded: String, bands: Map<Char, Set<Char>>): Boolean {
        if (foldedKey.length < bufFolded.length) return false
        if (bands.isEmpty()) return foldedKey.startsWith(bufFolded)
        for (i in bufFolded.indices) {
            val allowed = bands[bufFolded[i]] ?: setOf(bufFolded[i])
            if (foldedKey[i] !in allowed) return false
        }
        return true
    }

    /**
     * Confidence for a user-dictionary candidate per 白い熊's ranking rule: used once → near the top (just
     * under an exact dictionary hit at [EXACT_MATCH_CONFIDENCE]); used twice or more → above that ceiling so
     * it lands as the #1 candidate, with higher frequency ranking higher within that top band.
     */
    /**
     * A stored single-letter learned surface is a sentence-start auto-cap artifact ("A" for Czech "a"),
     * never deliberate casing — fold it so it can't shadow the lowercase dictionary word and doesn't
     * display capitalized mid-sentence (sentence-start recasing still capitalizes it there).
     */
    private fun learnedSurface(word: String): String =
        if (word.length == 1) word.lowercase(getLocaleForLanguage()) else word

    private fun userDictionaryConfidence(frequency: Int): Double =
        if (frequency <= 1) {
            USER_DICT_NEAR_TOP_CONFIDENCE
        } else {
            (USER_DICT_TOP_BASE_CONFIDENCE + minOf(frequency, USER_DICT_FREQ_CAP) * USER_DICT_FREQ_STEP)
                .coerceAtMost(1.0)
        }

    /**
     * The user dictionary's Japanese reading→surface entries whose reading the current buffer is typing
     * toward (the typed kana is a prefix of the stored reading), highest-frequency first. The Japanese
     * candidate path surfaces these as the TOP conversions — leading the row from the very first kana, so a
     * registered word like しろいくま→白い熊 is offered as #1 the moment you type し. (Deliberately eager: the
     * user's own registrations win over the bundled conversions.) The unified replacement for the old
     * per-converter registration overlay.
     */
    suspend fun japaneseUserCandidates(reading: String): List<String> {
        val repo = userDictionaryRepository ?: return emptyList()
        if (reading.isEmpty()) return emptyList()
        return try {
            repo.matchesFor("ja")
                .asSequence()
                .filter {
                    it.kind == com.urik.keyboard.data.database.UserDictionaryKind.JAPANESE &&
                        it.matchKey.startsWith(reading)
                }
                .sortedByDescending { it.frequency }
                .map { it.value }
                .filter { !isWordBlacklisted(it) }
                .distinct()
                .toList()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "japaneseUserCandidates")
            )
            emptyList()
        }
    }

    /** Promote a just-committed word in the user dictionary of [languageTag] (its use count climbs). */
    fun recordUserDictionaryUse(languageTag: String, word: String) {
        userDictionaryRepository?.recordUse(languageTag, word)
    }

    /**
     * Your own typed words (the learned-words store) surfaced on CLUSTER layouts and ranked by how often you
     * have typed them: once → near the top, twice or more → the #1 candidate (a hair below a deliberate user-
     * dictionary entry, so a deliberate add still wins a tie). Matched band-by-band, accent-folded — exactly
     * the match the centre-letter buffer denies the similarity lookup, which is why a repeated phrase like
     * "Teď" never used to reappear here. Cluster-only: a normal layout already surfaces learned words via
     * [queryLearnedSuggestions], and elevating them to #1 there would over-trigger auto-replacement.
     */
    private suspend fun queryLearnedHeavySuggestions(
        normalizedWord: String,
        languageCode: String,
        seenWords: MutableSet<String>
    ): List<SpellingSuggestion> {
        val bands = clusterBands
        if (bands.isEmpty() || normalizedWord.isEmpty()) return emptyList()
        return try {
            val learned = wordLearningEngine.getLearnedWordsForLanguage(languageCode)
            if (learned.isEmpty()) return emptyList()
            val bufFolded = wordNormalizer.stripDiacritics(normalizedWord).lowercase()
            if (bufFolded.isEmpty()) return emptyList()
            val result = mutableListOf<SpellingSuggestion>()
            learned
                .asSequence()
                .filter { (word, _) ->
                    matchesTypedBuffer(wordNormalizer.stripDiacritics(word).lowercase(), bufFolded, bands)
                }
                .sortedByDescending { it.second }
                .take(LEARNED_HEAVY_MAX_RESULTS)
                .forEach { (word, frequency) ->
                    val dedupeKey = word.lowercase()
                    if (dedupeKey in seenWords || isWordBlacklisted(word)) return@forEach
                    seenWords.add(dedupeKey)
                    result.add(
                        SpellingSuggestion(
                            word = learnedSurface(word),
                            confidence = learnedHeavyConfidence(frequency),
                            ranking = 0,
                            source = "learned",
                            preserveCase = word.length > 1
                        )
                    )
                }
            result
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "queryLearnedHeavySuggestions")
            )
            emptyList()
        }
    }

    /** Heavy ranking for a typed word; mirrors [userDictionaryConfidence] a hair lower so a deliberate entry wins. */
    private fun learnedHeavyConfidence(frequency: Int): Double =
        if (frequency <= 1) {
            LEARNED_HEAVY_NEAR_TOP_CONFIDENCE
        } else {
            (LEARNED_HEAVY_TOP_BASE_CONFIDENCE + minOf(frequency, USER_DICT_FREQ_CAP) * USER_DICT_FREQ_STEP)
                .coerceAtMost(1.0)
        }

    private suspend fun queryClusterSuggestions(
        normalizedWord: String,
        languageCode: String,
        seenWords: MutableSet<String>
    ): List<SpellingSuggestion> {
        val bands = clusterBands
        if (bands.isEmpty() || normalizedWord.isEmpty()) return emptyList()
        val folded = wordNormalizer.stripDiacritics(normalizedWord).lowercase()
        val allowedSets = folded.map { ch -> bands[ch] ?: setOf(ch) }
        if (allowedSets.none { it.size > 1 }) return emptyList()
        val dict = getUrikDictionary(languageCode) ?: return emptyList()

        // Cluster contractions (Bug 1): the apostrophe form can't come from the DAWG (the "'" is never
        // tapped) and the centre-letter buffer never spells the bare key, so surface any contraction whose
        // bare key is consistent with the tap bands as a TOP-ranked candidate, ahead of the dict words.
        val contractions =
            if (languageCode == "en") {
                Contractions.candidatesForBands(allowedSets).mapNotNull { (value, preserveCase) ->
                    val key = value.lowercase()
                    if (key in seenWords || isWordBlacklisted(value)) return@mapNotNull null
                    seenWords.add(key)
                    SpellingSuggestion(
                        word = value,
                        confidence = CONTRACTION_GUARANTEED_CONFIDENCE,
                        ranking = 0,
                        source = "contraction",
                        preserveCase = preserveCase
                    )
                }
            } else {
                emptyList()
            }

        val pooled = dict.clusterCandidates(allowedSets, CLUSTER_BAR_POOL)
        // The centre-letter word itself must never be pool-truncated: the letters actually TAPPED
        // form a real word more often than its corpus rank suggests ("hit" on en, Czech "dít" via
        // the folded centres d-i-t, rare "luft") — yet the frequency-capped pool fills with
        // higher-ranked band combinations and completions. A singleton re-query (no bands) pulls
        // the exact centre word and its immediate completions past the cut; ranking stays natural.
        val centerMatches = dict.clusterCandidates(folded.map { setOf(it) }, CENTER_WORD_POOL)
            .filterNot { c -> pooled.any { it.first == c.first } }
        val rawDict = pooled + centerMatches
        // Your own usage dominates dictionary frequency on a cluster layout: a word you have committed climbs
        // (used once → near the top, twice or more → the #1 candidate band), so a phrase you type constantly
        // like Czech "Teď" leads the row. Without this the cluster bar ranked purely by bundled-dictionary
        // frequency and ignored how often YOU type a word. Frequencies are keyed by the layout language (the
        // same tag recordWordUsage now writes under), so they actually match.
        val userFreqs = try {
            wordFrequencyRepository.getFrequencies(rawDict.map { it.first }, languageCode)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "queryClusterSuggestions_userFreq")
            )
            emptyMap()
        }
        val dictCandidates = rawDict
            .mapNotNull { (word, freq) ->
                val key = word.lowercase()
                if (key in seenWords || isWordBlacklisted(word)) return@mapNotNull null
                seenWords.add(key)
                val freqScore = ln(freq.toDouble() + 1.0) / ln(MAX_DICT_FREQUENCY)
                val base = (0.55 + 0.44 * freqScore).coerceIn(0.0, 0.99)
                val userFreq = userFreqs[word] ?: 0
                val confidence = if (userFreq > 0) maxOf(base, learnedHeavyConfidence(userFreq)) else base
                SpellingSuggestion(word, confidence, 0, "cluster")
            }
        return contractions + dictCandidates
    }

    private suspend fun queryUrikSuggestions(
        normalizedWord: String,
        languageCode: String,
        seenWords: MutableSet<String>
    ): List<SpellingSuggestion> {
        val dict = getUrikDictionary(languageCode) ?: return emptyList()
        val adjacentKeyMap = cachedAdjacentKeyMap
        val inputAccentStripped = wordNormalizer.stripDiacritics(normalizedWord)

        val rawCandidates = dict.getCandidates(normalizedWord, MAX_EDIT_DISTANCE).toMutableList()

        if (adjacentKeyMap.isNotEmpty() &&
            languageCode != "ja" &&
            normalizedWord.length >= FAT_FINGER_MIN_WORD_LENGTH
        ) {
            val variants = fatFingerExpander.generateVariants(normalizedWord, adjacentKeyMap)
            for (variant in variants) {
                rawCandidates.addAll(dict.getCandidates(variant, MAX_EDIT_DISTANCE - 1))
            }
        }

        val auto = LevenshteinAutomaton(normalizedWord, MAX_EDIT_DISTANCE)
        val deduped = rawCandidates
            .groupBy { it.first.lowercase() }
            .mapNotNull { (_, dupes) ->
                val rep = dupes.minBy { it.second }
                val trueDistance = auto.accept(rep.first.lowercase())
                if (trueDistance < 0) null else rep.first to trueDistance
            }

        val terms = deduped.map { it.first }
        val userFrequencies = try {
            wordFrequencyRepository.getFrequencies(terms, languageCode)
        } catch (_: Exception) {
            emptyMap()
        }

        val inputFreq = dict.getFrequency(normalizedWord)

        val scored = deduped
            .filter { (term, distance) ->
                if (seenWords.contains(term.lowercase()) || isWordBlacklisted(term)) return@filter false
                if (distance == 0) return@filter false
                if (inputFreq > 0) {
                    val termFreq = dict.getFrequency(term)
                    if (termFreq > 0 && inputFreq > termFreq * SUGGESTION_MAX_FREQUENCY_GAP) return@filter false
                }
                true
            }
            .mapIndexed { index, (term, distance) ->
                val freq = dict.getFrequency(term)
                val confidence = scoreDictionaryCandidate(
                    input = normalizedWord,
                    term = term,
                    distance = distance,
                    frequency = freq,
                    userFrequency = userFrequencies[term] ?: 0,
                    languageCode = languageCode,
                    inputAccentStripped = inputAccentStripped
                )
                SpellingSuggestion(
                    word = term,
                    confidence = confidence,
                    ranking = index,
                    source = "dictionary"
                )
            }
        return scored.also { results -> seenWords.addAll(results.map { it.word.lowercase() }) }
    }

    private fun scoreDictionaryCandidate(
        input: String,
        term: String,
        distance: Int,
        frequency: Long,
        userFrequency: Int,
        languageCode: String,
        inputAccentStripped: String
    ): Double {
        if (distance == 0) return EXACT_MATCH_CONFIDENCE

        val isContraction = com.urik.keyboard.utils.TextMatchingUtils
            .isContractionSuggestion(input, term)
        if (isContraction) return CONTRACTION_GUARANTEED_CONFIDENCE

        val distanceScore = (MAX_EDIT_DISTANCE - distance).toDouble() / MAX_EDIT_DISTANCE.toDouble()
        val freqScore = ln(frequency.toDouble() + 1.0) / ln(MAX_DICT_FREQUENCY)
        var base = DICT_DISTANCE_WEIGHT * distanceScore + DICT_FREQUENCY_WEIGHT * freqScore

        if (languageCode != "ja") {
            base += calculateFullWordSpatialScore(input, term.lowercase()) * SPATIAL_PROXIMITY_WEIGHT
        }

        val strippedTerm = com.urik.keyboard.utils.TextMatchingUtils.stripWordPunctuation(term)
        if (strippedTerm != term && distance == 1) {
            base += APOSTROPHE_BOOST
        }

        val termAccentStripped = wordNormalizer.stripDiacritics(term.lowercase())
        if (inputAccentStripped == termAccentStripped && input != term.lowercase()) {
            base += DIACRITIC_PROMOTION_BOOST
        }

        if (strippedTerm.length < input.length) {
            val ratio = strippedTerm.length.toDouble() / input.length
            if (ratio < MINIMUM_LENGTH_RATIO) base *= ratio
        }

        if (userFrequency > 0) base += calculateFrequencyBoost(userFrequency)

        return base.coerceIn(DICT_CONFIDENCE_MIN, 0.99)
    }

    private fun mergeAndRankSuggestions(
        suggestions: List<SpellingSuggestion>,
        maxResults: Int,
        languageCode: String
    ): List<SpellingSuggestion> {
        val promoted = promoteContractionForms(suggestions, languageCode)

        return promoted
            .groupBy { it.word }
            .mapValues { (_, dupes) -> dupes.maxBy { it.confidence } }
            .values
            .sortedByDescending { it.confidence }
            .take(maxResults)
    }

    private fun promoteContractionForms(
        suggestions: List<SpellingSuggestion>,
        languageCode: String
    ): List<SpellingSuggestion> {
        var anyPromoted = false
        val result = suggestions.map { suggestion ->
            val contraction = getDominantContractionForm(suggestion.word, languageCode)
            if (contraction != null) {
                anyPromoted = true
                suggestion.copy(word = contraction)
            } else {
                suggestion
            }
        }
        return if (anyPromoted) result else suggestions
    }

    private fun getCompletionsForPrefix(prefix: String, languageCode: String): List<Pair<String, Long>> {
        val dict = getUrikDictionary(languageCode) ?: return emptyList()
        return dict.getWordsWithPrefix(prefix, MAX_PREFIX_COMPLETION_RESULTS)
            .filter { (word, _) -> word.length > prefix.length }
    }

    private fun isValidInput(text: String): Boolean {
        if (text.isBlank()) return false

        val hasValidChars =
            text.any { char ->
                Character.isLetter(char.code) ||
                    Character.isIdeographic(char.code) ||
                    Character.getType(char.code) == Character.OTHER_LETTER.toInt() ||
                    char == '\'' ||
                    char == '\u2019'
            }

        val codePointCount = text.codePointCount(0, text.length)
        return hasValidChars && codePointCount in 1..MAX_INPUT_CODEPOINTS
    }

    /**
     * The languages to draw suggestions from. On a CLUSTER layout this is ONLY the active LAYOUT language:
     * the cluster bands belong to that layout, so walking other languages' dictionaries with them produces
     * garbage (English "Bed/Bay/Tag" flooding a Czech bar, and a foreign word matching the bands wrongly
     * counting as "in the dictionary" — which blocks learning the real word). Off cluster, the user's normal
     * merged-dictionary set applies, so cross-language typing on a plain layout is unaffected.
     */
    private fun suggestionLanguages(): List<String> =
        if (clusterActive) {
            listOf(languageManager.currentLayoutLanguage.value.substringBefore("-"))
        } else {
            languageManager.effectiveDictionaryLanguages.value
        }

    private fun getCurrentLanguage(): String = try {
        val currentLanguage = languageManager.currentLanguage.value
        currentLanguage.split("-").first()
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SpellCheckManager",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to "getCurrentLanguage")
        )
        "en"
    }

    private fun getLocaleForLanguage(): Locale {
        val lang = languageManager.currentLanguage.value
        if (lang != currentLanguage) {
            currentLanguage = lang
            cachedLocale = try {
                Locale.forLanguageTag(lang)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "SpellCheckManager",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "getLocaleForLanguage")
                )
                Locale.forLanguageTag("en")
            }
        }
        return cachedLocale
    }

    private fun buildCacheKey(word: String, language: String): String = "${language}_$word"

    /** Call when language changed or after bulk word learning. */
    fun clearCaches() {
        suggestionCache.invalidateAll()
        dictionaryCache.invalidateAll()
    }

    /**
     * CRITICAL: Must be called after word removal from learned words.
     * Otherwise cache marks removed word as valid (stale data).
     *
     * Clears entire suggestion cache since cached prefix lists may contain this word.
     */
    fun invalidateWordCache(word: String) {
        try {
            val currentLang = getCurrentLanguage()
            val locale = getLocaleForLanguage()
            val normalizedWord = word.lowercase(locale).trim()
            val cacheKey = buildCacheKey(normalizedWord, currentLang)

            dictionaryCache.invalidate(cacheKey)
            suggestionCache.invalidateAll()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "invalidateWordCache")
            )
        }
    }

    /** Removes from WordLearningEngine and adds to blacklist in one operation. */
    suspend fun removeSuggestion(word: String): Result<Boolean> = withContext(ioDispatcher) {
        return@withContext try {
            val result = wordLearningEngine.removeWord(word)
            blacklistSuggestion(word)
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Global across all languages. Clears entire suggestion cache since cached
     * prefix lists may contain this word.
     */
    fun blacklistSuggestion(word: String) {
        try {
            val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()

            blacklistedWords.add(normalizedWord)

            val currentLang = getCurrentLanguage()
            val cacheKey = buildCacheKey(normalizedWord, currentLang)
            dictionaryCache.invalidate(cacheKey)
            suggestionCache.invalidateAll()

            initScope.launch(ioDispatcher) {
                try {
                    blacklistRepository.add(normalizedWord)
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "SpellCheckManager",
                        severity = ErrorLogger.Severity.LOW,
                        exception = e,
                        context = mapOf("operation" to "blacklistSuggestion.persist")
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "blacklistSuggestion")
            )
        }
    }

    /** Clears entire suggestion cache since cached prefix lists excluded this word. */
    fun removeFromBlacklist(word: String) {
        try {
            val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()

            val removed = blacklistedWords.remove(normalizedWord)

            if (removed) {
                val currentLang = getCurrentLanguage()
                val cacheKey = buildCacheKey(normalizedWord, currentLang)
                dictionaryCache.invalidate(cacheKey)
                suggestionCache.invalidateAll()

                initScope.launch(ioDispatcher) {
                    try {
                        blacklistRepository.remove(normalizedWord)
                    } catch (e: Exception) {
                        ErrorLogger.logException(
                            component = "SpellCheckManager",
                            severity = ErrorLogger.Severity.LOW,
                            exception = e,
                            context = mapOf("operation" to "removeFromBlacklist.persist")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.LOW,
                exception = e,
                context = mapOf("operation" to "removeFromBlacklist")
            )
        }
    }

    fun isWordBlacklisted(word: String): Boolean = try {
        val normalizedWord = word.lowercase(getLocaleForLanguage()).trim()
        normalizedWord in blacklistedWords
    } catch (e: Exception) {
        ErrorLogger.logException(
            component = "SpellCheckManager",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to "isWordBlacklisted")
        )
        false
    }

    override fun onMemoryPressure(level: Int) {
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            -> {
                clearCaches()
                val keepLanguage = currentLanguage
                val toEvict = urikDictionaries.keys.filter { it != keepLanguage }
                toEvict.forEach { lang -> urikDictionaries.remove(lang) }
            }

            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
            -> {
                val activeLangs = languageManager.activeLanguages.value.toSet()
                val toEvict = urikDictionaries.keys.filter { it !in activeLangs }
                toEvict.forEach { lang -> urikDictionaries.remove(lang) }
            }
        }
    }

    suspend fun getCommonWords(languageCode: String? = null): List<Pair<String, Long>> = withContext(ioDispatcher) {
        try {
            if (!ensureInitialized()) {
                return@withContext emptyList()
            }

            val targetLang = languageCode ?: getCurrentLanguage()
            if (targetLang !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                return@withContext emptyList()
            }

            val dict = getUrikDictionary(targetLang) ?: return@withContext emptyList()
            return@withContext dict.getWordsWithPrefix("", dict.wordCount)
                .filter { !isWordBlacklisted(it.first) }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "getCommonWords")
            )
            return@withContext emptyList()
        }
    }

    suspend fun getCommonWordsForLanguages(languages: List<String>): Map<String, Long> = withContext(ioDispatcher) {
        try {
            if (!ensureInitialized() || languages.isEmpty()) {
                return@withContext emptyMap()
            }

            val mergedWords = HashMap<String, Long>(INITIAL_WORD_LIST_CAPACITY)

            languages.forEach { lang ->
                if (lang !in KeyboardSettings.SUPPORTED_LANGUAGES) {
                    return@forEach
                }

                val dict = getUrikDictionary(lang) ?: return@forEach
                dict.getWordsWithPrefix("", dict.wordCount).forEach { (word, frequency) ->
                    if (!isWordBlacklisted(word)) {
                        mergedWords[word] = maxOf(mergedWords[word] ?: 0L, frequency)
                    }
                }
            }

            return@withContext mergedWords
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "SpellCheckManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "getCommonWordsForLanguages")
            )
            return@withContext emptyMap()
        }
    }

    private fun calculateFullWordSpatialScore(input: String, candidate: String): Double {
        val keyPositions = cachedKeyPositions
        if (keyPositions.isEmpty()) return 0.0

        val avgKeySpacing = cachedAverageKeySpacing
        if (avgKeySpacing <= 0.0) return 0.0

        val sigma = avgKeySpacing * PROXIMITY_SIGMA_MULTIPLIER
        val twoSigmaSquared = 2.0 * sigma * sigma

        if (input.length == candidate.length) {
            return scoreSameLengthAlignment(input, candidate, keyPositions, twoSigmaSquared)
        }

        if (input.length > candidate.length) {
            return scoreDifferentLengthAlignment(input, candidate, keyPositions, twoSigmaSquared)
        }

        return scorePrefixWithLookahead(input, candidate, keyPositions, twoSigmaSquared, avgKeySpacing)
    }

    private fun scorePrefixWithLookahead(
        input: String,
        candidate: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double,
        avgKeySpacing: Double
    ): Double {
        if (input.isEmpty()) return 0.0

        val lengthDiff = candidate.length - input.length
        val typedScore = if (lengthDiff <= 2) {
            maxOf(
                scoreDifferentLengthAlignment(input, candidate, keyPositions, twoSigmaSquared),
                scoreDirectPrefix(input, candidate, keyPositions, twoSigmaSquared)
            )
        } else {
            scoreDirectPrefix(input, candidate, keyPositions, twoSigmaSquared)
        }

        val lookaheadSigma = avgKeySpacing * LOOKAHEAD_SIGMA_MULTIPLIER
        val lookaheadTwoSigmaSquared = 2.0 * lookaheadSigma * lookaheadSigma

        var lookaheadScoreSum = 0.0
        var lookaheadWeightSum = 0.0
        for (i in input.length until candidate.length) {
            val positionsAhead = i - input.length
            val decayIndex = minOf(positionsAhead, LOOKAHEAD_DECAY_TABLE.lastIndex)
            val weight = LOOKAHEAD_BASE_WEIGHT * LOOKAHEAD_DECAY_TABLE[decayIndex]
            val transitionScore = scoreCharPair(
                candidate[i - 1],
                candidate[i],
                keyPositions,
                lookaheadTwoSigmaSquared
            )
            lookaheadScoreSum += transitionScore * weight
            lookaheadWeightSum += weight
        }

        val typedWeight = input.length.toDouble()
        val totalWeight = typedWeight + lookaheadWeightSum
        return (typedScore * typedWeight + lookaheadScoreSum) / totalWeight
    }

    private fun scoreDirectPrefix(
        input: String,
        candidate: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double
    ): Double {
        var totalScore = 0.0
        for (i in input.indices) {
            totalScore += scoreCharPair(input[i], candidate[i], keyPositions, twoSigmaSquared)
        }
        return if (input.isNotEmpty()) totalScore / input.length else 0.0
    }

    private fun scoreSameLengthAlignment(
        input: String,
        candidate: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double
    ): Double {
        var totalScore = 0.0
        for (i in input.indices) {
            totalScore += scoreCharPair(input[i], candidate[i], keyPositions, twoSigmaSquared)
        }
        return totalScore / input.length
    }

    private fun scoreDifferentLengthAlignment(
        input: String,
        candidate: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double
    ): Double {
        val shorter: String
        val longer: String
        if (input.length < candidate.length) {
            shorter = input
            longer = candidate
        } else {
            shorter = candidate
            longer = input
        }

        val skipBudget = longer.length - shorter.length
        var bestScore = 0.0

        if (skipBudget == 1) {
            for (skipPos in longer.indices) {
                val score = scoreWithSkips(shorter, longer, keyPositions, twoSigmaSquared, skipPos, -1)
                if (score > bestScore) bestScore = score
            }
        } else if (skipBudget == 2) {
            val totalLength = shorter.length + skipBudget
            for (i in 0 until totalLength) {
                for (j in i + 1 until totalLength) {
                    val score = scoreWithSkips(shorter, longer, keyPositions, twoSigmaSquared, i, j)
                    if (score > bestScore) bestScore = score
                }
            }
        }

        return bestScore
    }

    private fun scoreWithSkips(
        shorter: String,
        longer: String,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double,
        skip1: Int,
        skip2: Int
    ): Double {
        var totalScore = 0.0
        var shortIdx = 0
        for (longIdx in longer.indices) {
            if (longIdx == skip1 || longIdx == skip2) continue
            if (shortIdx >= shorter.length) break
            totalScore += scoreCharPair(shorter[shortIdx], longer[longIdx], keyPositions, twoSigmaSquared)
            shortIdx++
        }
        return if (shorter.isNotEmpty()) totalScore / shorter.length else 0.0
    }

    private fun scoreCharPair(
        inputChar: Char,
        candidateChar: Char,
        keyPositions: Map<Char, android.graphics.PointF>,
        twoSigmaSquared: Double
    ): Double {
        val ic = inputChar.lowercaseChar()
        val cc = candidateChar.lowercaseChar()

        if (ic == cc) return 1.0

        if (ic.isDigit() || cc.isDigit()) return 0.0

        val pos1 = keyPositions[ic] ?: return 0.0
        val pos2 = keyPositions[cc] ?: return 0.0

        val dx = (pos1.x - pos2.x).toDouble()
        val dy = (pos1.y - pos2.y).toDouble()
        val distanceSquared = dx * dx + dy * dy

        return kotlin.math.exp(-distanceSquared / twoSigmaSquared)
    }

    private fun calculateAverageKeySpacing(keyPositions: Map<Char, android.graphics.PointF>): Double {
        if (keyPositions.size < 2) return 0.0

        val posArray = keyPositions.values.toTypedArray()
        var totalMinDistance = 0.0

        for (i in posArray.indices) {
            var minDistSq = Double.MAX_VALUE
            for (j in posArray.indices) {
                if (i == j) continue
                val dx = (posArray[i].x - posArray[j].x).toDouble()
                val dy = (posArray[i].y - posArray[j].y).toDouble()
                val distSq = dx * dx + dy * dy
                if (distSq < minDistSq) minDistSq = distSq
            }
            if (minDistSq != Double.MAX_VALUE) totalMinDistance += kotlin.math.sqrt(minDistSq)
        }

        return totalMinDistance / posArray.size
    }

    private fun calculateFrequencyBoost(frequency: Int): Double {
        if (frequency <= 0) return 0.0

        return when {
            frequency >= HIGH_FREQUENCY_THRESHOLD -> {
                ln(frequency.toDouble()) * HIGH_FREQUENCY_LOG_MULTIPLIER +
                    HIGH_FREQUENCY_BASE_BOOST
            }

            frequency >= MEDIUM_FREQUENCY_THRESHOLD -> {
                ln(frequency.toDouble()) * MEDIUM_FREQUENCY_LOG_MULTIPLIER +
                    MEDIUM_FREQUENCY_BASE_BOOST
            }

            else -> {
                ln(frequency.toDouble() + 1.0) * FREQUENCY_BOOST_MULTIPLIER
            }
        }
    }

    internal companion object {
        const val SUGGESTION_CACHE_SIZE = 500
        const val DICTIONARY_CACHE_SIZE = 1000

        const val MAX_EDIT_DISTANCE = 2
        const val MAX_SUGGESTIONS = 5

        /** Candidate pool size for cluster layouts — the bar shows as many of these as fit; Tab cycles them. */
        const val CLUSTER_BAR_POOL = 16

        /** The singleton (no-bands) re-query cap that rescues the centre-letter word from pool truncation. */
        const val CENTER_WORD_POOL = 4
        const val MIN_COMPLETION_LENGTH = 4
        const val FAT_FINGER_MIN_WORD_LENGTH = 4
        const val APOSTROPHE_BOOST = 0.30
        const val DIACRITIC_PROMOTION_BOOST = 0.08
        const val EXACT_MATCH_CONFIDENCE = 0.999
        const val CONTRACTION_GUARANTEED_CONFIDENCE = 0.995

        // User-dictionary ranking (queryUserDictionarySuggestions): freq 1 sits just under an exact dict hit;
        // freq ≥ 2 sits ABOVE the exact-match ceiling so the user's own word is the #1 candidate, ordered by
        // frequency within that top band (base + step·min(freq,cap), capped at 1.0 → never runs away).
        const val USER_DICT_NEAR_TOP_CONFIDENCE = 0.97
        const val USER_DICT_TOP_BASE_CONFIDENCE = 0.9995
        const val USER_DICT_FREQ_STEP = 0.00001
        const val USER_DICT_FREQ_CAP = 50

        // Typed-word (learned) heavy ranking on cluster layouts — same shape as the user-dictionary band,
        // a hair lower so a deliberately added entry wins a tie at equal frequency.
        const val LEARNED_HEAVY_NEAR_TOP_CONFIDENCE = 0.96
        const val LEARNED_HEAVY_TOP_BASE_CONFIDENCE = 0.9994
        const val LEARNED_HEAVY_MAX_RESULTS = 8
        const val CONTRACTION_DOMINANCE_RATIO = 20L
        const val USER_FREQ_CONTRACTION_WEIGHT = 300L

        const val INITIAL_WORD_LIST_CAPACITY = 50000
        const val INITIALIZATION_TIMEOUT_MS = 5000L

        const val FREQUENCY_BOOST_MULTIPLIER = 0.02
        const val LEARNED_WORD_BASE_CONFIDENCE = 0.60

        /** Spatial score below which learned word base confidence is multiplicatively reduced. */
        const val SPATIAL_GATE_THRESHOLD = 0.4
        const val LEARNED_WORD_CONFIDENCE_MIN = 0.30
        const val LEARNED_SPATIAL_WEIGHT = 0.35
        const val LEARNED_WORD_CONFIDENCE_MAX = 0.99

        const val MAX_PREFIX_COMPLETIONS = 5
        const val COMPLETION_LENGTH_WEIGHT = 0.70
        const val COMPLETION_FREQUENCY_WEIGHT = 0.30
        const val COMPLETION_CONFIDENCE_MIN = 0.50

        const val DICT_DISTANCE_WEIGHT = 0.45
        const val DICT_FREQUENCY_WEIGHT = 0.05
        const val DICT_CONFIDENCE_MIN = 0.0
        const val MAX_DICT_FREQUENCY = 30_000_000.0

        const val SPATIAL_PROXIMITY_WEIGHT = 0.35
        const val PROXIMITY_SIGMA_MULTIPLIER = 2.0
        const val MINIMUM_LENGTH_RATIO = 0.75

        const val LOOKAHEAD_BASE_WEIGHT = 0.5
        const val LOOKAHEAD_DECAY = 0.7
        const val LOOKAHEAD_SIGMA_MULTIPLIER = 4.0
        const val COMPLETION_SPATIAL_WEIGHT = 0.15
        private const val MAX_LOOKAHEAD_POSITIONS = 20

        @JvmField
        val LOOKAHEAD_DECAY_TABLE = DoubleArray(MAX_LOOKAHEAD_POSITIONS) { i ->
            var result = 1.0
            repeat(i) { result *= LOOKAHEAD_DECAY }
            result
        }

        const val MAX_PREFIX_COMPLETION_RESULTS = 10
        const val MAX_INPUT_CODEPOINTS = 100
        const val SUGGESTION_MAX_FREQUENCY_GAP = 500L

        const val HIGH_FREQUENCY_THRESHOLD = 10
        const val MEDIUM_FREQUENCY_THRESHOLD = 3
        const val HIGH_FREQUENCY_BASE_BOOST = 0.15
        const val HIGH_FREQUENCY_LOG_MULTIPLIER = 0.04
        const val MEDIUM_FREQUENCY_BASE_BOOST = 0.05
        const val MEDIUM_FREQUENCY_LOG_MULTIPLIER = 0.03
    }
}
