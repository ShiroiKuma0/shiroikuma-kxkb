package com.urik.keyboard.settings.voice

import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.urik.keyboard.R
import com.urik.keyboard.automation.GengoshimaHandoff
import com.urik.keyboard.data.UserDictionaryRepository
import com.urik.keyboard.data.VoiceCaptureStore
import com.urik.keyboard.data.VoiceCorpusRepository
import com.urik.keyboard.data.VoiceHandoffOutbox
import com.urik.keyboard.data.database.DatabaseAvailability
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.voice.CzechCommas
import com.urik.keyboard.service.voice.SpokenPunctuation
import com.urik.keyboard.service.voice.VoiceInputController
import com.urik.keyboard.service.voice.VoiceJudging
import com.urik.keyboard.service.voice.VoiceModelStore
import com.urik.keyboard.service.voice.VoiceSessionLedger
import com.urik.keyboard.service.voice.VoiceTranscript
import com.urik.keyboard.service.voice.VoiceWordJudge
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.ui.keyboard.components.VoiceReviewColors
import com.urik.keyboard.utils.KxkbToast
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The walk-capture review: the sentences 白い熊 spoke outside with the screen off, recorded by 自由作業盤's
 * 物理鍵 key grabber and handed to this keyboard over the automation door.
 *
 * Nothing is decoded while walking — the clips arrive as audio and are transcribed HERE, with the newest
 * corrections and vocabulary bias behind them. Each sentence can be played, its marked words corrected the
 * same way a dictation's are, and then sent: the correction becomes an auto-replacement rule, the
 * confirmations become corpus evidence, the recording moves into the voice corpus and the sentence goes
 * back to 自由作業盤 for 言語島. Exactly the calls the dictation review makes
 * ([VoiceCorpusRepository.recordCorrection] / [VoiceCorpusRepository.recordAcceptance]), exactly the same
 * judging and the same word marks ([VoiceJudging]), so what the keyboard learns here cannot drift from what
 * it learns at a text field.
 */
@AndroidEntryPoint
class VoiceCaptureFragment : Fragment() {
    @Inject lateinit var corpus: VoiceCorpusRepository

    @Inject lateinit var userDictionary: UserDictionaryRepository

    @Inject lateinit var spellCheckManager: SpellCheckManager

    @Inject lateinit var voiceInput: VoiceInputController

    @Inject lateinit var settingsRepository: SettingsRepository

    /** One captured sentence on the page: the stored row, and — once decoded — its own one-sentence ledger. */
    private class Row(
        val entry: VoiceCaptureStore.Entry,
        val ledger: VoiceSessionLedger?,
        val sentence: VoiceSessionLedger.Entry?
    )

    private lateinit var store: VoiceCaptureStore
    private lateinit var outbox: VoiceHandoffOutbox
    private lateinit var handoff: GengoshimaHandoff
    private lateinit var root: LinearLayout
    private var player: MediaPlayer? = null
    private var playingButton: Button? = null
    private var working = false
    private val rows = mutableListOf<Row>()

    // The transcription run's progress dialog — the load alone is seconds, which read as "stuck" before.
    private var progressDialog: AlertDialog? = null
    private var progressStep: TextView? = null
    private var progressDetail: TextView? = null
    private var progressBar: ProgressBar? = null
    private var stopRequested = false

    private val yellow = 0xFFFFFF00.toInt()
    private val dim = 0xFFCCCC66.toInt()

