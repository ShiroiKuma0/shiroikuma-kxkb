package com.urik.keyboard.service.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import com.urik.keyboard.data.UserDictionaryRepository
import com.urik.keyboard.data.VoiceCorpusRepository
import com.urik.keyboard.service.OutputBridge
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.ui.keyboard.components.SwipeKeyboardView
import com.urik.keyboard.ui.keyboard.components.VoiceReview
import com.urik.keyboard.ui.keyboard.components.VoiceReviewMark
import com.urik.keyboard.ui.keyboard.components.VoiceReviewWord
import com.urik.keyboard.utils.ErrorLogger
import java.util.UUID

/**
 * The dictation review loop, between the voice result and the text field:
 *
 * 1. **Judge** each chunk's words before it is committed ([prepare]) — suspect = the recogniser was
 *    unsure, the word is not known in the dictation language, or you corrected this recognition before;
 *    a word you confirmed earlier is never suspect.
 * 2. **Commit** it ([buildCommitText] / [recordCommit]) — with the suspect words underlined where the app honours spans — and
 *    record every word in the [VoiceSessionLedger].
 * 3. **Review** — the suggestion strip lists every dictated word, the marked ones coloured; a tap on any
 *    dictated word, on the strip or in the text, opens the correction box, whose ◂/▸ pull the neighbouring
 *    dictated words in (several misrecognised words → one).
 * 4. **Keep** — a correction is stored with the recording at once; the suspect words you let stand are
 *    stored as confirmed ONLY when you accept: ✓ on the strip, or the next mic press ([accept]). ✕ closes
 *    without learning. Leaving the field, switching apps or hiding the keyboard commits nothing — the
 *    review waits for that field and comes back with it.
 *
 * All calls are on the main thread.
 */
