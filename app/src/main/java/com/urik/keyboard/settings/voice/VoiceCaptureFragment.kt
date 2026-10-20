package com.urik.keyboard.settings.voice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
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
import com.urik.keyboard.service.voice.VoiceLanguages
import com.urik.keyboard.service.voice.VoiceLongSplit
import com.urik.keyboard.service.voice.VoiceModelStore
import com.urik.keyboard.service.voice.VoiceOggExport
import com.urik.keyboard.service.voice.VoiceSessionLedger
import com.urik.keyboard.service.voice.VoiceSettledWords
import com.urik.keyboard.service.voice.VoiceTranscript
import com.urik.keyboard.service.voice.VoiceWordJudge
import com.urik.keyboard.settings.KeyboardSettings
import com.urik.keyboard.settings.SettingsActivity
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.ui.keyboard.components.FlowLayout
import com.urik.keyboard.ui.keyboard.components.VoiceReviewColors
import com.urik.keyboard.utils.KxkbToast
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
 *
 * A row may also be a long RECORDING rather than a sentence (contract §9): the walk-capture press doubles
 * as 白い熊's plain audio recorder, so one press-to-press may be hours long, and Whisper hears thirty
 * seconds. Such a row is never decoded — what it is FOR is decided here: kept as an Opus OGG, cut into
 * sentences at its own pauses, or both, and the recording goes when the last thing wanted from it is done.
 *
 * **Any** row's recording can be kept, not only a long one (白い熊, 2026-10-20): what makes audio worth
 * keeping is what was said into it, which has nothing to do with how long the press was. So "Save as OGG"
 * sits on every row that still has its recording, and the directory they are written to is picked once at
 * the top of the page — the page asks for it the first time it is needed, and that ask is also what
 * requests the file access, since the directory is 白い熊's own, not somewhere inside this app.
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
        val sentence: VoiceSessionLedger.Entry?,
        /** Deleted on this page, not yet in the store: it shows its Undo instead of its sentence. */
        val dropped: Boolean = false
    )

    private lateinit var store: VoiceCaptureStore
    private lateinit var outbox: VoiceHandoffOutbox
    private lateinit var handoff: GengoshimaHandoff
    private lateinit var root: LinearLayout
    private var player: MediaPlayer? = null
    private var playingButton: Button? = null
    private var playingUuid: String? = null

    /** Where each long recording is being listened from, so its position survives a re-render. */
    private val playFrom = mutableMapOf<String, Int>()

    /** The seek bar and its clock, per long row on the page, for the one that is playing to move. */
    private val seekViews = mutableMapOf<String, Pair<SeekBar, TextView>>()
    private var working = false
    private val rows = mutableListOf<Row>()

    /** The directory kept recordings are written to, as it is set now. Null = not picked yet. */
    private var recordingDir: String? = null

    /**
     * The order of the pills under a row, as 白い熊 has dragged them. One order for the whole page: a row
     * renders the controls it has in this sequence, so a pill moved under one sentence moves under all of
     * them ([VoiceCaptureControls]).
     */
    private var buttonOrder: List<String> = VoiceCaptureControls.DEFAULT

    /** The pill being dragged and the row it was lifted from, so a drop lands only where it started. */
    private var draggingId: String? = null
    private var draggingUuid: String? = null

    /**
     * Sentences deleted on this page but not yet in the store. Deleting a walk sentence throws away a
     * recording that cannot be made again — the walk is over — so the tap does not reach the store: the row
     * collapses to what it held, struck through, with the way back beside it, and the removal is committed
     * when the page goes. A mis-tap costs nothing, and the worst a killed process can do is leave a
     * sentence standing.
     */
    private val pendingDrops = linkedSetOf<String>()

    // The transcription run's progress dialog — the load alone is seconds, which read as "stuck" before.
    private var progressDialog: AlertDialog? = null
    private var progressStep: TextView? = null
    private var progressDetail: TextView? = null
    private var progressBar: ProgressBar? = null
    private var stopRequested = false

    private val yellow = 0xFFFFFF00.toInt()
    private val dim = 0xFFCCCC66.toInt()

    // The Export/import page's warn colour, for the same thing it warns about there: a directory not
    // chosen yet. Deliberately NOT one of the four settable mark colours below — those mean something about a
    // word, and a page that borrowed one would change meaning when 白い熊 re-set it.
    private val warn = 0xFFFF6666.toInt()

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
        savedInstanceState?.getStringArrayList(STATE_DROPS)?.let { pendingDrops.addAll(it) }
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

    /** A fold or a rotation rebuilds this page; the deletions waiting on it must survive that. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_DROPS, ArrayList(pendingDrops))
    }

    override fun onDestroyView() {
        // Only when the page is really going. A recreation — 白い熊 unfolds the phone mid-review — is not
        // the end of the undo window: the uuids ride along in the instance state and the stubs come back.
        if (isRemoving || requireActivity().isFinishing) commitDrops()
        super.onDestroyView()
        dismissProgress()
    }

    // ---- the page --------------------------------------------------------------------------------------

    private fun rebuild() {
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            val threshold = settings.voiceUncertaintyPercent / 100f
            loadMarkColours()
            recordingDir = settingsRepository.getVoiceRecordingPath()
            buttonOrder = settingsRepository.getVoiceCaptureButtonOrder()
            val entries = withContext(Dispatchers.IO) {
                store.sweepOrphans()
                store.entries()
            }
            rows.clear()
            for (entry in entries) {
                // A dropped row is never judged: it is on its way out, and the work would be thrown away.
                val row = if (entry.uuid in pendingDrops) {
                    Row(entry, null, null, dropped = true)
                } else {
                    rowFor(entry, threshold)
                }
                rows.add(row)
            }
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
     *
     * The page is rebuilt after every save, and judging a FRESH decode rewrites the recognitions you have
     * corrected before — which is the point of correcting them. A sentence you have already settled
     * ([VoiceCaptureStore.Entry.edited]) is judged for its marks alone: a correction made in sentence #10
     * may never reach back into the nine sentences you approved before it, nor undo itself in the one it
     * was made in.
     *
     * Judging alone, though, cannot know what YOU did to the individual words — which one you corrected,
     * which one an earlier correction replaced — so those two marks come back from the store
     * ([VoiceSettledWords]) instead of being re-derived. Without them a settled sentence lost its blue and
     * its green on every save and every fold of the phone, whole words at a time, including the words the
     * edit never touched.
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
            spellCheckManager = spellCheckManager,
            replace = !entry.edited
        )
        val settled = entry.settled.orEmpty()
        val ledger = VoiceSessionLedger()
        val sentence = ledger.add(
            utteranceId = entry.uuid,
            language = entry.language,
            committedText = judged.transcript.text,
            fieldStart = 0,
            confidences = judged.confidences,
            judgements = judged.judgements,
            samples = null,
            // The decode's own replacements for a fresh row; for a settled one, what YOU settled — the two
            // never both carry a word, since storing the settled words is what makes a row settled.
            originals = judged.originals + VoiceSettledWords.originals(settled),
            correctedAt = VoiceSettledWords.correctedAt(settled),
            clipPath = clipOf(entry)?.absolutePath
        )
        return Row(entry, ledger, sentence)
    }

    private fun render() {
        root.removeAllViews()
        seekViews.clear()
        root.addView(text(getString(R.string.voice_capture_title), 22f, yellow))
        val used = store.usedBytes() / (1024.0 * 1024.0)
        val budget = store.budgetBytes() / (1024.0 * 1024.0)
        // A long recording is not a sentence waiting to be reviewed, and counting it as one made the page
        // promise work that "Transcribe all" would never do.
        val waitingRows = rows.count { !it.dropped && !it.entry.isLong }
        root.addView(
            text(getString(R.string.voice_capture_summary, waitingRows, used, budget), 13f, dim)
                .apply { setPadding(0, dp(6), 0, 0) }
        )
        val longRows = rows.count { !it.dropped && it.entry.isLong }
        if (longRows > 0) {
            root.addView(
                text(getString(R.string.voice_capture_long_waiting, longRows), 13f, dim)
                    .apply { setPadding(0, dp(2), 0, 0) }
            )
        }
        val waiting = outbox.pendingCount()
        root.addView(
            text(getString(R.string.voice_capture_handoff_state, waiting, outbox.sentCount()), 13f, dim)
                .apply { setPadding(0, dp(2), 0, 0) }
        )
        root.addView(text(getString(R.string.voice_capture_intro), 13f, dim).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(directoryBox())
        root.addView(legend())
        if (rows.isNotEmpty()) {
            // A long press is invisible until it is named; without this the pills look fixed.
            root.addView(
                text(getString(R.string.voice_capture_reorder_hint), 12f, dim)
                    .apply { setPadding(0, dp(4), 0, 0) }
            )
        }
        if (rows.isNotEmpty()) root.addView(markAllRow())
        if (rows.any { !it.dropped && !it.entry.isLong && !it.entry.transcribed }) {
            root.addView(pill(getString(R.string.voice_capture_transcribe_all)) { transcribeAll() })
        }
        if (rows.isEmpty()) {
            root.addView(text(getString(R.string.voice_capture_empty), 14f, dim).apply { setPadding(0, dp(16), 0, 0) })
        } else {
            for (row in rows) root.addView(rowView(row))
        }
        root.addView(bottomBar(waiting))
    }

    // ---- the recordings directory ---------------------------------------------------------------------

    /**
     * Where kept recordings go, in the same bordered box the Export/import page uses for the backup
     * directory: a small label over the path itself, warn-red while it is unset, and the whole box a tap
     * into the picker. It sits above the rows because it is a fact about the page, not about one recording.
     */
    private fun directoryBox(): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            setStroke(dp(2), yellow)
            cornerRadius = dp(10).toFloat()
        }
        val set = recordingDir?.takeIf { it.isNotBlank() }
        setOnClickListener { editRecordingDir(set) }
        addView(text(getString(R.string.voice_capture_ogg_dir_label), 12f, yellow))
        addView(
            text(set ?: getString(R.string.voice_capture_ogg_dir_unset), 15f, if (set == null) warn else dim)
                .apply { setTypeface(typeface, Typeface.BOLD) }
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10); bottomMargin = dp(4) }
    }

    /**
     * The directory, typed or browsed to. [OGG_DIR] under the shared storage root is only ever a
     * SUGGESTION — the hint in the box and where the browser opens — and never a silent default: a
     * recording written to a directory 白い熊 did not name is a file they have to go looking for.
     */
    private fun editRecordingDir(current: String?, resume: String? = null) {
        val suggestion = File(Environment.getExternalStorageDirectory(), OGG_DIR)
        val input = EditText(requireContext()).apply {
            setText(current ?: "")
            hint = suggestion.absolutePath
            setSingleLine()
            setTextColor(yellow)
        }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_capture_ogg_dir_title)
            .setMessage(getString(R.string.voice_capture_ogg_dir_message, suggestion.absolutePath))
            .setView(box)
            .setPositiveButton(R.string.export_import_save) { _, _ ->
                saveRecordingDir(input.text.toString(), resume)
            }
            .setNeutralButton(R.string.export_import_browse) { _, _ ->
                val start = current?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }
                    ?: suggestion.takeIf { it.isDirectory }
                    ?: Environment.getExternalStorageDirectory()
                browseForDirectory(start) { picked -> saveRecordingDir(picked.absolutePath, resume) }
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    /**
     * [resume] is the recording whose "Save as OGG" asked for the directory in the first place: picking one
     * is the first half of that gesture, not an errand of its own, so the save goes on from here instead of
     * making 白い熊 find the row again and press it a second time. Only that path rebuilds afterwards (the
     * save does its own), so two renders cannot race and show the pill back on a recording already kept.
     */
    private fun saveRecordingDir(path: String, resume: String? = null) {
        lifecycleScope.launch {
            settingsRepository.setVoiceRecordingPath(path)
            recordingDir = settingsRepository.getVoiceRecordingPath()
            val row = resume?.let { uuid -> rows.firstOrNull { it.entry.uuid == uuid && !it.dropped } }
            if (row != null && recordingDir != null) saveOgg(row) else rebuild()
        }
    }

    /**
     * One dialog per directory: the list walks, the buttons decide. "Use this directory" is the POSITIVE
     * button, so it sits at the bottom right of Cancel (白い熊, 2026-10-20) — the decision belongs where a
     * dialog's decisions are, not at the top of a list of places to go, where it read as another place.
     */
    private fun browseForDirectory(dir: File, onPick: (File) -> Unit) {
        if (!hasAllFilesAccess()) {
            requestAllFilesAccess()
            return
        }
        val subDirs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val labels = mutableListOf<String>()
        val targets = mutableListOf<File>()
        dir.parentFile?.let {
            labels.add(".. (" + it.name.ifBlank { "/" } + ")")
            targets.add(it)
        }
        subDirs.forEach {
            labels.add("📁 " + it.name)
            targets.add(it)
        }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(dir.absolutePath)
            .setItems(labels.toTypedArray()) { _, which -> browseForDirectory(targets[which], onPick) }
            .setPositiveButton(R.string.export_import_pick_here) { _, _ -> onPick(dir) }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    /**
     * The directory to write into, or null — having asked for whatever is missing. Pressing "Save as OGG"
     * with nothing set is not an error to report: it is the first half of the same gesture, so it opens the
     * picker (白い熊, 2026-10-20) and [resume] carries the recording on into the save once a directory is
     * chosen. The file access comes first, since the browser cannot list a single directory without it —
     * and that one cannot be resumed: granting it happens in the system settings, outside this page.
     */
    private fun recordingDirOrAsk(resume: String? = null): File? {
        if (!hasAllFilesAccess()) {
            KxkbToast.show(requireContext(), getString(R.string.voice_capture_ogg_no_access))
            requestAllFilesAccess()
            return null
        }
        val set = recordingDir?.takeIf { it.isNotBlank() }
        if (set == null) {
            KxkbToast.show(requireContext(), getString(R.string.voice_capture_ogg_dir_needed))
            editRecordingDir(null, resume)
            return null
        }
        val dir = File(set)
        if (!dir.isDirectory && !dir.mkdirs()) {
            KxkbToast.show(requireContext(), getString(R.string.voice_capture_ogg_dir_failed, set))
            editRecordingDir(set, resume)
            return null
        }
        return dir
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
            TextView(requireContext()).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(dim)
                text = rowHeader(entry)
            }
        )
        if (row.dropped) {
            addView(droppedBody(row))
        } else if (entry.isLong) {
            addView(longBody(row))
        } else if (row.sentence == null) {
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
        // A long row says this inside [longBody], with the rest of what has become of it.
        if (!row.dropped && !entry.isLong) {
            entry.ogg?.let { addView(text(getString(R.string.voice_capture_long_ogg, it), 12f, yellow)) }
        }
        addView(if (!row.dropped && entry.isLong) longButtons(row) else buttonRow(row))
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
     * When it was spoken and how long it ran. A long recording says so in words as well — bold, and not by
     * colour alone: it is the one row on the page that behaves differently from all the others, and 白い熊
     * is red–green colour-blind. Its length reads as a clock, since 12:34 means nothing as "754.0 s".
     */
    private fun rowHeader(entry: VoiceCaptureStore.Entry): CharSequence {
        val spoken = stamp.format(Date(entry.capturedAt))
        if (!entry.isLong) {
            return "$spoken · " + String.format(Locale.ROOT, "%.1f s", entry.durationMs / 1000.0)
        }
        val sb = SpannableStringBuilder("$spoken · ")
        val start = sb.length
        sb.append(getString(R.string.voice_capture_long_label))
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" · ").append(clock(entry.durationMs))
        return sb
    }

    /** Milliseconds as a clock: `12:34`, and `1:02:03` once there is an hour of it. */
    private fun clock(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        return if (minutes >= 60) {
            String.format(Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60)
        }
    }

    /** What a deleted sentence leaves behind until the page goes: what it held, struck through. */
    private fun droppedBody(row: Row): View = TextView(requireContext()).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(dim)
        setPadding(0, dp(2), 0, 0)
        val held = row.entry.text
            ?: getString(
                if (row.entry.isLong) R.string.voice_capture_long_label else R.string.voice_capture_not_decoded
            )
        text = SpannableStringBuilder(held).apply {
            setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * The row's own controls, in a flow that WRAPS. A fixed row ran off the edge of a folded screen with
     * four pills on it, and there are six here now — the language pill alone is as wide as two — so the
     * line breaks where the screen ends instead, and unfolding the phone simply fits more on a line.
     *
     * They are built as a MAP and laid out by [controlFlow] in 白い熊's own order, which is why a row that
     * happens to offer only four of them still shows those four where they belong.
     *
     * "Send to 白い熊 自由作業盤" keeps a line of its own beneath them, as before: it is the one that cannot
     * be taken back, and it should not drift under a thumb aimed at Share — so it is not one of the pills
     * that can be dragged, either.
     */
    private fun buttonRow(row: Row): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        if (row.dropped) {
            addView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(pill(getString(R.string.voice_capture_undo)) { undoDrop(row) })
                    addView(text(getString(R.string.voice_capture_dropped), 12f, dim))
                }
            )
            return@apply
        }
        val controls = linkedMapOf<String, Button>()
        controls[VoiceCaptureControls.LANGUAGE] = languagePill(row)
        // Both of these need the recording itself, and a corrected sentence's clip has moved into the voice
        // corpus — which is exactly what [clipOf] follows.
        if (clipOf(row.entry) != null) {
            lateinit var play: Button
            play = pill(getString(R.string.voice_corpus_play)) { togglePlay(row, play) }
            controls[VoiceCaptureControls.PLAY] = play
            controls[VoiceCaptureControls.TRANSCRIBE] = pill(transcribeLabel(row)) { transcribeOne(row) }
            // Keeping the audio has nothing to do with the length of the press: a sentence worth keeping is
            // kept the same way an hour is (白い熊, 2026-10-20). Offered once — a second press would only
            // write the same recording out under a second name.
            // On an Android too old for the OGG muxer the pill is simply absent here: a long row still
            // offers it, because there the toast explains an alternative (cutting) that a sentence does not.
            if (row.entry.ogg == null && VoiceOggExport.supported) {
                controls[VoiceCaptureControls.SAVE_OGG] = pill(getString(R.string.voice_capture_save_ogg)) {
                    saveOgg(row)
                }
            }
        }
        controls[VoiceCaptureControls.EDIT] = pill(getString(R.string.voice_capture_edit)) { editSentence(row) }
        if (textOf(row) != null) {
            controls[VoiceCaptureControls.COPY] = pill(getString(R.string.voice_capture_copy)) { copySentence(row) }
            controls[VoiceCaptureControls.SHARE] = pill(getString(R.string.voice_capture_share)) { shareSentence(row) }
        }
        controls[VoiceCaptureControls.DELETE] = pill(getString(R.string.voice_corpus_delete)) { confirmDrop(row) }
        addView(controlFlow(row, controls))
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
     * The row's controls, laid out in 白い熊's order and draggable into another one (白い熊, 2026-10-20).
     *
     * [controls] is what THIS row offers, keyed by id; the sequence comes from [buttonOrder], which covers
     * every control there is — so a row renders its own subset in the one order, and moving a pill here
     * moves it under every line. A long press lifts a pill into a framework drag, the flow re-orders its
     * children live under the finger, and the drop writes the new order down.
     *
     * The drag is confined to the row it started in: every row has a pill with the same id, so a listener
     * that accepted any drag would let a Delete lifted from the third sentence be dropped among the first
     * one's pills, where it would mean nothing.
     */
    private fun controlFlow(row: Row, controls: Map<String, Button>): View =
        FlowLayout(requireContext()).apply {
            // The pills carry their own margins for a LinearLayout, which a flow ignores; these are the
            // same two gaps, in the one place the flow reads them.
            horizontalSpacing = dp(10)
            verticalSpacing = dp(8)
            setPadding(0, dp(8), 0, 0)
            for (id in buttonOrder) {
                val control = controls[id] ?: continue
                control.tag = id
                control.setOnLongClickListener { lifted -> liftControl(lifted, id, row.entry.uuid) }
                addView(control)
            }
            setOnDragListener { view, event -> onControlDrag(row.entry.uuid, view as FlowLayout, event) }
        }

    /**
     * Long press = lift. The pill stays in place, dimmed, while its shadow follows the finger.
     *
     * What is being dragged is written down BEFORE the drag starts: the framework delivers
     * ACTION_DRAG_STARTED off the back of this call, and a listener that read the fields after it would
     * refuse the very drag it had just begun. [DragEvent.getClipData] is null until the drop, so the id
     * cannot simply be read back off the event.
     */
    private fun liftControl(control: View, id: String, uuid: String): Boolean {
        draggingId = id
        draggingUuid = uuid
        control.alpha = LIFTED_ALPHA
        val data = ClipData.newPlainText(DRAG_CONTROL, id)
        val started =
            control.startDragAndDrop(data, View.DragShadowBuilder(control), null, View.DRAG_FLAG_OPAQUE)
        if (!started) {
            draggingId = null
            draggingUuid = null
            control.alpha = 1f
        }
        return started
    }

    private fun onControlDrag(uuid: String, flow: FlowLayout, event: DragEvent): Boolean {
        val id = draggingId ?: return false
        if (uuid != draggingUuid) return false
        val lifted = flow.findViewWithTag<View>(id)
        return when (event.action) {
            // Accepted only by the flow the pill was lifted from — see [controlFlow].
            DragEvent.ACTION_DRAG_STARTED ->
                lifted != null && event.clipDescription?.label == DRAG_CONTROL
            DragEvent.ACTION_DRAG_LOCATION -> {
                if (lifted == null) return false
                val slot = dropSlot(flow, lifted, event.x, event.y)
                if (slot != flow.indexOfChild(lifted)) {
                    flow.removeView(lifted)
                    flow.addView(lifted, slot)
                }
                true
            }
            DragEvent.ACTION_DROP -> {
                if (lifted == null) return false
                val shown = (0 until flow.childCount).mapNotNull { flow.getChildAt(it).tag as? String }
                // Put the pill down here rather than waiting for the redraw: ACTION_DRAG_ENDED arrives
                // after this, by which time there is nothing being dragged for it to tidy up.
                lifted.alpha = 1f
                draggingId = null
                draggingUuid = null
                saveControlOrder(id, shown)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                lifted?.alpha = 1f
                draggingId = null
                draggingUuid = null
                true
            }
            else -> true
        }
    }

    /**
     * Where the finger says the lifted pill now belongs: after every pill it has already passed in reading
     * order — a whole line above counts as passed, and within a line the pill's own midpoint is the
     * boundary, so a pill swaps as soon as the finger is more than half way across its neighbour.
     */
    private fun dropSlot(flow: FlowLayout, lifted: View, x: Float, y: Float): Int {
        var slot = 0
        for (i in 0 until flow.childCount) {
            val child = flow.getChildAt(i)
            if (child === lifted) continue
            val passed = y > child.bottom || (y >= child.top && x > (child.left + child.right) / 2f)
            if (!passed) break
            slot++
        }
        return slot
    }

    /**
     * Write the dragged pill's new place down and redraw every row in it. [render] is enough — the rows
     * themselves have not changed, only the order their pills are built in.
     */
    private fun saveControlOrder(dragged: String, shown: List<String>) {
        val moved = VoiceCaptureControls.moveWithin(buttonOrder, dragged, shown)
        if (moved == buttonOrder) {
            render()
            return
        }
        buttonOrder = moved
        lifecycleScope.launch {
            settingsRepository.setVoiceCaptureButtonOrder(moved)
            render()
        }
    }

    /**
     * The sentence as it now reads — what Copy and Share hand on. The ledger's text when the row is
     * decoded (so a correction made here travels with it), the stored text otherwise, and null when there
     * is nothing to hand on at all.
     */
    private fun textOf(row: Row): String? = (row.sentence?.currentText() ?: row.entry.text)?.takeIf { it.isNotBlank() }

    /**
     * The sentence on the clipboard. The review page is where a walk sentence is finished, but not always
     * where it is wanted: this is the way one of them reaches a note, a message or a search without going
     * through 言語島 at all.
     */
    private fun copySentence(row: Row) {
        val text = textOf(row) ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            KxkbToast.show(requireContext(), getString(R.string.voice_capture_share_failed))
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.voice_capture_title), text))
        KxkbToast.show(requireContext(), getString(R.string.voice_capture_copied))
    }

    /**
     * The same sentence handed to another app. Plain `text/plain` and nothing else: the recording stays
     * here — it is the keyboard's training material and belongs to the voice corpus, not to whatever app
     * the chooser lands on.
     */
    private fun shareSentence(row: Row) {
        val text = textOf(row) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            startActivity(Intent.createChooser(send, getString(R.string.voice_capture_share_title)))
        }.onFailure {
            KxkbToast.show(requireContext(), getString(R.string.voice_capture_share_failed))
        }
    }

    /**
     * Edit the whole sentence as text — the way to fix PUNCTUATION, which the per-word box cannot touch: it
     * opens on a word's core, and a correction keeps whatever punctuation stood around it. Whisper ending a
     * sentence mid-utterance ("…the river. The water was cold.") is exactly this case, and only a comma in
     * place of that period fixes it.
     *
     * A hand edit re-derives the row from the new text, and it costs only the words it really changed: the
     * confidences re-align onto the new words by their cores, so a changed comma keeps them, and the two
     * marks that are events rather than judgements — a word you corrected, a word an earlier correction
     * replaced — are carried across the same way ([VoiceSettledWords.carry]). The recognition stays on file
     * as the "Heard:" line. It teaches the keyboard nothing by itself — a word you want remembered is
     * better corrected by tapping it, which stores the pair — and the box says so.
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
        val sentence = row.sentence
        if (entry.transcribed) {
            // The words you did not change keep what they were marked as; the ones you did keep nothing.
            val settled = if (sentence == null) {
                emptyList()
            } else {
                VoiceSettledWords.carry(sentence.currentText(), text, VoiceSettledWords.of(sentence))
            }
            store.saveText(entry.uuid, text, settled)
        } else {
            // Nothing was recognised here: the typed text becomes both the sentence and its "recognition",
            // with no word confidences to align — there was no decode to take them from.
            store.saveTranscript(
                uuid = entry.uuid,
                recognized = text,
                text = text,
                rawWords = null,
                rawConfidences = null,
                edited = true
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

    // ---- the transcription language --------------------------------------------------------------------

    /**
     * The language this sentence is decoded as, as a pill that shows BOTH halves of the pair with the
     * current one lit and bold: the whole page then reads at a glance as which sentences are Czech, and a
     * tap is the switch. Bold as well as bright on purpose — a brightness difference alone is a poor thing
     * to hang the meaning of a row on.
     */
    private fun languagePill(row: Row): Button {
        val current = VoiceLanguages.captureOrDefault(row.entry.language)
        val label = SpannableStringBuilder()
        for (code in VoiceLanguages.CAPTURE_PAIR) {
            if (label.isNotEmpty()) label.append(" ⇄ ")
            val start = label.length
            label.append(VoiceLanguages.displayName(code))
            val mark: Any =
                if (code == current) StyleSpan(Typeface.BOLD) else ForegroundColorSpan(dim)
            label.setSpan(mark, start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return pill(label) { flipLanguage(row) }
    }

    /**
     * Switch one sentence to the other language. A sentence already decoded loses that decode — it was made
     * in the other language — so this asks first; an undecoded one has nothing to lose and switches at once.
     */
    private fun flipLanguage(row: Row) {
        val target = VoiceLanguages.otherOfPair(row.entry.language)
        if (!row.entry.transcribed) {
            applyLanguage(row.entry.uuid, target)
            return
        }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_capture_lang_title)
            .setMessage(
                getString(R.string.voice_capture_lang_confirm_one, VoiceLanguages.displayName(target))
            )
            .setPositiveButton(R.string.voice_capture_lang_again) { _, _ ->
                applyLanguage(row.entry.uuid, target)
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    private fun applyLanguage(uuid: String, target: String) {
        stopPlaying()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.setLanguage(uuid, target) }
            rebuild()
        }
    }

    /** The whole walk in one gesture: usually every sentence of it was spoken in the same language. */
    private fun markAllRow(): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        for (code in VoiceLanguages.CAPTURE_PAIR) {
            addView(
                pill(getString(R.string.voice_capture_lang_all, VoiceLanguages.displayName(code))) {
                    markAll(code)
                }
            )
        }
    }

    private fun markAll(target: String) {
        val changing = rows.count {
            !it.dropped && VoiceLanguages.captureOrDefault(it.entry.language) != target
        }
        if (changing == 0) return
        val losing = rows.count {
            !it.dropped && VoiceLanguages.captureOrDefault(it.entry.language) != target && it.entry.transcribed
        }
        if (losing == 0) {
            applyLanguageToAll(target)
            return
        }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_capture_lang_title)
            .setMessage(
                getString(
                    R.string.voice_capture_lang_confirm_many,
                    changing,
                    VoiceLanguages.displayName(target),
                    losing
                )
            )
            .setPositiveButton(R.string.voice_capture_lang_again) { _, _ -> applyLanguageToAll(target) }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    private fun applyLanguageToAll(target: String) {
        stopPlaying()
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) { store.setLanguageForAll(target) }
            if (changed > 0) {
                KxkbToast.show(
                    requireContext(),
                    getString(
                        R.string.voice_capture_lang_marked,
                        changed,
                        VoiceLanguages.displayName(target)
                    )
                )
            }
            rebuild()
        }
    }

    // ---- long recordings (contract §9) -----------------------------------------------------------------

    /** What has become of this recording so far, and the bar to listen through it with. */
    private fun longBody(row: Row): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(2), 0, 0)
        val entry = row.entry
        val done = mutableListOf<String>()
        entry.ogg?.let { done.add(getString(R.string.voice_capture_long_ogg, it)) }
        if (entry.pieces > 0) done.add(getString(R.string.voice_capture_long_pieces, entry.pieces))
        if (done.isEmpty()) {
            addView(text(getString(R.string.voice_capture_long_untouched), 14f, dim))
        } else {
            for (line in done) addView(text(line, 14f, yellow))
        }
        if (entry.salvaged) addView(text(getString(R.string.voice_capture_long_salvaged), 12f, dim))
        if (clipOf(entry) != null) addView(seekView(row))
    }

    /**
     * The seek bar, because a recording of this length is not listened to from the start every time. It is
     * draggable while nothing is playing too: the position is remembered per recording and the player is
     * started there.
     */
    private fun seekView(row: Row): View {
        val uuid = row.entry.uuid
        val duration = row.entry.durationMs
        val label = text(
            getString(R.string.voice_capture_position, clock((playFrom[uuid] ?: 0).toLong()), clock(duration)),
            12f,
            dim
        )
        val bar = SeekBar(requireContext()).apply {
            max = duration.toInt().coerceAtLeast(1)
            progress = playFrom[uuid] ?: 0
            progressTintList = ColorStateList.valueOf(yellow)
            progressBackgroundTintList = ColorStateList.valueOf(0x66FFFF00)
            thumbTintList = ColorStateList.valueOf(yellow)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        playFrom[uuid] = value
                        label.text =
                            getString(R.string.voice_capture_position, clock(value.toLong()), clock(duration))
                        if (playingUuid == uuid) runCatching { player?.seekTo(value) }
                    }

                    override fun onStartTrackingTouch(view: SeekBar) = Unit

                    override fun onStopTrackingTouch(view: SeekBar) = Unit
                }
            )
        }
        seekViews[uuid] = bar to label
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(label)
        }
    }

    /**
     * A recording's own controls. Each of the two things that can be made of it is offered once: a second
     * OGG of the same recording would be a second file under a different name, and a second cut a second
     * set of the same sentences. What has been done is on the row; when both have, the recording goes.
     *
     * The same five ids a sentence row uses, laid out by the same [controlFlow] in the same one order — a
     * recording and a sentence are not two different pages.
     */
    private fun longButtons(row: Row): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        val controls = linkedMapOf<String, Button>()
        // The language is the one the PIECES will be decoded in, so it is set before cutting.
        controls[VoiceCaptureControls.LANGUAGE] = languagePill(row)
        if (clipOf(row.entry) != null) {
            lateinit var play: Button
            play = pill(getString(R.string.voice_corpus_play)) { togglePlay(row, play) }
            controls[VoiceCaptureControls.PLAY] = play
            if (row.entry.ogg == null) {
                controls[VoiceCaptureControls.SAVE_OGG] = pill(getString(R.string.voice_capture_save_ogg)) {
                    saveOgg(row)
                }
            }
            if (row.entry.pieces <= 0) {
                controls[VoiceCaptureControls.SPLIT] = pill(getString(R.string.voice_capture_split)) {
                    splitLong(row)
                }
            }
        }
        controls[VoiceCaptureControls.DELETE] = pill(getString(R.string.voice_corpus_delete)) { confirmDrop(row) }
        addView(controlFlow(row, controls))
    }

    /**
     * Keep the recording as an Opus OGG in 白い熊's chosen directory, named by when it was spoken. Written as
     * a `.part` and renamed only when the encoder has finished, so a failed or interrupted run leaves no
     * file that plays as far as it got and then stops — and an existing name is never overwritten: the
     * recording cannot be made again, and neither can anything else in that directory.
     *
     * The same path serves a sentence and an hours-long recording. A sentence encodes in a blink, so it is
     * not given the progress dialog the long one needs — a box that appears and vanishes says nothing —
     * and nothing is removed when it is done: only a long recording goes, and only once BOTH things that
     * can be made of it have been ([finishLong]).
     */
    private fun saveOgg(row: Row) {
        if (working) return
        if (!VoiceOggExport.supported) {
            KxkbToast.show(requireContext(), getString(R.string.voice_capture_ogg_unsupported))
            return
        }
        val dir = recordingDirOrAsk(resume = row.entry.uuid) ?: return
        val clip = clipOf(row.entry) ?: return
        working = true
        stopRequested = false
        stopPlaying()
        lifecycleScope.launch {
            val seconds = (row.entry.durationMs / 1000).toInt().coerceAtLeast(1)
            val encoded = AtomicLong()
            val watched = row.entry.durationMs >= OGG_PROGRESS_MIN_MS
            if (watched) {
                showProgress(seconds)
                updateProgress(0, seconds, getString(R.string.voice_capture_ogg_step))
            }
            val ticker = launch {
                while (isActive && watched) {
                    updateProgress((encoded.get() / 1000).toInt(), seconds, null)
                    delay(PROGRESS_POLL_MS)
                }
            }
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val pcm = store.pcmOf(clip) ?: error("no audio")
                    val target = VoiceOggExport.targetIn(dir, row.entry.capturedAt)
                    val part = File(target.parentFile, target.name + ".part")
                    try {
                        val whole = VoiceOggExport.write(
                            source = clip,
                            dataOffset = pcm.offset,
                            dataBytes = pcm.bytes,
                            target = part,
                            stop = { stopRequested }
                        ) { ms -> encoded.set(ms) }
                        // Given up on: the part-file goes and the recording is exactly as it was. An OGG
                        // that stops halfway through an hour would look like a whole one in a file manager.
                        if (!whole) {
                            part.delete()
                            return@runCatching null
                        }
                        if (!part.renameTo(target)) error(target.name)
                    } catch (e: Throwable) {
                        part.delete()
                        throw e
                    }
                    target
                }
            }
            ticker.cancel()
            dismissProgress()
            working = false
            written
                .onSuccess { target ->
                    if (target == null) {
                        KxkbToast.show(requireContext(), getString(R.string.voice_capture_long_stopped))
                        return@onSuccess
                    }
                    withContext(Dispatchers.IO) { store.markOgg(row.entry.uuid, target.absolutePath) }
                    val finished = row.entry.isLong && finishLong(row.entry.uuid)
                    KxkbToast.show(
                        requireContext(),
                        if (finished) {
                            getString(R.string.voice_capture_long_finished)
                        } else {
                            getString(R.string.voice_capture_ogg_done, target.absolutePath)
                        }
                    )
                    rebuild()
                }
                .onFailure { e ->
                    KxkbToast.show(
                        requireContext(),
                        getString(R.string.voice_capture_ogg_failed, e.message ?: e.javaClass.simpleName)
                    )
                }
        }
    }

    /**
     * Cut the recording into sentences at its own pauses, each of them an ordinary captured sentence from
     * there on — decoded, reviewed and sent exactly as one spoken into a single press would be. The
     * recording itself stays until the last thing wanted from it is done; nothing is lost if this goes
     * wrong halfway, the pieces written so far simply stand.
     */
    private fun splitLong(row: Row) {
        if (working) return
        val clip = clipOf(row.entry) ?: return
        working = true
        stopRequested = false
        stopPlaying()
        lifecycleScope.launch {
            val total = AtomicInteger()
            val cut = AtomicInteger()
            showProgress(1)
            updateProgress(0, 1, getString(R.string.voice_capture_split_pauses))
            val ticker = launch {
                while (isActive) {
                    val all = total.get()
                    if (all > 0) {
                        updateProgress(
                            cut.get(),
                            all,
                            getString(R.string.voice_capture_split_cutting, (cut.get() + 1).coerceAtMost(all), all)
                        )
                    }
                    delay(PROGRESS_POLL_MS)
                }
            }
            val made = withContext(Dispatchers.IO) {
                runCatching {
                    val pcm = store.pcmOf(clip) ?: return@runCatching 0
                    val plan = VoiceLongSplit.Detector().use { detector ->
                        VoiceLongSplit.plan(
                            VoiceLongSplit.verdicts(clip, pcm.offset, pcm.bytes, detector) { stopRequested }
                        )
                    }
                    // Given up on while listening through it: a plan made of half the recording would cut
                    // only half of it and then call the recording done.
                    if (stopRequested) return@runCatching 0
                    total.set(plan.size)
                    var made = 0
                    for (piece in plan) {
                        if (stopRequested) break
                        val bytes = VoiceLongSplit.read(clip, pcm.offset, piece)
                        if (store.addPiece(row.entry, piece.offsetMs, bytes) != null) made++
                        cut.set(made)
                    }
                    made
                }.getOrElse { -1 }
            }
            ticker.cancel()
            dismissProgress()
            working = false
            when {
                made < 0 -> KxkbToast.show(requireContext(), getString(R.string.voice_capture_split_failed))
                made == 0 && stopRequested ->
                    KxkbToast.show(requireContext(), getString(R.string.voice_capture_long_stopped))
                made == 0 -> KxkbToast.show(requireContext(), getString(R.string.voice_capture_split_silent))
                else -> {
                    withContext(Dispatchers.IO) { store.markSplit(row.entry.uuid, made) }
                    val finished = finishLong(row.entry.uuid)
                    KxkbToast.show(
                        requireContext(),
                        if (finished) {
                            getString(R.string.voice_capture_long_finished)
                        } else {
                            getString(R.string.voice_capture_split_done, made)
                        }
                    )
                }
            }
            rebuild()
        }
    }

    /**
     * The recording goes when BOTH things that can be made of it have been — never after the first, which
     * would decide for 白い熊 that the other was not wanted. True when it has just gone.
     */
    private suspend fun finishLong(uuid: String): Boolean {
        val entry = withContext(Dispatchers.IO) { store.entries().firstOrNull { it.uuid == uuid } } ?: return false
        if (entry.ogg == null || entry.pieces <= 0) return false
        withContext(Dispatchers.IO) { store.remove(uuid) }
        return true
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        val pkg = "package:" + requireContext().packageName
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse(pkg)))
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
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
        // With the text, what now stands about its words: this correction, and every mark the sentence
        // already carried. The page is rebuilt from the store immediately after.
        store.saveText(row.entry.uuid, sentence.currentText(), VoiceSettledWords.of(sentence))
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
            .setMessage(
                getString(
                    if (row.entry.isLong) {
                        R.string.voice_capture_long_drop_confirm
                    } else {
                        R.string.voice_capture_drop_confirm
                    }
                )
            )
            .setPositiveButton(R.string.voice_corpus_delete) { _, _ ->
                stopPlaying()
                pendingDrops.add(row.entry.uuid)
                rebuild()
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    private fun undoDrop(row: Row) {
        pendingDrops.remove(row.entry.uuid)
        rebuild()
    }

    /**
     * Make the deferred deletions real. The undo window IS the page, so this runs as the page goes — and
     * synchronously, because by then there is no lifecycle scope left to launch anything in. A sentence
     * sent in the meantime is already out of the store, and [VoiceCaptureStore.remove] shrugs that off.
     */
    private fun commitDrops() {
        if (pendingDrops.isEmpty()) return
        val uuids = pendingDrops.toList()
        pendingDrops.clear()
        for (uuid in uuids) store.remove(uuid)
    }

    private fun closeSettings() {
        stopPlaying()
        val activity = requireActivity()
        // Opened from 自由作業盤's dashboard: leaving the review means going back to it, which is what Back
        // and the toolbar arrow do too — the three ways out of this page must not land in three places.
        if (activity is SettingsActivity && activity.returnToCaller()) return
        // Opened from the space menu: close the whole settings task, not one page of it.
        runCatching { activity.finishAffinity() }.onFailure { activity.finish() }
    }

    // ---- decoding --------------------------------------------------------------------------------------

    /** Every sentence that has not been decoded yet, in one run of the engine. */
    private fun transcribeAll() = transcribe(null)

    /** What this row's Transcribe pill says: a first decode, or a second one over a decode that stands. */
    private fun transcribeLabel(row: Row): String = getString(
        if (row.entry.transcribed) {
            R.string.voice_capture_transcribe_again
        } else {
            R.string.voice_capture_transcribe_one
        }
    )

    /**
     * Decode THIS recording, and only it. A sentence that came back wrong — the wrong language caught late,
     * a word the keyboard has learned since, a decode that simply failed — is worth another run on its own,
     * without putting every other sentence of the walk through the engine again.
     *
     * A decode that already stands is overwritten, so this asks first: the text may be one 白い熊 corrected
     * or typed by hand, and that is not something a stray tap may throw away. What was learned from those
     * corrections is untouched either way — it went to the corpus when it was saved.
     */
    private fun transcribeOne(row: Row) {
        if (!row.entry.transcribed) {
            transcribe(setOf(row.entry.uuid))
            return
        }
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_capture_transcribe_again)
            .setMessage(R.string.voice_capture_transcribe_again_confirm)
            .setPositiveButton(R.string.voice_capture_transcribe_again) { _, _ ->
                transcribe(setOf(row.entry.uuid))
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    /**
     * One run of the engine over [only] (by uuid), or — [only] null — over every sentence still waiting for
     * a first decode. A sentence deleted on this page is never decoded: it is on its way out.
     */
    private fun transcribe(only: Set<String>?) {
        if (working) return
        if (VoiceModelStore.status(requireContext()) != VoiceModelStore.Status.INSTALLED) {
            KxkbToast.show(requireContext(), getString(R.string.voice_capture_no_model))
            return
        }
        working = true
        stopRequested = false
        stopPlaying()
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            val pending = withContext(Dispatchers.IO) {
                store.entries().filter {
                    // A long recording is never decoded, singly or in a run: Whisper's window is 30 s and
                    // the thing is hours. It is saved or cut into sentences instead (contract §9).
                    it.uuid !in pendingDrops && !it.isLong &&
                        if (only == null) !it.transcribed else it.uuid in only
                }
            }
            var decoded = 0
            var silent = 0
            if (pending.isEmpty()) {
                working = false
                return@launch
            }
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
                    val samples = withContext(Dispatchers.IO) { clipOf(entry)?.let { store.readSamples(it) } }
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

    // ---- the recording ---------------------------------------------------------------------------------

    /**
     * This sentence's recording, wherever it now lies. A per-word correction is stored the moment it is
     * saved, and storing it MOVES the clip out of the inbox and into the voice corpus
     * ([VoiceCorpusRepository.recordCorrection] → `adoptClip`) while the inbox row stays here until the
     * sentence is sent — so a corrected row must follow its audio across rather than behave as though it
     * had none. Reading only the inbox cost the Play pill on the first correction, and with it the ability
     * to decode the sentence again after a language switch. The corpus names an adopted clip after the
     * utterance, which here IS the capture uuid.
     */
    private fun clipOf(entry: VoiceCaptureStore.Entry): File? =
        store.clipFile(entry) ?: corpus.clipOf(entry.uuid)

    // ---- playback --------------------------------------------------------------------------------------

    private fun togglePlay(row: Row, button: Button) {
        val wasThis = playingButton === button
        stopPlaying()
        if (wasThis) return
        val clip = clipOf(row.entry) ?: return
        val uuid = row.entry.uuid
        try {
            player = MediaPlayer().apply {
                setDataSource(clip.absolutePath)
                setOnCompletionListener {
                    // An hour-long recording is listened to in pieces: it starts again where it was left,
                    // not from the top, unless it ran all the way out.
                    playFrom.remove(uuid)
                    stopPlaying()
                }
                prepare()
                playFrom[uuid]?.let { seekTo(it) }
                start()
            }
            playingButton = button
            playingUuid = uuid
            button.text = getString(R.string.voice_corpus_stop)
            if (row.entry.isLong) followPlayback(uuid, row.entry.durationMs)
        } catch (e: Exception) {
            stopPlaying()
            KxkbToast.show(requireContext(), e.message ?: "")
        }
    }

    /** The playing position onto the row's own seek bar, while it is this row that is playing. */
    private fun followPlayback(uuid: String, durationMs: Long) {
        lifecycleScope.launch {
            while (isActive && playingUuid == uuid) {
                val at = runCatching { player?.currentPosition }.getOrNull() ?: break
                playFrom[uuid] = at
                seekViews[uuid]?.let { (bar, label) ->
                    bar.progress = at
                    label.text = getString(R.string.voice_capture_position, clock(at.toLong()), clock(durationMs))
                }
                delay(PLAY_POLL_MS)
            }
        }
    }

    private fun stopPlaying() {
        playingUuid = null
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

    private fun pill(label: CharSequence, onClick: () -> Unit): Button = Button(requireContext()).apply {
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

        /** The sentences deleted but not yet committed, across a recreation of the page. */
        private const val STATE_DROPS = "pending_drops"

        /** Marks a drag as one of ours, so a drop from anywhere else is not mistaken for a pill. */
        private const val DRAG_CONTROL = "kxkb_walk_control"

        /** A lifted pill stays where it was, dimmed, so the gap it will leave is visible. */
        private const val LIFTED_ALPHA = 0.35f

        /** How long the page waits for the model set to load before giving up on a run. */
        private const val ENGINE_WAIT_MS = 120_000L
        private const val ENGINE_POLL_MS = 150L

        /** The playing position, and the progress of a long run, are read rather than pushed. */
        private const val PLAY_POLL_MS = 250L
        private const val PROGRESS_POLL_MS = 200L

        /**
         * The directory offered for kept recordings when none has been picked — the hint in the box and where
         * the browser opens. Only ever a suggestion; nothing is written anywhere until it is chosen.
         */
        private const val OGG_DIR = "〇/録音"

        /** Below this, encoding is over before a progress dialog could be read, so none is shown. */
        private const val OGG_PROGRESS_MIN_MS = 20_000L
    }
}
