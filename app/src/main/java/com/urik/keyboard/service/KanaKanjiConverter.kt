package com.urik.keyboard.service

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.urik.keyboard.data.database.UserKanjiFrequencyDao
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class KanaKanjiConverter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userKanjiFrequencyDao: UserKanjiFrequencyDao
) : ScriptConverter {
    private var index: TreeMap<String, MutableList<ConversionCandidate>> = TreeMap()
    private val userFrequencies = ConcurrentHashMap<String, Long>()

    private val loadMutex = Mutex()
    private var writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // The dispatcher index/DB I/O hops to. Overridable in tests so the whole load + persist runs on the
    // (single, controllable) test dispatcher — otherwise a nested Dispatchers.IO hop escapes the test scheduler.
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @Volatile private var loaded = false

    override val supportedLanguages: Set<String> = setOf("ja")

    override val isReady: Boolean get() = loaded

    @VisibleForTesting
    internal constructor(
        context: Context,
        userKanjiFrequencyDao: UserKanjiFrequencyDao,
        writeDispatcher: CoroutineDispatcher
    ) : this(context, userKanjiFrequencyDao) {
        writeScope = CoroutineScope(SupervisorJob() + writeDispatcher)
        ioDispatcher = writeDispatcher
    }

    override suspend fun getCandidates(input: String, languageCode: String): List<ConversionCandidate> {
        ensureLoaded()
        if (input.isEmpty()) return emptyList()

        val ceiling = input + '￿'
        val dict = index.subMap(input, ceiling).values.flatten()

        // A user-frequency entry is an MRU NUDGE on top of the dictionary frequency, never a replacement: a
        // surface that also exists in the bundled dictionary keeps (at least) its dictionary rank and is only
        // bumped up by the modest learn boost — it can never be DEMOTED below where it would sit unlearned.
        // A surface absent from the dictionary (a registered word like 白い熊) rides purely on its stored boost,
        // which is why an explicit registration is seeded with the large REGISTER_BOOST. (Japanese FIX 2 / BUG A.)
        val dictFreqBySurface = dict.associate { it.surface to it.frequency }
        val userBoosted = userFrequencies.entries
            .filter { it.key.startsWith("$input\t") }
            .map { (key, freq) ->
                val surface = key.substringAfter("\t")
                val base = dictFreqBySurface[surface] ?: 0L
                ConversionCandidate(surface, input, base + freq * USER_FREQ_MULTIPLIER, "learned")
            }

        val userSurfaces = userBoosted.map { it.surface }.toSet()
        return (userBoosted + dict.filter { it.surface !in userSurfaces })
            .sortedByDescending { it.frequency }
            .distinctBy { it.surface }
    }

    /**
     * Learn an ordinary candidate selection. Only persisted when [surface] is a conversion of the FULL typed
     * [input] — i.e. the dictionary lists it under the exact reading [input], or it is already a user entry for
     * [input] (a previously registered/learned surface). A longer completion offered by the prefix lookup (e.g.
     * しろいはな while typing しろい — its true reading is しろいはな, not しろい) is NOT bound to the shorter
     * reading, so one accidental tap on a completion can never pollute the base reading. (BUG A.)
     */
    override fun recordSelection(input: String, surface: String) {
        val r = input.trim()
        val s = surface.trim()
        if (r.isEmpty() || s.isEmpty()) return
        // The gate consults the loaded dictionary index, so load it first (off the main thread). The in-memory
        // boost map is only nudged once the gate passes, keeping a mis-selected completion out of it entirely.
        writeScope.launch {
            try {
                ensureLoaded()
                if (!isFullReadingSurface(r, s)) return@launch
                val key = "$r\t$s"
                userFrequencies.merge(key, BASE_USER_FREQUENCY) { old, new -> old + new }
                userKanjiFrequencyDao.incrementBy(
                    reading = r,
                    surface = s,
                    amount = BASE_USER_FREQUENCY,
                    lastUsed = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "KanaKanjiConverter",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "persistUserSelection")
                )
            }
        }
    }

    /**
     * True when [surface] is a legitimate conversion of the FULL typed [reading] — the dictionary lists it under
     * the exact reading, or it is already a user entry for that reading. A longer completion offered by the
     * prefix lookup (e.g. しろいはな while typing しろい — its true reading is しろいはな, not しろい) fails this,
     * so one accidental tap on a completion can never bind to (and pollute) the shorter base reading. (BUG A.)
     */
    private fun isFullReadingSurface(reading: String, surface: String): Boolean {
        // The plain kana reading is an always-present base candidate, not a learnable row.
        if (surface == reading) return false
        // Already a user entry for this exact reading (e.g. an earlier registration) — re-MRU it.
        if (userFrequencies.containsKey("$reading\t$surface")) return true
        // Otherwise require the dictionary to list this surface under the EXACT reading (not a prefix extension).
        return index[reading]?.any { it.surface == surface } == true
    }

    /**
     * Register a user reading→surface mapping (e.g. しろいくま → 白い熊). Stored in the SAME user-frequency
     * table the learned selections use, but seeded with a large boost so the registered surface ranks at the
     * top of the candidate list — and, since [getCandidates] offers every user-frequency entry whether or not
     * it exists in the bundled dictionary, a brand-new surface becomes available for that reading immediately
     * and after a restart. (Japanese FIX 2.)
     */
    override fun registerEntry(reading: String, surface: String) {
        val r = reading.trim()
        val s = surface.trim()
        if (r.isEmpty() || s.isEmpty()) return
        boostUserEntry(r, s, REGISTER_BOOST, "registerEntry")
    }

    /**
     * Forget a user reading→surface entry (a learned or registered pair), both from the in-memory boost map and
     * the Room store, so the polluting/unwanted surface stops being offered for that reading. Used by the
     * Japanese user-dictionary settings editor's per-row delete. (BUG A.)
     */
    override fun removeEntry(reading: String, surface: String) {
        val r = reading.trim()
        val s = surface.trim()
        if (r.isEmpty() || s.isEmpty()) return
        userFrequencies.remove("$r\t$s")
        writeScope.launch {
            try {
                userKanjiFrequencyDao.delete(r, s)
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "KanaKanjiConverter",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to "removeEntry")
                )
            }
        }
    }

    private fun boostUserEntry(input: String, surface: String, amount: Long, operation: String) {
        val key = "$input\t$surface"
        userFrequencies.merge(key, amount) { old, new -> old + new }
        writeScope.launch {
            try {
                userKanjiFrequencyDao.incrementBy(
                    reading = input,
                    surface = surface,
                    amount = amount,
                    lastUsed = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "KanaKanjiConverter",
                    severity = ErrorLogger.Severity.LOW,
                    exception = e,
                    context = mapOf("operation" to operation)
                )
            }
        }
    }

    override fun release() {
        writeScope.cancel()
        writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        index = TreeMap()
        loaded = false
    }

    @VisibleForTesting
    internal fun userFrequenciesForTest(): Map<String, Long> = userFrequencies

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            val result = withContext(ioDispatcher) { loadIndex() }
            if (result.isNotEmpty()) {
                index = result
                try {
                    val rows = userKanjiFrequencyDao.getAll()
                    rows.forEach { row ->
                        userFrequencies["${row.reading}\t${row.surface}"] = row.frequency
                    }
                } catch (e: Exception) {
                    ErrorLogger.logException(
                        component = "KanaKanjiConverter",
                        severity = ErrorLogger.Severity.HIGH,
                        exception = e,
                        context = mapOf("operation" to "loadUserFrequencies")
                    )
                }
                loaded = true
            }
        }
    }

    /**
     * The generated inflected-form list `dictionaries/ja.demoted` (one `reading<TAB>surface` per line,
     * see `tools/demote_ja_conjugations.py`), as the `"$reading\t$surface"` keys [loadIndex] matches on.
     * Absent file = no demotions, i.e. exactly upstream's ranking.
     */
    private fun loadDemotions(): Set<String> = try {
        context.assets.open("dictionaries/ja.demoted").bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()
        }
    } catch (_: Exception) {
        emptySet()
    }

    private suspend fun loadIndex(): TreeMap<String, MutableList<ConversionCandidate>> {
        val result = TreeMap<String, MutableList<ConversionCandidate>>()
        try {
            // Held only for the duration of the build, then dropped with the local — the penalty is
            // folded into the stored frequency, so nothing is consulted again on the lookup path.
            val demoted = loadDemotions()
            context.assets.open("dictionaries/ja_readings.txt").bufferedReader().use { reader ->
                for (line in reader.lineSequence()) {
                    currentCoroutineContext().ensureActive()
                    if (line.startsWith("#") || line.isBlank()) continue
                    val parts = line.split("\t")
                    if (parts.size == 3) {
                        val reading = parts[0]
                        val surface = parts[1]
                        val freq = parts[2].toLongOrNull()
                        if (freq != null) {
                            val penalty =
                                if ("$reading\t$surface" in demoted) INFLECTION_PENALTY else 0L
                            result.getOrPut(reading) { mutableListOf() }
                                .add(ConversionCandidate(surface, reading, freq - penalty, "dictionary"))
                        }
                    }
                }
            }
            result.values.forEach { list -> list.sortByDescending { it.frequency } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "KanaKanjiConverter",
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("operation" to "loadIndex")
            )
        }
        return result
    }

    private companion object {
        // Modest multiplier: an ordinary learned selection (stored amount BASE_USER_FREQUENCY) contributes only
        // a small additive nudge ON TOP OF the dictionary frequency, so one casual mis-tap is MRU-ish, never an
        // override that buries the bundled candidates. (Bundled frequencies run into the tens of thousands.)
        // (BUG A — was 500, which let a single selection dominate.)
        const val USER_FREQ_MULTIPLIER = 25L
        const val BASE_USER_FREQUENCY = 1L

        // Subtracted from a bundled frequency when the pair is listed in `dictionaries/ja.demoted` — an
        // INFLECTED form of a verb or adjective (住もう, the volitional of 住む; 住し, a bare サ変 fragment).
        // Mozc stores every conjugated form as its own entry carrying the verb's cost, and its own converter
        // only ever uses that cost inside a Viterbi search over the whole sentence, where the POS connection
        // matrix is what keeps a bare volitional from winning a one-word conversion. Flattened into this
        // per-reading unigram table that correction is gone, so the inflections of common verbs bury genuine
        // nouns (すもう offered 住もう 16606 ahead of 相撲 15222; えん offered 得ん ahead of 円).
        //
        // The penalty is GRADED, not absolute: the bundled band is 14000-20000, so 2000 (a third of it) puts
        // an inflection behind a noun of comparable standing while a very common one still beats a rare
        // homophone (知ろう 17916 keeps its lead over the name 四郎 15810). Pushing every inflection below the
        // whole band instead starts promoting obscure names — measured at 3000 it offers 亜子 for あこ and
        // 以遠 for いえん. Selecting a demoted candidate still learns it: USER_FREQ_MULTIPLIER climbs from the
        // demoted base, and ＋登録 overrides it outright.
        const val INFLECTION_PENALTY = 2_000L

        // An EXPLICITLY registered entry (the ＋登録 flow) is seeded with a large stored amount so that, even for
        // a brand-new surface with no dictionary frequency, REGISTER_BOOST * USER_FREQ_MULTIPLIER (= 25 000) out-
        // ranks the highest bundled frequency (20 000) and the registered surface lands at the top. (Japanese FIX 2.)
        const val REGISTER_BOOST = 1_000L
    }
}