class VoiceReviewCoordinator(
    private val context: Context,
    private val corpus: VoiceCorpusRepository,
    private val userDictionary: UserDictionaryRepository,
    private val spellCheckManager: SpellCheckManager,
    private val outputBridge: OutputBridge,
    private val view: () -> SwipeKeyboardView?,
    /** Give the bar back its normal content (custom row / next-word predictions) after the strip closes. */
    private val restoreBar: () -> Unit,
    /** "Keep all dictation audio": an accepted sentence with nothing to learn keeps its recording too. */
    private val keepAllAudio: () -> Boolean = { false }
) {
    /**
     * A judged chunk, ready to commit. [transcript]'s text already carries the automatic replacements;
     * [originals] maps each replaced word's index to the word the recogniser produced there.
     */
    class Prepared(
        val transcript: VoiceTranscript,
        val language: String,
        val confidences: List<Float?>,
        val judgements: List<VoiceJudgement>,
        val originals: Map<Int, String> = emptyMap()
    )

    private val ledger = VoiceSessionLedger()

    /** The review on disk, so a killed keyboard process does not lose it (only ✓ / mic / ✕ end it). */
    private val pendingStore = VoicePendingStore(context)
    private var restored = false
    private val handler = Handler(Looper.getMainLooper())

    private var stripVisible = false

    /** The run of dictated words the correction box is open on ([from]..[to] of [entry]). */
    private class PendingEdit(val entry: VoiceSessionLedger.Entry, val from: Int, val to: Int)

    private var pendingEdit: PendingEdit? = null

    /** The field the dictation under review lives in, and the field the keyboard is in now. */
    private var ledgerField: String? = null
    private var currentField: String? = null

    private val inLedgerField: Boolean get() = ledgerField != null && ledgerField == currentField

    private val verifyRunnable = Runnable { if (inLedgerField) verifyNow() }

    val hasDictation: Boolean get() = !ledger.isEmpty

    /**
     * Judge [input]'s words in [language] (off the main thread inside); a word under [threshold] recogniser
     * confidence is marked uncertain. The rules themselves live in [VoiceJudging], shared with the
     * walk-capture review page. Never throws.
     */
    suspend fun prepare(input: VoiceTranscript, language: String, threshold: Float): Prepared {
        val judged = VoiceJudging.judge(
            input = input,
            language = language,
            threshold = threshold,
            corpus = corpus,
            userDictionary = userDictionary,
            spellCheckManager = spellCheckManager,
            onError = { operation, e -> log(operation, e) }
        )
        return Prepared(
            judged.transcript,
            judged.language,
            judged.confidences,
            judged.judgements,
            judged.originals
        )
    }

    /**
     * The text to commit for [prepared] with [prefix]/[suffix] spacing: the suspect words carry an
     * underline span when [underline] is on (EditText and WebView draw it; Compose fields drop spans).
     */
    fun buildCommitText(prepared: Prepared, prefix: String, suffix: String, underline: Boolean): CharSequence {
        val plain = prefix + prepared.transcript.text + suffix
        if (!underline || prepared.judgements.none { it.isSuspect }) return plain
        val spanned = SpannableString(plain)
        var index = 0
        for ((start, end) in wordRanges(prepared.transcript.text)) {
            val judgement = prepared.judgements.getOrNull(index++) ?: continue
            if (!judgement.isSuspect) continue
            val token = prepared.transcript.text.substring(start, end)
            val coreStart = start + VoiceWordJudge.coreStartIn(token)
            val coreEnd = coreStart + VoiceWordJudge.coreOf(token).length
            if (coreEnd <= coreStart) continue
            try {
                // AUTO_CORRECTION draws an underline without the framework's own tap-popup (EASY_CORRECT
                // would pop a suggestion menu over our correction box).
                val original = prepared.originals[index - 1]?.let(VoiceWordJudge::coreOf)
                val span = SuggestionSpan(
                    context,
                    listOfNotNull(original).toTypedArray(),
                    SuggestionSpan.FLAG_AUTO_CORRECTION
                )
                spanned.setSpan(span, prefix.length + coreStart, prefix.length + coreEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } catch (e: Exception) {
                log("underline", e)
            }
        }
        return spanned
    }

    /** Record a chunk just committed with its text starting at absolute field offset [fieldStart]. */
    fun recordCommit(prepared: Prepared, fieldStart: Int) {
        ledger.add(
            utteranceId = UUID.randomUUID().toString(),
            language = prepared.language,
            committedText = prepared.transcript.text,
            fieldStart = fieldStart,
            confidences = prepared.confidences,
            judgements = prepared.judgements,
            samples = prepared.transcript.samples,
            originals = prepared.originals
        )
        ledgerField = currentField
        stripVisible = true
        refreshStrip()
        persist()
    }

    private fun persist() = pendingStore.save(ledgerField, ledger.entries())

    /** Bring back a review a previous keyboard process left on disk (once, before the first field). */
    private fun restoreOnce() {
        if (restored) return
        restored = true
        if (!ledger.isEmpty) return
        val (field, entries) = pendingStore.load() ?: return
        ledger.restore(entries)
        ledgerField = field
    }

    /** The keyboard is hiding: re-read the field while it is still connected and keep the review on disk. */
    fun onKeyboardHidden() {
        if (ledger.isEmpty) return
        verifyNow()
        persist()
    }

    /**
     * A field started ([key] identifies it). The dictation under review belongs to one field: back in it, its
     * strip returns; anywhere else the strip stays away — but nothing is committed or lost.
     */
    fun onFieldStarted(key: String) {
        restoreOnce()
        currentField = key
        pendingEdit = null
        stripVisible = !ledger.isEmpty && inLedgerField
        refreshStrip()
    }

    /** The field's view is up: re-locate the dictated words (the text may have changed while away). */
    fun onFieldShown() {
        if (!inLedgerField || ledger.isEmpty) return
        verifyNow()
        refreshStrip()
    }

    // ---- the strip ------------------------------------------------------------------------------------

    fun refreshStrip() {
        val v = view() ?: return
        if (!stripVisible || ledger.isEmpty) {
            v.setVoiceReview(null)
            return
        }
        val words = ledger.allWords().map { (_, word) ->
            val core = VoiceWordJudge.coreOf(word.current).ifEmpty { word.current }
            VoiceReviewWord(
                label = core,
                mark = markOf(word),
                fieldPosition = word.start + VoiceWordJudge.coreStartIn(word.current)
            )
        }
        v.setVoiceReview(VoiceReview(words))
    }

    /** Close the strip and give the bar its normal content back. */
    private fun hideStrip() {
        if (!stripVisible) return
        stripVisible = false
        view()?.setVoiceReview(null)
        restoreBar()
    }

    /**
     * The strip's chip for one word: [VoiceJudging.markOf]'s verdict, with "you corrected it" folded into
     * NONE — in a field the correction is already visible in the text itself.
     */
    private fun markOf(word: VoiceSessionLedger.Word): VoiceReviewMark = when (VoiceJudging.markOf(word)) {
        VoiceJudging.WordMark.LOW_CONFIDENCE -> VoiceReviewMark.LOW_CONFIDENCE
        VoiceJudging.WordMark.UNKNOWN_WORD -> VoiceReviewMark.UNKNOWN_WORD
        VoiceJudging.WordMark.REPLACED -> VoiceReviewMark.KNOWN_MISRECOGNITION
        VoiceJudging.WordMark.CORRECTED, VoiceJudging.WordMark.NONE -> VoiceReviewMark.NONE
    }

    // ---- correcting -----------------------------------------------------------------------------------

    /** A strip word was tapped: put the cursor at the end of its core and open the correction box. */
    fun onStripWordTapped(fieldPosition: Int) {
        if (!inLedgerField) return
        verifyNow()
        val (entry, word) = ledger.wordAt(fieldPosition) ?: run {
            refreshStrip()
            return
        }
        val core = VoiceWordJudge.coreOf(word.current)
        if (core.isEmpty()) return
        val coreEnd = ledger.coreStartOf(word) + core.length
        outputBridge.finishComposingText()
        outputBridge.setSelection(coreEnd, coreEnd)
        if (outputBridge.attemptRecompositionAtCursor(coreEnd)) openCorrection(entry, entry.words.indexOf(word))
    }

    /**
     * A tap in the text recomposed the word [core] at [coreStart]. When it is a dictated word, the
     * correction box opens on it and true is returned (the caller skips the candidate lookup).
     */
    fun onWordRecomposed(coreStart: Int, core: String): Boolean {
        if (ledger.isEmpty || core.isEmpty() || !inLedgerField) return false
        verifyNow()
        val (entry, word) = ledger.wordAt(coreStart) ?: return false
        if (ledger.coreStartOf(word) != coreStart || VoiceWordJudge.coreOf(word.current) != core) return false
        openCorrection(entry, entry.words.indexOf(word))
        return true
    }

    private fun openCorrection(entry: VoiceSessionLedger.Entry, index: Int) {
        val word = entry.words.getOrNull(index) ?: return
        pendingEdit = PendingEdit(entry, index, index)
        // An automatically replaced word opens as "recognised → replacement", only the replacement editable,
        // with ↺ to put the recognised word back.
        val original = if (word.autoReplaced) VoiceWordJudge.coreOf(word.recognized).takeIf { it.isNotEmpty() } else null
        showCorrection(entry, index, index, VoiceWordJudge.coreOf(word.current), original)
    }

    private fun showCorrection(entry: VoiceSessionLedger.Entry, from: Int, to: Int, text: String, original: String? = null) {
        view()?.showEditWordOverlay(
            text,
            canExtendLeft = ledger.adjacentIndex(entry, from, -1) != null,
            canExtendRight = ledger.adjacentIndex(entry, to, +1) != null,
            original = original
        )
    }

    /**
     * ◂ / ▸ in the correction box: pull the neighbouring dictated word into the edit ([direction] -1 / +1),
     * so several misrecognised words become one correction ("lukou i ostupu" → one word). The run is
     * re-underlined in the field and the box shows it whole.
     */
    fun extendCorrection(direction: Int) {
        val pending = pendingEdit ?: return
        verifyNow()
        val entry = pending.entry
        val from = if (direction < 0) ledger.adjacentIndex(entry, pending.from, -1) ?: return else pending.from
        val to = if (direction > 0) ledger.adjacentIndex(entry, pending.to, +1) ?: return else pending.to
        val text = ledger.spanText(entry, from, to) ?: return
        val start = ledger.coreStartOf(entry.words[from])
        outputBridge.recomposeRange(start, text)
        pendingEdit = PendingEdit(entry, from, to)
        showCorrection(entry, from, to, text)
    }

    /**
     * The correction box committed [edited] over the composing text ([coreStart], [original] as captured
     * before the commit — one word or a run of words; falls back to what this coordinator opened the box on).
     * Not dictated text → no-op.
     */
    fun onEditCommitted(coreStart: Int, original: String, edited: String) {
        val pending = pendingEdit
        pendingEdit = null
        val target = if (coreStart >= 0 && original.isNotEmpty()) {
            coreStart to original
        } else {
            pending?.let { p ->
                ledger.spanText(p.entry, p.from, p.to)?.let { ledger.coreStartOf(p.entry.words[p.from]) to it }
            }
        } ?: return
        val applied = ledger.applyEdit(target.first, target.second, edited) ?: return
        corpus.recordCorrection(applied.entry, applied.word, applied.recognized, edited)
        refreshStrip()
        persist()
    }

    // ---- verification & acceptance ---------------------------------------------------------------------

    /** The selection moved: re-check the dictated text shortly (debounced — a burst of updates reads once). */
    fun onSelectionChanged() {
        if (ledger.isEmpty || !inLedgerField) return
        handler.removeCallbacks(verifyRunnable)
        handler.postDelayed(verifyRunnable, VERIFY_DEBOUNCE_MS)
    }

    /** Re-locate the dictated words in the field around the cursor (only in the dictation's own field). */
    fun verifyNow() {
        if (ledger.isEmpty || !inLedgerField) return
        handler.removeCallbacks(verifyRunnable)
        try {
            val before = outputBridge.safeGetTextBeforeCursor(VERIFY_BEFORE_CHARS)
            val after = outputBridge.safeGetTextAfterCursor(VERIFY_AFTER_CHARS)
            if (before.isEmpty() && after.isEmpty()) return
            val cursor = outputBridge.safeGetCursorPosition()
            ledger.verify(
                window = before + after,
                windowStart = cursor - before.length,
                reachesFieldStart = before.length < VERIFY_BEFORE_CHARS,
                reachesFieldEnd = after.length < VERIFY_AFTER_CHARS
            )
        } catch (e: Exception) {
            log("verify", e)
        }
    }

    /**
     * Accept the dictation (✓, or the next mic press): every suspect word still standing unchanged is stored
     * as confirmed, and the strip closes. The field is re-read first when it is the dictation's own; from
     * another field the last verified state counts.
     */
    fun accept() {
        if (ledger.isEmpty) {
            hideStrip()
            return
        }
        if (inLedgerField) verifyNow()
        corpus.recordAcceptance(ledger.drain(), keepAll = keepAllAudio())
        pendingEdit = null
        ledgerField = null
        pendingStore.clear()
        hideStrip()
    }

    /** ✕: close the review without confirming anything (corrections already made stay stored). */
    fun discard() {
        ledger.clear()
        pendingEdit = null
        ledgerField = null
        pendingStore.clear()
        hideStrip()
    }

    /** Teardown: only ✓ or the mic commits, so nothing is accepted here — the review stays on disk. */
    fun shutdown() {
        handler.removeCallbacks(verifyRunnable)
        if (!ledger.isEmpty) persist()
    }

    private fun log(operation: String, e: Exception) {
        ErrorLogger.logException(
            component = "VoiceReviewCoordinator",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to operation)
        )
    }

    companion object {
        private const val VERIFY_DEBOUNCE_MS = 300L

        /** The longest run of words a stored multi-word correction is looked for. */
        private const val MAX_PHRASE_WORDS = 6

        /**
         * Replace every run of 2–[MAX_PHRASE_WORDS] words in [text] whose case-folded text (outer punctuation
         * of the run left out, as stored) has a correction from [correctionFor], longest run first, left to
         * right. Returns the new text and, per index of a replaced word in the NEW text, the run it replaced
         * (punctuation as recognised).
         */
        fun replacePhrases(text: String, correctionFor: (String) -> String?): Pair<String, Map<Int, String>> {
            val ranges = wordRanges(text)
            if (ranges.size < 2) return text to emptyMap()
            val sb = StringBuilder()
            val originals = mutableMapOf<Int, String>()
            var cursor = 0
            var outIndex = 0
            var i = 0
            while (i < ranges.size) {
                var matched = false
                val longest = minOf(MAX_PHRASE_WORDS, ranges.size - i)
                for (n in longest downTo 2) {
                    val first = text.substring(ranges[i].first, ranges[i].second)
                    val last = text.substring(ranges[i + n - 1].first, ranges[i + n - 1].second)
                    val runStart = ranges[i].first + VoiceWordJudge.coreStartIn(first)
                    val runEnd = ranges[i + n - 1].first + VoiceWordJudge.coreStartIn(last) +
                        VoiceWordJudge.coreOf(last).length
                    if (runEnd <= runStart) continue
                    val run = text.substring(runStart, runEnd)
                    if (run.any { it == '\n' }) continue
                    val correction = correctionFor(run.lowercase().replace(Regex("\\s+"), " ")) ?: continue
                    sb.append(text, cursor, runStart).append(correction)
                    cursor = runEnd
                    originals[outIndex] = text.substring(ranges[i].first, ranges[i + n - 1].second)
                    outIndex += VoiceTranscript.splitWords(correction).size.coerceAtLeast(1)
                    i += n
                    matched = true
                    break
                }
                if (!matched) {
                    outIndex++
                    i++
                }
            }
            if (originals.isEmpty()) return text to emptyMap()
            sb.append(text, cursor, text.length)
            return sb.toString() to originals
        }
        private const val VERIFY_BEFORE_CHARS = 4000
        private const val VERIFY_AFTER_CHARS = 2000

        /** (start, end) of every whitespace-separated word of [text]. */
        fun wordRanges(text: String): List<Pair<Int, Int>> {
            val out = mutableListOf<Pair<Int, Int>>()
            var i = 0
            while (i < text.length) {
                if (text[i].isWhitespace()) {
                    i++
                    continue
                }
                val start = i
                while (i < text.length && !text[i].isWhitespace()) i++
                out.add(start to i)
            }
            return out
        }
    }
}
