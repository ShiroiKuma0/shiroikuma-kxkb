@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.urik.keyboard.service

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for the "ac " trailing-space-on-Enter bug.
 *
 * Pressing Enter committed a trailing SPACE in every single-line field of every app: typing `ac` + Enter in
 * the Android Settings search box left `ac ` instead of searching, and every further Enter added another
 * space — even when the buffer was already empty.
 *
 * Root cause was NOT performInputAction (the action-Enter path): the column layouts carry Enter as a
 * compass/flick key (`{"type":"compass","char":"⏎","flick":{"center":{"action":"enter"}}}`), so a tap routed
 * through handleGnuAction("enter") → outputBridge.sendEnter(), which commits a "\n" — and a single-line field
 * normalises that committed newline to a SPACE. That is why fixing only performInputAction had no effect.
 *
 * The fix routes BOTH Enter shapes through the shared [EnterActionPerformer], which NEVER commits "\n"/" ":
 * it performs the field's editor action, falling back to a real KEYCODE_ENTER only when the framework
 * declines (a genuine multi-line field).
 *
 * These tests drive the shared decision against a [FakeInputConnection] (the same harness the OutputBridge
 * tests use) and assert that neither " " nor "\n" is ever committed.
 */
@RunWith(RobolectricTestRunner::class)
class EnterActionPerformerTest {
    private lateinit var fakeIc: FakeInputConnection
    private val keyEventsSent = mutableListOf<Int>()
    private val editorActionsPerformed = mutableListOf<Int>()

    @Before
    fun setup() {
        fakeIc = FakeInputConnection()
        keyEventsSent.clear()
        editorActionsPerformed.clear()
    }

    /**
     * Drives the Enter path the way a key press does: it commits no newline/space directly; the field's
     * editor action is performed (an explicit action), or the framework default is requested and a real
     * KEYCODE_ENTER is the only fallback.
     *
     * @param defaultEditorActionAccepts models `sendDefaultEditorAction(true)`: a single-line field with an
     *   imeAction returns true (it consumed Enter); a plain multi-line field returns false (Enter = newline).
     */
    private fun pressEnter(imeAction: Int, defaultEditorActionAccepts: Boolean) {
        EnterActionPerformer.perform(
            imeAction = imeAction,
            performEditorAction = { action ->
                editorActionsPerformed.add(action)
                fakeIc.performEditorAction(action)
            },
            sendDefaultEditorAction = { defaultEditorActionAccepts },
            sendEnterKeyEvent = {
                keyEventsSent.add(KeyEvent.KEYCODE_ENTER)
                fakeIc.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                fakeIc.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        )
    }

    private fun assertNoSpaceOrNewlineCommitted() {
        assertFalse(
            "Enter must NEVER commit a space — that is the 'ac ' bug. committedTexts=${fakeIc.committedTexts}",
            fakeIc.committedTexts.contains(" ")
        )
        assertFalse(
            "Enter must NEVER commit a newline (single-line fields normalise it to a space). " +
                "committedTexts=${fakeIc.committedTexts}",
            fakeIc.committedTexts.contains("\n")
        )
        assertTrue(
            "Enter committed text it should not have: ${fakeIc.committedTexts}",
            fakeIc.committedTexts.isEmpty()
        )
    }

    @Test
    fun `plain Enter in a committed word single-line field performs editor action without a space`() {
        // (a) a finished word already in the field; the cursor sits after it.
        fakeIc.textBuffer.append("ac")
        fakeIc.selectionStart = 2

        // A single-line search field consumes the framework default editor action.
        pressEnter(EditorInfo.IME_ACTION_NONE, defaultEditorActionAccepts = true)

        assertNoSpaceOrNewlineCommitted()
        assertEquals("ac", fakeIc.textBuffer.toString())
        // No raw Enter needed: the framework consumed it.
        assertTrue(keyEventsSent.isEmpty())
    }

    @Test
    fun `plain Enter in an empty field performs editor action without a space`() {
        // (b) the second Enter on an already-empty field still must not add a space.
        pressEnter(EditorInfo.IME_ACTION_NONE, defaultEditorActionAccepts = true)

        assertNoSpaceOrNewlineCommitted()
        assertEquals("", fakeIc.textBuffer.toString())
    }

    @Test
    fun `plain Enter in a multi-line field falls back to a real KEYCODE_ENTER, never a committed newline`() {
        fakeIc.textBuffer.append("line one")
        fakeIc.selectionStart = 8

        // A multi-line field declines the default editor action — Enter means a literal newline there.
        pressEnter(EditorInfo.IME_ACTION_NONE, defaultEditorActionAccepts = false)

        assertNoSpaceOrNewlineCommitted()
        assertEquals(listOf(KeyEvent.KEYCODE_ENTER), keyEventsSent)
    }

    @Test
    fun `explicit SEARCH action performs the editor action, never committing a space`() {
        fakeIc.textBuffer.append("query")
        fakeIc.selectionStart = 5

        pressEnter(EditorInfo.IME_ACTION_SEARCH, defaultEditorActionAccepts = false)

        assertNoSpaceOrNewlineCommitted()
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), editorActionsPerformed)
        assertTrue(keyEventsSent.isEmpty())
    }

    @Test
    fun `every explicit action key performs its editor action and never commits a space`() {
        for (action in listOf(
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS
        )) {
            fakeIc = FakeInputConnection()
            keyEventsSent.clear()
            editorActionsPerformed.clear()

            pressEnter(action, defaultEditorActionAccepts = false)

            assertNoSpaceOrNewlineCommitted()
            assertEquals(listOf(action), editorActionsPerformed)
        }
    }
}
