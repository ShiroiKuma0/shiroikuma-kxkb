package com.urik.keyboard.settings.voice

import android.media.MediaPlayer
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.urik.keyboard.R
import com.urik.keyboard.data.VoiceCorpusRepository
import com.urik.keyboard.data.database.VoiceWordEventKind
import com.urik.keyboard.utils.KxkbToast
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The voice corpus: every dictation kept with its recording — what the recogniser heard, what it reads as
 * after your review, and each word you corrected or confirmed. Play a recording, delete one dictation (what
 * it taught is forgotten with it), or delete everything. Nothing here ever leaves the device unless you
 * export it (Export/import → Voice corrections).
 */
@AndroidEntryPoint
class VoiceCorpusFragment : Fragment() {
    @Inject lateinit var corpus: VoiceCorpusRepository

    private lateinit var root: LinearLayout
    private var player: MediaPlayer? = null
    private var playingButton: Button? = null

    private val yellow = 0xFFFFFF00.toInt()
    private val dim = 0xFFCCCC66.toInt()
    private val blue = 0xFF4FC3F7.toInt()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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

    private fun rebuild() {
        lifecycleScope.launch {
            val items = corpus.items()
            val bytes = corpus.corpusBytes()
            root.removeAllViews()
            root.addView(text(getString(R.string.voice_corpus_title), 22f, yellow))
            root.addView(
                text(
                    getString(R.string.voice_corpus_summary, items.size, bytes / (1024.0 * 1024.0)),
                    13f,
                    dim
                ).apply { setPadding(0, dp(6), 0, 0) }
            )
            root.addView(text(getString(R.string.voice_corpus_intro), 13f, dim).apply { setPadding(0, dp(6), 0, 0) })
            if (items.isNotEmpty()) {
                root.addView(pill(getString(R.string.voice_corpus_delete_all)) { confirmDeleteAll() })
            }
            for (item in items) root.addView(itemView(item))
        }
    }

    private fun itemView(item: VoiceCorpusRepository.Item): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(14), 0, dp(10))
        val u = item.utterance
        val seconds = u.sampleCount / 16000.0
        addView(
            text(
                "${stamp.format(Date(u.createdAt))} · ${u.languageTag} · " +
                    String.format(Locale.ROOT, "%.1f s", seconds),
                12f,
                dim
            )
        )
        addView(text(u.finalText, 16f, yellow).apply { setPadding(0, dp(2), 0, 0) })
        if (u.recognizedText != u.finalText) {
            addView(text(getString(R.string.voice_corpus_heard, u.recognizedText), 12f, dim))
        }
        for (e in item.events) {
            val line = if (e.kind == VoiceWordEventKind.CORRECTED.tag) {
                "${e.recognized} → ${e.corrected}"
            } else {
                "✓ ${e.recognized}"
            }
            addView(text(line, 13f, blue).apply { setPadding(dp(12), dp(1), 0, 0) })
        }
        val buttons = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        if (item.clip != null) {
            lateinit var play: Button
            play = pill(getString(R.string.voice_corpus_play)) { togglePlay(item, play) }
            buttons.addView(play)
        }
        buttons.addView(pill(getString(R.string.voice_corpus_delete)) { confirmDelete(item) })
        addView(buttons)
        addView(View(requireContext()).apply {
            setBackgroundColor(0x66FFFF00)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
            }
        })
    }

    private fun togglePlay(item: VoiceCorpusRepository.Item, button: Button) {
        val wasThis = playingButton === button
        stopPlaying()
        if (wasThis) return
        val clip = item.clip ?: return
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

    private fun confirmDelete(item: VoiceCorpusRepository.Item) {
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_corpus_delete)
            .setMessage(getString(R.string.voice_corpus_delete_confirm, item.utterance.finalText))
            .setPositiveButton(R.string.voice_corpus_delete) { _, _ ->
                stopPlaying()
                lifecycleScope.launch {
                    corpus.delete(item.utterance.id)
                    rebuild()
                }
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(requireContext(), R.style.Theme_Urik_Dialog)
            .setTitle(R.string.voice_corpus_delete_all)
            .setMessage(R.string.voice_corpus_delete_all_confirm)
            .setPositiveButton(R.string.voice_corpus_delete_all) { _, _ ->
                stopPlaying()
                lifecycleScope.launch {
                    corpus.deleteAll()
                    rebuild()
                }
            }
            .setNegativeButton(R.string.export_import_cancel, null)
            .show()
    }

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
}