    // The dictation strip's three marks, so a word means the same thing on both, plus one this page adds:
    // a colour for a word YOU corrected here (in a field you can see that in the text; in a list you
    // cannot). All four are settable in the kxkb UI page (Rows → Suggestion bar) and read from the live
    // geometry's baseline — they are style knobs, which the "apply to all keyboards" fan-out writes to
    // every baseline, so this page and the keyboard always show the same four colours.
    private var markLow = VoiceReviewColors.UNSURE
    private var markUnknown = VoiceReviewColors.UNKNOWN
    private var markReplaced = VoiceReviewColors.REPLACED
    private var markCorrected = VoiceReviewColors.CORRECTED

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        store = VoiceCaptureStore(requireContext())
        outbox = VoiceHandoffOutbox(requireContext())
        handoff = GengoshimaHandoff(outbox)
        val scroll = ScrollView(requireContext()).apply {
            setBackgroundColor(0xFF000000.toInt())
            isFillViewport = true
        }
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(36))
        }
        scroll.addView(root)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rebuild()
    }

    override fun onStop() {
        super.onStop()
        stopPlaying()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissProgress()
    }

    // ---- the page --------------------------------------------------------------------------------------

    private fun rebuild() {
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            val threshold = settings.voiceUncertaintyPercent / 100f
            loadMarkColours()
            val entries = withContext(Dispatchers.IO) {
                store.sweepOrphans()
                store.entries()
            }
            rows.clear()
            for (entry in entries) rows.add(rowFor(entry, threshold))
            render()
        }
    }

    /** The four mark colours as this keyboard has them set, or the built-in defaults. */
    private suspend fun loadMarkColours() {
        val knobs = settingsRepository.currentGeometry.first()
            ?.let { settingsRepository.getGeometryBaselineLook(it) }
            ?: return
        markLow = knobs.voiceMarkUnsureColor ?: VoiceReviewColors.UNSURE
        markUnknown = knobs.voiceMarkUnknownColor ?: VoiceReviewColors.UNKNOWN
        markReplaced = knobs.voiceMarkReplacedColor ?: VoiceReviewColors.REPLACED
        markCorrected = knobs.voiceMarkCorrectedColor ?: VoiceReviewColors.CORRECTED
    }

    /**
     * A decoded sentence gets a one-sentence ledger: the words, their marks and their offsets inside the
     * sentence itself (`fieldStart = 0`). The dictation ledger's field machinery — the longest-common-
     * subsequence re-location of words in a live field — has nothing to re-locate here, and is never called.
     */
    private suspend fun rowFor(entry: VoiceCaptureStore.Entry, threshold: Float): Row {
        val text = entry.text ?: return Row(entry, null, null)
        val transcript = VoiceTranscript(
            text,
            entry.language,
            entry.rawWords,
            entry.rawConfidences?.toFloatArray(),
            null
        )
        val judged = VoiceJudging.judge(
            input = transcript,
            language = entry.language,
            threshold = threshold,
            corpus = corpus,
            userDictionary = userDictionary,
            spellCheckManager = spellCheckManager
        )
        val ledger = VoiceSessionLedger()
        val sentence = ledger.add(
            utteranceId = entry.uuid,
            language = entry.language,
            committedText = judged.transcript.text,
            fieldStart = 0,
            confidences = judged.confidences,
            judgements = judged.judgements,
            samples = null,
            originals = judged.originals,
            clipPath = store.clipFile(entry)?.absolutePath
        )
        return Row(entry, ledger, sentence)
    }

    private fun render() {
        root.removeAllViews()
        root.addView(text(getString(R.string.voice_capture_title), 22f, yellow))
        val used = store.usedBytes() / (1024.0 * 1024.0)
        val budget = store.budgetBytes() / (1024.0 * 1024.0)
        root.addView(
            text(getString(R.string.voice_capture_summary, rows.size, used, budget), 13f, dim)
                .apply { setPadding(0, dp(6), 0, 0) }
        )
        val waiting = outbox.pendingCount()
        root.addView(
            text(getString(R.string.voice_capture_handoff_state, waiting, outbox.sentCount()), 13f, dim)
                .apply { setPadding(0, dp(2), 0, 0) }
        )
        root.addView(text(getString(R.string.voice_capture_intro), 13f, dim).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(legend())
        if (rows.any { !it.entry.transcribed }) {
            root.addView(pill(getString(R.string.voice_capture_transcribe_all)) { transcribeAll() })
        }
        if (rows.isEmpty()) {
            root.addView(text(getString(R.string.voice_capture_empty), 14f, dim).apply { setPadding(0, dp(16), 0, 0) })
        } else {
            for (row in rows) root.addView(rowView(row))
        }
        root.addView(bottomBar(waiting))
    }

    /** What the colours mean, in the colours themselves — four words beat a paragraph. */
    private fun legend(): View = TextView(requireContext()).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(dim)
        setPadding(0, dp(6), 0, 0)
        val parts = listOf(
            getString(R.string.voice_capture_mark_unsure) to markLow,
            getString(R.string.voice_capture_mark_unknown) to markUnknown,
            getString(R.string.voice_capture_mark_replaced) to markReplaced,
            getString(R.string.voice_capture_mark_corrected) to markCorrected
        )
        val sb = SpannableStringBuilder(getString(R.string.voice_capture_legend))
        for ((label, colour) in parts) {
            sb.append(' ')
            val start = sb.length
            sb.append(label)
            sb.setSpan(ForegroundColorSpan(colour), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (label != parts.last().first) sb.append(" ·")
        }
        text = sb
    }

    /**
     * Close, and close the settings with it: this page is opened straight from 自由作業盤 or the space-slide
     * menu, so "back out of the review" means leaving the keyboard's UI altogether, not climbing a settings
     * tree 白い熊 never walked down.
     */
    private fun bottomBar(waiting: Int): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(18), 0, 0)
        if (rows.any { it.sentence != null }) {
            addView(pill(getString(R.string.voice_capture_send_all)) { sendAll() })
        }
        addView(
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(pill(getString(R.string.voice_capture_close)) { closeSettings() })
                addView(
                    View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                    }
                )
                if (waiting > 0) {
                    addView(pill(getString(R.string.voice_capture_resend, waiting)) { resend() })
                }
            }
        )
    }

    private fun rowView(row: Row): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(14), 0, dp(10))
        val entry = row.entry
        addView(
            text(
                "${stamp.format(Date(entry.capturedAt))} · ${entry.language} · " +
                    String.format(Locale.ROOT, "%.1f s", entry.durationMs / 1000.0),
                12f,
                dim
            )
        )
        if (row.sentence == null) {
            val label = if (entry.attempts > 0) {
                getString(R.string.voice_capture_nothing, entry.attempts)
            } else {
                getString(R.string.voice_capture_not_decoded)
            }
            val colour = if (entry.attempts > 0) markLow else dim
            addView(text(label, 16f, colour).apply { setPadding(0, dp(2), 0, 0) })
        } else {
            addView(
                TextView(requireContext()).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setTextColor(yellow)
                    setPadding(0, dp(2), 0, 0)
                    highlightColor = Color.TRANSPARENT
                    movementMethod = LinkMovementMethod.getInstance()
                    text = sentenceText(row)
                }
            )
            val heard = entry.recognized
            if (heard != null && heard != row.sentence.currentText()) {
                addView(text(getString(R.string.voice_capture_heard, heard), 12f, dim))
            }
        }
        addView(buttonRow(row))
        addView(
            View(requireContext()).apply {
                setBackgroundColor(0x66FFFF00)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(8)
                }
            }
        )
    }

    /**
     * Two lines rather than one: "Send to 白い熊 自由作業盤" is a long label by design, and a single row of
     * pills would run off the edge of a folded screen.
     */
    private fun buttonRow(row: Row): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                if (store.clipFile(row.entry) != null) {
                    lateinit var play: Button
                    play = pill(getString(R.string.voice_corpus_play)) { togglePlay(row, play) }
                    addView(play)
                }
                addView(pill(getString(R.string.voice_capture_edit)) { editSentence(row) })
                addView(pill(getString(R.string.voice_corpus_delete)) { confirmDrop(row) })
            }
        )
        if (row.sentence != null) {
            addView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    addView(pill(getString(R.string.voice_capture_send_one)) { sendOneAndPush(row) })
                }
            )
        }
    }

    /**
     * Edit the whole sentence as text — the way to fix PUNCTUATION, which the per-word box cannot touch: it
     * opens on a word's core, and a correction keeps whatever punctuation stood around it. Whisper ending a
     * sentence mid-utterance ("…the river. The water was cold.") is exactly this case, and only a comma in
     * place of that period fixes it.
     *
     * A hand edit re-derives the row from the new text: the marks and confidences are judged again (the
     * confidences re-align onto the new words by their cores, so a changed comma keeps them), and the
     * recognition stays on file as the "Heard:" line. It teaches the keyboard nothing by itself — a word you
     * want remembered is better corrected by tapping it, which stores the pair — and the box says so.
     *
     * It also works on a clip nothing was recognised in: the typed text becomes the sentence, so a recording
     * the engine mangles is still usable rather than only deletable.
     */
    private fun editSentence(row: Row) {
        val entry = row.entry
        val current = row.sentence?.currentText() ?: entry.text ?: ""
        val input = EditText(requireContext()).apply {
            setText(current)
            setSelection(current.length)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            minLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(input)
            addView(
                text(getString(R.string.voice_capture_edit_hint), 12f, dim)
                    .apply { setPadding(0, dp(8), 0, 0) }
            )
        }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_capture_edit_title)
            .setView(content)
            .setPositiveButton(R.string.voice_capture_save) { _, _ ->
                commitSentence(row, input.text.toString())
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    private fun commitSentence(row: Row, edited: String) {
        val text = edited.replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return
        val entry = row.entry
        if (entry.transcribed) {
            store.saveText(entry.uuid, text)
        } else {
            // Nothing was recognised here: the typed text becomes both the sentence and its "recognition",
            // with no word confidences to align — there was no decode to take them from.
            store.saveTranscript(
                uuid = entry.uuid,
                recognized = text,
                text = text,
                rawWords = null,
                rawConfidences = null
            )
        }
        rebuild()
    }

    /**
     * The sentence with every word tappable and the marked ones coloured, by the same rule the dictation
     * strip marks words with ([VoiceJudging.markOf]): red where the recogniser was unsure, orange for a word
     * it does not know in this language, blue where an earlier correction of yours replaced what it heard,
     * green where you corrected it here.
     */
    private fun sentenceText(row: Row): CharSequence {
        val sentence = row.sentence ?: return ""
        val sb = SpannableStringBuilder()
        for (word in sentence.words) {
            if (!word.intact) continue
            if (sb.isNotEmpty()) sb.append(' ')
            val start = sb.length
            sb.append(word.current)
            val colour = when (VoiceJudging.markOf(word)) {
                VoiceJudging.WordMark.LOW_CONFIDENCE -> markLow
                VoiceJudging.WordMark.UNKNOWN_WORD -> markUnknown
                VoiceJudging.WordMark.REPLACED -> markReplaced
                VoiceJudging.WordMark.CORRECTED -> markCorrected
                VoiceJudging.WordMark.NONE -> null
            }
            val coreStart = start + VoiceWordJudge.coreStartIn(word.current)
            val coreEnd = (coreStart + VoiceWordJudge.coreOf(word.current).length).coerceAtMost(sb.length)
            if (colour != null && coreEnd > coreStart) {
                sb.setSpan(ForegroundColorSpan(colour), coreStart, coreEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val index = word.index
            sb.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = openCorrection(row, index, index)

                    // No colour of its own: a link colour here would paint over the word's mark.
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false
                    }
                },
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    // ---- correcting ------------------------------------------------------------------------------------

    /**
     * The correction box over words [from]..[to] of this sentence: an ordinary [EditText], which is allowed
     * here and not in the keyboard — the in-keyboard overlay exists only because an IME window may not host
     * one. ◂ / ▸ pull the neighbouring words in, so several misrecognised words become one correction; ↺
     * puts back what was heard where an earlier correction already replaced it.
     */
    private fun openCorrection(row: Row, from: Int, to: Int) {
        val ledger = row.ledger ?: return
        val sentence = row.sentence ?: return
        val runText = ledger.spanText(sentence, from, to) ?: return
        val word = sentence.words.getOrNull(from) ?: return
        val input = EditText(requireContext()).apply {
            setText(runText)
            setSelection(runText.length)
            setTextColor(yellow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            val heard = ledger.spanText(sentence, from, to, recognized = true)
            if (heard != null && heard != runText) {
                addView(text(getString(R.string.voice_capture_heard, heard), 13f, dim))
            }
            addView(input)
            addView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    addView(pill(getString(R.string.voice_capture_extend_left)) {})
                    addView(pill(getString(R.string.voice_capture_extend_right)) {})
                    if (word.autoReplaced) addView(pill(getString(R.string.voice_capture_restore)) {})
                }
            )
        }
        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_capture_correct)
            .setView(content)
            .setPositiveButton(R.string.voice_capture_save) { _, _ ->
                commitCorrection(row, from, to, input.text.toString())
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .create()
        // The three pills act on the run, so they re-open the box rather than edit it in place.
        val bar = (content.getChildAt(content.childCount - 1) as LinearLayout)
        var pill = 0
        (bar.getChildAt(pill++) as Button).setOnClickListener {
            val next = ledger.adjacentIndex(sentence, from, -1) ?: return@setOnClickListener
            dialog.dismiss()
            openCorrection(row, next, to)
        }
        (bar.getChildAt(pill++) as Button).setOnClickListener {
            val next = ledger.adjacentIndex(sentence, to, +1) ?: return@setOnClickListener
            dialog.dismiss()
            openCorrection(row, from, next)
        }
        if (word.autoReplaced) {
            (bar.getChildAt(pill) as Button).setOnClickListener {
                val heard = VoiceWordJudge.coreOf(word.recognized)
                if (heard.isNotEmpty()) {
                    input.setText(heard)
                    input.setSelection(heard.length)
                }
            }
        }
        dialog.show()
    }

    private fun commitCorrection(row: Row, from: Int, to: Int, edited: String) {
        val ledger = row.ledger ?: return
        val sentence = row.sentence ?: return
        val trimmed = edited.trim()
        if (trimmed.isEmpty()) return
        val original = ledger.spanText(sentence, from, to) ?: return
        val coreStart = ledger.coreStartOf(sentence.words[from])
        val applied = ledger.applyEdit(coreStart, original, trimmed) ?: return
        // Stored at once, like the dictation box: a correction is evidence whether or not the sentence is
        // ever sent — and this is what makes the same recognition replace itself next time.
        corpus.recordCorrection(applied.entry, applied.word, applied.recognized, trimmed)
        store.saveText(row.entry.uuid, sentence.currentText())
        rebuild()
    }

    // ---- sending and dropping --------------------------------------------------------------------------

    /**
     * Accept one sentence and queue it for 自由作業盤: the words left standing are confirmed, the recording
     * MOVES into the voice corpus, the inbox row goes and the sentence enters the hand-over queue.
     * [VoiceCorpusRepository.recordAcceptance] is called with `keepAll` on, unlike the dictation review's
     * setting-driven call — a walk sentence is deliberate training material, so its audio is kept even when
     * there was nothing to correct.
     */
    private fun sendOne(row: Row): Boolean {
        val sentence = row.sentence ?: return false
        corpus.recordAcceptance(listOf(sentence), keepAll = true)
        store.remove(row.entry.uuid, deleteClip = false)
        outbox.add(
            VoiceHandoffOutbox.Item(
                uuid = row.entry.uuid,
                text = sentence.currentText(),
                recognized = row.entry.recognized ?: sentence.recognizedText,
                language = row.entry.language,
                capturedAt = row.entry.capturedAt,
                queuedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    private fun sendOneAndPush(row: Row) {
        if (!storeReady()) return
        stopPlaying()
        lifecycleScope.launch {
            if (!sendOne(row)) return@launch
            val pushed = flushNow()
            KxkbToast.show(
                requireContext(),
                if (pushed.sent.isNotEmpty()) {
                    getString(R.string.voice_capture_sent_one)
                } else {
                    getString(R.string.voice_capture_queued_one)
                }
            )
            rebuild()
        }
    }

    private fun sendAll() {
        if (!storeReady()) return
        stopPlaying()
        lifecycleScope.launch {
            val accepted = rows.count { sendOne(it) }
            if (accepted == 0) return@launch
            val pushed = flushNow()
            KxkbToast.show(
                requireContext(),
                if (pushed.sent.isNotEmpty()) {
                    getString(R.string.voice_capture_sent_many, pushed.sent.size)
                } else {
                    getString(R.string.voice_capture_queued_many, accepted)
                }
            )
            rebuild()
        }
    }

    /** Push what is already queued but unacknowledged — the retry when 自由作業盤 was not there. */
    private fun resend() {
        lifecycleScope.launch {
            val pushed = flushNow()
            val message = if (pushed.sent.isNotEmpty()) {
                getString(R.string.voice_capture_sent_many, pushed.sent.size)
            } else {
                getString(
                    R.string.voice_capture_handoff_failed,
                    pushed.error ?: getString(R.string.voice_capture_handoff_none)
                )
            }
            KxkbToast.show(requireContext(), message)
            rebuild()
        }
    }

    /**
     * Push the queue to 自由作業盤. Only the uuids it names as stored leave the queue, so a refusal, an app
     * that is not installed and a phone that answered nothing all come to the same safe thing: the
     * sentences stay queued and go out next time.
     */
    private suspend fun flushNow(): GengoshimaHandoff.Pushed =
        withContext(Dispatchers.IO) { handoff.pushAll(requireContext().contentResolver) }

    /** Nothing may be accepted while the word store is a stand-in: the learning would go nowhere. */
    private fun storeReady(): Boolean {
        if (DatabaseAvailability.isReal) return true
        KxkbToast.show(requireContext(), getString(R.string.voice_capture_no_store))
        return false
    }

    private fun confirmDrop(row: Row) {
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_corpus_delete)
            .setMessage(getString(R.string.voice_capture_drop_confirm))
            .setPositiveButton(R.string.voice_corpus_delete) { _, _ ->
                stopPlaying()
                store.remove(row.entry.uuid)
                rebuild()
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    private fun closeSettings() {
        stopPlaying()
        val activity = requireActivity()
        // Opened straight from 自由作業盤 or the space menu: close the whole settings task, not one page of it.
        runCatching { activity.finishAffinity() }.onFailure { activity.finish() }
    }

    // ---- decoding --------------------------------------------------------------------------------------

    private fun transcribeAll() {
        if (working) return
        if (VoiceModelStore.status(requireContext()) != VoiceModelStore.Status.INSTALLED) {
            KxkbToast.show(requireContext(), getString(R.string.voice_capture_no_model))
            return
        }
        working = true
        stopRequested = false
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            val pending = withContext(Dispatchers.IO) { store.entries().filter { !it.transcribed } }
            var decoded = 0
            var silent = 0
            showProgress(pending.size)
            // One run for the whole page: the engine loads once instead of per sentence.
            voiceInput.beginBatch()
            try {
                if (!awaitEngine()) {
                    KxkbToast.show(requireContext(), getString(R.string.voice_capture_no_model))
                    return@launch
                }
                for ((index, entry) in pending.withIndex()) {
                    if (stopRequested) break
                    updateProgress(
                        done = index,
                        total = pending.size,
                        step = getString(R.string.voice_capture_progress_item, index + 1, pending.size)
                    )
                    val samples = withContext(Dispatchers.IO) { store.readSamples(entry) }
                    val transcript = samples?.let { voiceInput.transcribeSamples(it, entry.language) }
                    val text = transcript?.let { punctuated(it.text, entry.language, settings) }
                    if (text.isNullOrBlank()) {
                        // A clip of silence, or audio the engine made nothing of: say so on the row rather
                        // than leave it looking untouched, so it can be deleted instead of retried for ever.
                        silent++
                        withContext(Dispatchers.IO) { store.countFailedAttempt(entry.uuid) }
                        updateProgress(index + 1, pending.size, null, getString(R.string.voice_capture_progress_silent))
                        continue
                    }
                    decoded++
                    withContext(Dispatchers.IO) {
                        store.saveTranscript(
                            uuid = entry.uuid,
                            recognized = text,
                            text = text,
                            rawWords = transcript.rawWords,
                            rawConfidences = transcript.rawConfidences?.toList()
                        )
                    }
                    updateProgress(index + 1, pending.size, null, text)
                }
            } finally {
                voiceInput.endBatch()
                dismissProgress()
                working = false
            }
            if (decoded > 0 || silent > 0) {
                KxkbToast.show(requireContext(), getString(R.string.voice_capture_progress_done, decoded, silent))
            }
            rebuild()
        }
    }

    /**
     * Wait for the engine while SAYING so. Loading the model set is seconds of native work that used to look
     * like a frozen page; the dialog names the step instead. False when the engine cannot be loaded at all.
     */
    private suspend fun awaitEngine(): Boolean {
        updateProgress(0, 0, getString(R.string.voice_capture_progress_model))
        var waited = 0L
        while (!voiceInput.engineReady) {
            if (voiceInput.engineFailed || stopRequested || waited >= ENGINE_WAIT_MS) return false
            delay(ENGINE_POLL_MS)
            waited += ENGINE_POLL_MS
        }
        return true
    }

    private fun showProgress(total: Int) {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            progressStep = text(getString(R.string.voice_capture_progress_model), 16f, yellow)
            addView(progressStep)
            progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = total.coerceAtLeast(1)
                progress = 0
                progressTintList = ColorStateList.valueOf(yellow)
                progressBackgroundTintList = ColorStateList.valueOf(0x66FFFF00)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            addView(progressBar)
            progressDetail = text("", 13f, dim).apply {
                setPadding(0, dp(8), 0, 0)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(progressDetail)
        }
        progressDialog = AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_capture_progress_title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(R.string.voice_capture_progress_stop) { _, _ -> stopRequested = true }
            .create()
            .also { it.show() }
    }

    /** [step] null keeps the current step line (a detail-only update after one sentence). */
    private fun updateProgress(done: Int, total: Int, step: String?, detail: String? = null) {
        if (step != null) progressStep?.text = step
        if (total > 0) {
            progressBar?.max = total
            progressBar?.progress = done
        }
        if (detail != null) progressDetail?.text = detail
    }

    private fun dismissProgress() {
        runCatching { progressDialog?.dismiss() }
        progressDialog = null
        progressStep = null
        progressDetail = null
        progressBar = null
    }

    /**
     * The same two clean-ups the dictation path applies, in the same order: spoken punctuation first, so
     * the marks and the corpus see the final text, then the Czech clause commas Whisper leaves out. The
     * whitespace is normalised because the review's word offsets and its rendering must agree exactly.
     */
    private fun punctuated(raw: String, language: String, settings: KeyboardSettings): String {
        val lang = language.substringBefore("-")
        val spoken = if (settings.voiceSpokenPunctuation) SpokenPunctuation.apply(raw, lang) else raw
        val commas = if (settings.voiceCzechCommas && lang == "cs") CzechCommas.apply(spoken) else spoken
        return commas.replace(WHITESPACE, " ").trim()
    }

    // ---- playback --------------------------------------------------------------------------------------

    private fun togglePlay(row: Row, button: Button) {
        val wasThis = playingButton === button
        stopPlaying()
        if (wasThis) return
        val clip = store.clipFile(row.entry) ?: return
        try {
            player = MediaPlayer().apply {
                setDataSource(clip.absolutePath)
                setOnCompletionListener { stopPlaying() }
                prepare()
                start()
            }
            playingButton = button
            button.text = getString(R.string.voice_corpus_stop)
        } catch (e: Exception) {
            stopPlaying()
            KxkbToast.show(requireContext(), e.message ?: "")
        }
    }

    private fun stopPlaying() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        playingButton?.text = getString(R.string.voice_corpus_play)
        playingButton = null
    }

    // ---- house style -----------------------------------------------------------------------------------

    private fun text(value: String, sizeSp: Float, color: Int) = TextView(requireContext()).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun pill(label: String, onClick: () -> Unit): Button = Button(requireContext()).apply {
        text = label
        setTextColor(yellow)
        isAllCaps = false
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            setStroke(dp(2), yellow)
            cornerRadius = dp(50).toFloat()
        }
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(16), dp(8), dp(16), dp(8))
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
            marginEnd = dp(10)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        private val WHITESPACE = Regex("\\s+")

        /** How long the page waits for the model set to load before giving up on a run. */
        private const val ENGINE_WAIT_MS = 120_000L
        private const val ENGINE_POLL_MS = 150L
    }
}
