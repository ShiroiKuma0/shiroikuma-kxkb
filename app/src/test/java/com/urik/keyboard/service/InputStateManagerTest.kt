package com.urik.keyboard.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputStateManagerTest {
    private var suggestionsCleared = false
    private var lastSuggestions: List<String> = emptyList()
    private var lastSelectedIndex = 0

    private lateinit var stateManager: InputStateManager

    @Before
    fun setup() {
        suggestionsCleared = false
        lastSuggestions = emptyList()
        lastSelectedIndex = 0

        val viewCallback = object : ViewCallback {
            override fun clearSuggestions() {
                suggestionsCleared = true
            }

            override fun updateSuggestions(suggestions: List<String>) {
                lastSuggestions = suggestions
            }

            override fun showDegradedIndicator(degraded: Boolean) {
            }

            override fun setSelectedSuggestion(index: Int) {
                lastSelectedIndex = index
            }
        }

        stateManager = InputStateManager(
            viewCallback = viewCallback,
            onShiftStateChanged = {},
            isCapsLockOn = { false },
            cancelDebounceJob = {}
        )
    }

    @Test
    fun `lastAutocorrection persists independently of postCommitReplacementState`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")
        stateManager.postCommitReplacementState = PostCommitReplacementState("teh", "the")

        stateManager.postCommitReplacementState = null

        assertNotNull(stateManager.lastAutocorrection)
        assertEquals("teh", stateManager.lastAutocorrection?.originalTypedWord)
        assertEquals("the", stateManager.lastAutocorrection?.correctedWord)
    }

    @Test
    fun `clearInternalStateOnly clears lastAutocorrection`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")

        stateManager.clearInternalStateOnly()

        assertNull(stateManager.lastAutocorrection)
    }

    @Test
    fun `invalidateComposingState clears lastAutocorrection`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")

        stateManager.invalidateComposingState()

        assertNull(stateManager.lastAutocorrection)
    }

    @Test
    fun `clearInternalStateOnly clears postCommitReplacementState`() {
        stateManager.postCommitReplacementState = PostCommitReplacementState("teh", "the")

        stateManager.clearInternalStateOnly()

        assertNull(stateManager.postCommitReplacementState)
    }

    @Test
    fun `clearBigramPredictions does not affect lastAutocorrection`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")
        stateManager.isShowingBigramPredictions = true

        stateManager.clearBigramPredictions()

        assertNotNull(stateManager.lastAutocorrection)
    }

    @Test
    fun `clearSpellConfirmationFields does not affect lastAutocorrection`() {
        stateManager.lastAutocorrection = LastAutocorrection("teh", "the")
        stateManager.spellConfirmationState = SpellConfirmationState.AWAITING_CONFIRMATION

        stateManager.clearSpellConfirmationFields()

        assertNotNull(stateManager.lastAutocorrection)
    }

    @Test
    fun `tryConsumeTypingOus returns false on empty queue`() {
        val consumed = stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5)

        assertFalse(consumed)
    }

    @Test
    fun `tryConsumeTypingOus consumes matching entry`() {
        stateManager.enqueueTypingOus(
            InputStateManager.ExpectedTypingOus(
                composingStart = 0,
                composingEnd = 5,
                cursorPosition = 5
            )
        )

        val consumed = stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5)

        assertTrue(consumed)
        assertFalse(stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5))
    }

    @Test
    fun `tryConsumeTypingOus clears queue on mismatch`() {
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 5, 5))
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 6, 6))

        val consumed = stateManager.tryConsumeTypingOus(cursor = 99, candStart = 0, candEnd = 5)

        assertFalse(consumed)
        assertFalse(stateManager.tryConsumeTypingOus(cursor = 6, candStart = 0, candEnd = 6))
    }

    @Test
    fun `tryConsumeTypingOus processes multiple in-flight OUS in order`() {
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 1, 1))
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 2, 2))
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 3, 3))

        assertTrue(stateManager.tryConsumeTypingOus(cursor = 1, candStart = 0, candEnd = 1))
        assertTrue(stateManager.tryConsumeTypingOus(cursor = 2, candStart = 0, candEnd = 2))
        assertTrue(stateManager.tryConsumeTypingOus(cursor = 3, candStart = 0, candEnd = 3))
        assertFalse(stateManager.tryConsumeTypingOus(cursor = 3, candStart = 0, candEnd = 3))
    }

    @Test
    fun `clearInternalStateOnly clears pending typing OUS`() {
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 5, 5))

        stateManager.clearInternalStateOnly()

        assertFalse(stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5))
    }

    @Test
    fun `invalidateComposingState clears pending typing OUS`() {
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 5, 5))

        stateManager.invalidateComposingState()

        assertFalse(stateManager.tryConsumeTypingOus(cursor = 5, candStart = 0, candEnd = 5))
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns true when all conditions met`() {
        stateManager.composingRegionStart = 10
        stateManager.displayBuffer = "hello"
        stateManager.isActivelyEditing = false
        stateManager.lastKnownCursorPosition = 15

        assertTrue(stateManager.isComposingCursorAtExpectedEnd())
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns false when composingRegionStart is -1`() {
        stateManager.composingRegionStart = -1
        stateManager.displayBuffer = "hello"
        stateManager.isActivelyEditing = false
        stateManager.lastKnownCursorPosition = 5

        assertFalse(stateManager.isComposingCursorAtExpectedEnd())
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns false when displayBuffer is empty`() {
        stateManager.composingRegionStart = 10
        stateManager.displayBuffer = ""
        stateManager.isActivelyEditing = false
        stateManager.lastKnownCursorPosition = 10

        assertFalse(stateManager.isComposingCursorAtExpectedEnd())
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns false when isActivelyEditing`() {
        stateManager.composingRegionStart = 10
        stateManager.displayBuffer = "hello"
        stateManager.isActivelyEditing = true
        stateManager.lastKnownCursorPosition = 15

        assertFalse(stateManager.isComposingCursorAtExpectedEnd())
    }

    @Test
    fun `isComposingCursorAtExpectedEnd returns false when cursor drifted`() {
        stateManager.composingRegionStart = 10
        stateManager.displayBuffer = "hello"
        stateManager.isActivelyEditing = false
        stateManager.lastKnownCursorPosition = 12

        assertFalse(stateManager.isComposingCursorAtExpectedEnd())
    }

    @Test
    fun `isKnownCursorTrustworthy returns true when all conditions met`() {
        stateManager.lastKnownCursorPosition = 10
        stateManager.isActivelyEditing = false

        assertTrue(stateManager.isKnownCursorTrustworthy())
    }

    @Test
    fun `isKnownCursorTrustworthy returns false when lastKnownCursorPosition is -1`() {
        stateManager.lastKnownCursorPosition = -1
        stateManager.isActivelyEditing = false

        assertFalse(stateManager.isKnownCursorTrustworthy())
    }

    @Test
    fun `isKnownCursorTrustworthy returns false when isActivelyEditing`() {
        stateManager.lastKnownCursorPosition = 10
        stateManager.isActivelyEditing = true

        assertFalse(stateManager.isKnownCursorTrustworthy())
    }

    @Test
    fun `isKnownCursorTrustworthy returns false when pending OUS in queue`() {
        stateManager.lastKnownCursorPosition = 10
        stateManager.isActivelyEditing = false
        stateManager.enqueueTypingOus(InputStateManager.ExpectedTypingOus(0, 5, 5))

        assertFalse(stateManager.isKnownCursorTrustworthy())
    }

    @Test
    fun `isKnownCursorTrustworthy returns false immediately after clearInternalStateOnly`() {
        stateManager.lastKnownCursorPosition = 10
        stateManager.isActivelyEditing = false

        stateManager.clearInternalStateOnly()

        assertFalse(stateManager.isKnownCursorTrustworthy())
    }

    @Test
    fun `isSuggestionsDisabled initialises false`() {
        assertFalse(stateManager.isSuggestionsDisabled)
    }

    @Test
    fun `removeSuggestionFromState removes word from pending, raw, and word state suggestions`() {
        stateManager.pendingSuggestions = listOf("Hello", "World")
        stateManager.currentRawSuggestions = listOf(
            SpellingSuggestion(word = "hello", confidence = 0.9, ranking = 0),
            SpellingSuggestion(word = "world", confidence = 0.8, ranking = 1)
        )
        stateManager.wordState = stateManager.wordState.copy(
            suggestions = listOf(
                SpellingSuggestion(word = "hello", confidence = 0.9, ranking = 0),
                SpellingSuggestion(word = "world", confidence = 0.8, ranking = 1)
            )
        )

        val remaining = stateManager.removeSuggestionFromState("Hello")

        assertEquals(listOf("World"), remaining)
        assertEquals(listOf("World"), stateManager.pendingSuggestions)
        assertFalse(stateManager.currentRawSuggestions.any { it.word.equals("hello", ignoreCase = true) })
        assertFalse(stateManager.wordState.suggestions.any { it.word.equals("hello", ignoreCase = true) })
        assertTrue(stateManager.currentRawSuggestions.any { it.word.equals("world", ignoreCase = true) })
        assertTrue(stateManager.wordState.suggestions.any { it.word.equals("world", ignoreCase = true) })
    }

    // ---- Bug C: the custom row as the empty-buffer default and appended to predictions. ----

    @Test
    fun `clearSuggestionDisplay shows the custom row as the default when one is configured`() {
        stateManager.setCustomSuggestions(listOf("brb", "omw"))

        stateManager.clearSuggestionDisplay()

        assertFalse(suggestionsCleared)
        assertEquals(listOf("brb", "omw"), lastSuggestions)
        assertEquals(listOf("brb", "omw"), stateManager.pendingSuggestions)
    }

    @Test
    fun `clearSuggestionDisplay clears the bar when no custom row is configured`() {
        stateManager.clearSuggestionDisplay()

        assertTrue(suggestionsCleared)
    }

    @Test
    fun `updateSuggestionDisplay appends the custom row after the predictions`() {
        stateManager.setCustomSuggestions(listOf("brb"))

        stateManager.updateSuggestionDisplay(listOf("the", "to"))

        assertEquals(listOf("the", "to", "brb"), lastSuggestions)
        assertEquals(listOf("the", "to", "brb"), stateManager.pendingSuggestions)
    }

    @Test
    fun `withCustomRow appends custom entries not already present and keeps order`() {
        stateManager.setCustomSuggestions(listOf("the", "brb", "omw"))

        // "the" is already a prediction, so it is not duplicated; the rest are appended in order.
        assertEquals(listOf("the", "to", "brb", "omw"), stateManager.withCustomRow(listOf("the", "to")))
    }

    @Test
    fun `withCustomRow returns predictions unchanged when no custom row`() {
        assertEquals(listOf("the", "to"), stateManager.withCustomRow(listOf("the", "to")))
    }

    @Test
    fun `withCustomRow is suppressed during Japanese composition`() {
        stateManager.setCustomSuggestions(listOf("brb"))
        stateManager.customRowSuppressed = true

        assertEquals(listOf("the"), stateManager.withCustomRow(listOf("the")))
    }

    // ---- Bug 1: the custom default row survives every state-reset path (backspace-to-empty, Enter/newline). ----

    @Test
    fun `clearInternalStateOnly repaints the custom default row and syncs pendingSuggestions`() {
        // clearInternalStateOnly is what coordinateStateClear runs on a post-commit clear / Enter / newline.
        stateManager.setCustomSuggestions(listOf("brb", "omw"))
        stateManager.displayBuffer = "hel"
        stateManager.pendingSuggestions = listOf("hello", "help")

        stateManager.clearInternalStateOnly()

        assertFalse(suggestionsCleared)
        assertEquals(listOf("brb", "omw"), lastSuggestions)
        // pendingSuggestions must mirror the bar so a tap commits the right custom entry.
        assertEquals(listOf("brb", "omw"), stateManager.pendingSuggestions)
    }

    @Test
    fun `custom default row reappears across empty then backspace then newline states`() {
        stateManager.setCustomSuggestions(listOf("brb", "omw"))

        // (a) empty buffer -> bar shows the custom entries.
        stateManager.clearSuggestionDisplay()
        assertEquals(listOf("brb", "omw"), lastSuggestions)
        assertEquals(listOf("brb", "omw"), stateManager.pendingSuggestions)

        // (b) backspace dropping a post-commit bar to empty -> still the custom entries, NOT blank.
        lastSuggestions = emptyList()
        stateManager.clearSuggestionDisplay()
        assertFalse(suggestionsCleared)
        assertEquals(listOf("brb", "omw"), lastSuggestions)

        // (c) after Enter/newline (coordinateStateClear -> clearInternalStateOnly) -> the custom entries.
        lastSuggestions = emptyList()
        stateManager.clearInternalStateOnly()
        assertEquals(listOf("brb", "omw"), lastSuggestions)
        assertEquals(listOf("brb", "omw"), stateManager.pendingSuggestions)
    }

    @Test
    fun `clearInternalStateOnly blanks the bar when no custom row is configured`() {
        stateManager.displayBuffer = "hel"
        stateManager.clearInternalStateOnly()
        assertTrue(suggestionsCleared)
    }
}
