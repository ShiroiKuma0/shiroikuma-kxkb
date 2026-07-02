package com.urik.keyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.urik.keyboard.utils.KxkbToast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.urik.keyboard.data.UserDictionaryRepository
import com.urik.keyboard.data.database.UserDictionaryKind
import com.urik.keyboard.service.ScriptConverterRegistry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Reading→surface word registration screen for the Japanese user dictionary (Japanese FIX 2 / BUG B).
 *
 * Replaces the former in-IME [android.app.AlertDialog]: an attached-window dialog over the IME fought the
 * keyboard for focus (it flickered and could not be typed into). A real Activity lets this keyboard type
 * normally — the user enters the kanji (e.g. 白い + 熊) into the surface field with the keyboard itself. On
 * Save the reading→surface pair is registered via the shared (singleton) Japanese converter so it is offered
 * for that reading from then on; the Room write runs off the main thread.
 *
 * Launched from the ＋登録 affordance via [intentForReading] with FLAG_ACTIVITY_NEW_TASK (the IME has no
 * Activity task of its own).
 */
@AndroidEntryPoint
class RegisterWordActivity : AppCompatActivity() {
    @Inject lateinit var scriptConverterRegistry: ScriptConverterRegistry
    @Inject lateinit var userDictionaryRepository: UserDictionaryRepository

    private lateinit var readingField: EditText
    private lateinit var surfaceField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setTitle(R.string.ja_register_dialog_title)

        val reading = intent.getStringExtra(EXTRA_READING).orEmpty()
        setContentView(buildLayout(reading))
    }

    private fun buildLayout(reading: String): View {
        val pad = (PADDING_DP * resources.displayMetrics.density).toInt()
        val gap = (GAP_DP * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(pad, pad, pad, pad)
        }

        fun label(textRes: Int, topGap: Int = 0) = TextView(this).apply {
            setText(textRes)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = topGap }
        }

        root.addView(label(R.string.ja_register_reading_label))
        readingField = EditText(this).apply {
            setText(reading)
            setSelection(text.length)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setSingleLine()
        }
        root.addView(readingField)

        root.addView(label(R.string.ja_register_surface_label, topGap = gap))
        surfaceField = EditText(this).apply {
            hint = getString(R.string.ja_register_surface_hint)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }
        root.addView(surfaceField)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = gap }
        }
        val cancel = Button(this).apply {
            setText(R.string.ja_register_cancel)
            setOnClickListener { finish() }
        }
        val save = Button(this).apply {
            setText(R.string.ja_register_confirm)
            setOnClickListener { onSave() }
        }
        buttons.addView(cancel)
        buttons.addView(save)
        root.addView(buttons)

        // The surface field is where the user types the kanji with this very keyboard: focus it on open.
        surfaceField.requestFocus()

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + maxOf(bars.bottom, ime.bottom))
            insets
        }
        return root
    }

    private fun onSave() {
        val reading = readingField.text?.toString()?.trim().orEmpty()
        val surface = surfaceField.text?.toString()?.trim().orEmpty()
        if (reading.isEmpty() || surface.isEmpty()) {
            KxkbToast.show(this, R.string.ja_register_empty)
            return
        }
        // registerEntry seeds the in-memory boost map synchronously and schedules its own off-main-thread Room
        // write; jump off the main thread anyway so the call site never touches Room on the UI thread.
        lifecycleScope.launch {
            // Registrations live in the unified user dictionary (kind = Japanese reading→surface), so they
            // are offered while typing and manageable in the User dictionary editor. (Unified Japanese.)
            val saved = userDictionaryRepository.add(
                languageTag = JAPANESE_LANGUAGE,
                kind = UserDictionaryKind.JAPANESE,
                matchKey = reading,
                value = surface
            )
            if (!saved) {
                // The write didn't persist (blank key or a DB error) — say so instead of a false "saved", so a
                // dropped registration is visible rather than silently lost (which is what made this hard to spot).
                KxkbToast.show(this@RegisterWordActivity, getString(R.string.ja_register_failed))
                return@launch
            }
            // Hand the just-registered pair back to the IME so that, on regaining the input view, it replaces
            // the still-typed reading with the registered surface (しろいくま → 白い熊). Set ONLY on a successful
            // Save — Cancel/back returns without ever signalling, so a dismissed dialog leaves the reading as-is.
            scriptConverterRegistry.signalRegistration(reading, surface)
            KxkbToast.show(this@RegisterWordActivity, getString(R.string.ja_register_saved_toast, surface))
            finish()
        }
    }

    companion object {
        const val EXTRA_READING = "reading"
        private const val JAPANESE_LANGUAGE = "ja"
        private const val PADDING_DP = 24
        private const val GAP_DP = 12

        /** Intent that opens the registration screen pre-filled with [reading] (launched from the IME). */
        fun intentForReading(context: Context, reading: String): Intent =
            Intent(context, RegisterWordActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_READING, reading)
    }
}
