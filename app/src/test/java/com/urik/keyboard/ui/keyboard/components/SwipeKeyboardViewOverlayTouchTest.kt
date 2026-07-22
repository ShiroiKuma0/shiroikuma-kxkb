package com.urik.keyboard.ui.keyboard.components

import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.urik.keyboard.R
import com.urik.keyboard.model.KeyboardKey
import com.urik.keyboard.model.KeyboardLayout
import com.urik.keyboard.model.KeyboardMode
import com.urik.keyboard.model.KeyboardState
import com.urik.keyboard.service.CharacterVariationService
import com.urik.keyboard.service.EmojiSearchManager
import com.urik.keyboard.service.LanguageManager
import com.urik.keyboard.service.RecentEmojiProvider
import com.urik.keyboard.service.SpellCheckManager
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.theme.Default
import com.urik.keyboard.theme.KeyboardTheme
import com.urik.keyboard.theme.ThemeManager
import com.urik.keyboard.utils.CacheMemoryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The edit-word overlay: opening, in-overlay editing, commit/cancel/remove, and touch/swipe gating. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SwipeKeyboardViewOverlayTouchTest {
    private lateinit var view: SwipeKeyboardView
    private lateinit var swipeDetector: SwipeDetector

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()

        val languageManager = mock<LanguageManager>()
        whenever(languageManager.currentLayoutLanguage).thenReturn(MutableStateFlow("en"))

        val themeManager = mock<ThemeManager>()
        whenever(themeManager.currentTheme).thenReturn(MutableStateFlow<KeyboardTheme>(Default))

        val cacheMemoryManager = CacheMemoryManager(context)
        val jobField = CacheMemoryManager::class.java.getDeclaredField("memoryMonitoringJob")
        jobField.isAccessible = true
        (jobField.get(cacheMemoryManager) as? Job)?.cancel()

        val layoutManager = KeyboardLayoutManager(
            context = context,
            onKeyClick = {},
            onAcceleratedDeletionChanged = {},
            onSymbolsLongPress = {},
            characterVariationService = mock<CharacterVariationService>(),
            languageManager = languageManager,
            themeManager = themeManager,
            cacheMemoryManager = cacheMemoryManager
        )
        layoutManager.updateHapticSettings(enabled = false, amplitude = 0)

        swipeDetector = mock<SwipeDetector>()

        view = SwipeKeyboardView(context)
        view.initialize(
            layoutManager,
            swipeDetector,
            mock<SpellCheckManager>(),
            mock<WordLearningEngine>(),
            themeManager,
            languageManager,
            mock<EmojiSearchManager>(),
            mock<RecentEmojiProvider>()
        )

        val layout = KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf((0 until 9).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) })
        )
        view.updateKeyboard(layout, KeyboardState())
        view.updateSuggestions(listOf("badword"))
    }

    private fun findSuggestionView(): TextView {
        val suggestionBarField = SwipeKeyboardView::class.java.getDeclaredField("suggestionBar")
        suggestionBarField.isAccessible = true
        val bar = suggestionBarField.get(view) as android.widget.LinearLayout
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            if (child is TextView && child.getTag(R.id.suggestion_text) == "badword") {
                return child
            }
        }
        error("suggestion view for 'badword' not found")
    }

    private fun overlayView(): android.widget.LinearLayout? {
        val field = SwipeKeyboardView::class.java.getDeclaredField("editWordOverlay")
        field.isAccessible = true
        return field.get(view) as? android.widget.LinearLayout
    }

    private fun findOverlayGlyph(glyph: String): TextView {
        val overlay = overlayView() ?: error("edit-word overlay not shown")
        for (i in 0 until overlay.childCount) {
            val child = overlay.getChildAt(i)
            if (child is TextView && child.text.toString() == glyph) return child
        }
        error("overlay glyph '$glyph' not found")
    }

    private fun findOverlayByDescription(descRes: Int): TextView {
        val overlay = overlayView() ?: error("edit-word overlay not shown")
        val desc = view.context.getString(descRes)
        for (i in 0 until overlay.childCount) {
            val child = overlay.getChildAt(i)
            if (child is TextView && child.contentDescription?.toString() == desc) return child
        }
        error("overlay button with description '$desc' not found")
    }

    private fun motionEvent(action: Int, x: Float = 50f, y: Float = 50f): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    @Test
    fun `long press on a candidate opens the overlay for that word`() {
        var committed: String? = null
        view.setOnEditWordCommitListener { committed = it }

        findSuggestionView().performLongClick()

        assertTrue(view.isEditWordOverlayVisible)
        view.editWordCommit()
        assertEquals("badword", committed)
    }

    @Test
    fun `tapping the edit chip opens the overlay with the render-time word`() {
        view.setEditChipWordProvider { "mata" }
        view.updateSuggestions(listOf("badword"))

        val suggestionBarField = SwipeKeyboardView::class.java.getDeclaredField("suggestionBar")
        suggestionBarField.isAccessible = true
        val bar = suggestionBarField.get(view) as android.widget.LinearLayout
        var chip: TextView? = null
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            if (child is TextView && child.text.toString() == "✎") chip = child
        }
        checkNotNull(chip) { "edit chip not rendered" }

        var committed: String? = null
        view.setOnEditWordCommitListener { committed = it }
        chip.performClick()

        assertTrue("chip tap must open the overlay", view.isEditWordOverlayVisible)
        view.editWordCommit()
        assertEquals("mata", committed)
    }

    @Test
    fun `overlay opens with cursor at end - insert and backspace edit the buffer`() {
        var committed: String? = null
        view.setOnEditWordCommitListener { committed = it }

        view.showEditWordOverlay("dobry")
        assertTrue(view.isEditWordOverlayVisible)

        view.editWordInsert("!")
        view.editWordBackspace()
        view.editWordBackspace()
        view.editWordCommit()

        assertEquals("dobr", committed)
        assertFalse(view.isEditWordOverlayVisible)
    }

    @Test
    fun `commit button commits the edited text and hides the overlay`() {
        var committed: String? = null
        view.setOnEditWordCommitListener { committed = it }

        view.showEditWordOverlay("kina")
        findOverlayGlyph("✓").performClick()

        assertEquals("kina", committed)
        assertFalse(view.isEditWordOverlayVisible)
        assertNull(overlayView())
    }

    @Test
    fun `cancel button hides the overlay without committing`() {
        var committed: String? = null
        view.setOnEditWordCommitListener { committed = it }

        view.showEditWordOverlay("kina")
        findOverlayGlyph("✕").performClick()

        assertNull(committed)
        assertFalse(view.isEditWordOverlayVisible)
    }

    @Test
    fun `remove button reports the ORIGINAL word even after edits and hides the overlay`() {
        var removed: String? = null
        view.setOnSuggestionLongPressListener { removed = it }

        view.showEditWordOverlay("badword")
        view.editWordInsert("xyz")
        findOverlayByDescription(R.string.edit_word_remove_description).performClick()

        assertEquals("badword", removed)
        assertFalse(view.isEditWordOverlayVisible)
    }

    @Test
    fun `swipe recognition is suppressed while the overlay is open but key taps still work`() {
        view.showEditWordOverlay("dobry")
        clearInvocations(swipeDetector)

        val down = motionEvent(MotionEvent.ACTION_DOWN)
        val move = motionEvent(MotionEvent.ACTION_MOVE, 250f, 50f)
        try {
            view.onTouchEvent(down)
            view.onTouchEvent(move)
        } finally {
            down.recycle()
            move.recycle()
        }

        verify(swipeDetector, never()).handleTouchEvent(any(), any())
    }

    @Test
    fun `showEditWordOverlay flushes in-flight gesture state`() {
        val down = motionEvent(MotionEvent.ACTION_DOWN)
        try {
            view.onTouchEvent(down)
        } finally {
            down.recycle()
        }

        view.showEditWordOverlay("dobry")

        verify(swipeDetector).handleTouchEvent(
            argThat { action == MotionEvent.ACTION_CANCEL },
            any()
        )

        val isSwipeActiveField = SwipeKeyboardView::class.java.getDeclaredField("isSwipeActive")
        isSwipeActiveField.isAccessible = true
        assertFalse(isSwipeActiveField.getBoolean(view))

        val hasTouchStartField = SwipeKeyboardView::class.java.getDeclaredField("hasTouchStart")
        hasTouchStartField.isAccessible = true
        assertFalse(hasTouchStartField.getBoolean(view))
    }

    @Test
    fun `voice indicator survives a keyboard rebuild without crashing`() {
        // The Fold 5 dictation crash: Samsung restarts input after each committed sentence, which
        // rebuilds the keyboard (a NEW suggestion bar) while the voice indicator is visible and
        // still parented to the OLD bar — the next bar update then re-added it bare and threw
        // "The specified child already has a parent".
        view.showVoiceIndicator("Dictating…")

        val layout = KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf((0 until 9).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) })
        )
        view.updateKeyboard(layout, KeyboardState())

        // The rebuild preserves suggestions via updateSuggestions — this re-adds the indicator.
        view.updateSuggestions(listOf("badword"))
        view.showVoiceIndicator("Dictating…")
        view.updateSuggestions(listOf("badword"))
    }

    @Test
    fun `keyboard rebuild keeps the overlay visible and attached`() {
        view.showEditWordOverlay("dobry")

        val layout = KeyboardLayout(
            mode = KeyboardMode.LETTERS,
            rows = listOf((0 until 9).map { KeyboardKey.Character("a", KeyboardKey.KeyType.LETTER) })
        )
        view.updateKeyboard(layout, KeyboardState())

        assertTrue("overlay must survive a keyboard rebuild (shift/mode changes)", view.isEditWordOverlayVisible)
        assertTrue("overlay must remain attached", overlayView()?.parent === view)

        val swipeOverlayField = SwipeKeyboardView::class.java.getDeclaredField("swipeOverlay")
        swipeOverlayField.isAccessible = true
        val swipeOverlay = swipeOverlayField.get(view) as android.view.View
        assertTrue("swipeOverlay must remain attached after rebuild", swipeOverlay.parent === view)
    }

    @Test
    fun `touches inside the overlay area reach its children even with stale gesture state`() {
        view.showEditWordOverlay("dobry")

        val isSwipeActiveField = SwipeKeyboardView::class.java.getDeclaredField("isSwipeActive")
        isSwipeActiveField.isAccessible = true
        isSwipeActiveField.setBoolean(view, true)

        val windowManager = view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        windowManager.addView(view, WindowManager.LayoutParams(1080, 2400))
        shadowOf(Looper.getMainLooper()).idle()

        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, 1080, 2400)

        var committed: String? = null
        view.setOnEditWordCommitListener { committed = it }

        val commitButton = findOverlayGlyph("✓")
        val viewLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        val buttonLocation = IntArray(2)
        commitButton.getLocationOnScreen(buttonLocation)
        val x = buttonLocation[0] - viewLocation[0] + commitButton.width / 2f
        val y = buttonLocation[1] - viewLocation[1] + commitButton.height / 2f

        val down = motionEvent(MotionEvent.ACTION_DOWN, x, y)
        val up = motionEvent(MotionEvent.ACTION_UP, x, y)
        try {
            view.dispatchTouchEvent(down)
            assertTrue("✓ should receive the DOWN through the overlay", commitButton.isPressed)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("dobry", committed)
        assertNull("overlay should be gone after ✓ is tapped", overlayView())
    }
}
