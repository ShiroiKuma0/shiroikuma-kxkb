package com.urik.keyboard.service

import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class JapaneseCandidateHandlerTest {
    private lateinit var inputState: InputStateManager
    private lateinit var outputBridge: OutputBridge
    private lateinit var onCommit: (String) -> Unit
    private lateinit var handler: JapaneseCandidateHandler

    @Before
    fun setUp() {
        inputState = mock()
        outputBridge = mock()
        onCommit = mock()
        handler = JapaneseCandidateHandler(inputState, outputBridge, onCommit)
    }

    @Test
    fun `onNextCandidate with empty candidates does nothing`() {
        whenever(inputState.pendingSuggestions).thenReturn(emptyList())
        handler.onNextCandidate()
        verify(outputBridge, never()).setComposingText(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `onNextCandidate cycles through candidates`() {
        whenever(inputState.pendingSuggestions).thenReturn(listOf("東京", "とうきょう", "トウキョウ"))
        handler.onNextCandidate()
        verify(outputBridge).setComposingText("とうきょう", 1)
    }

    @Test
    fun `onNextCandidate wraps from last to first`() {
        whenever(inputState.pendingSuggestions).thenReturn(listOf("東京", "とうきょう"))
        handler.onNextCandidate() // index → 1
        handler.onNextCandidate() // index → 0 (wrap)
        verify(outputBridge).setComposingText("東京", 1)
    }

    @Test
    fun `onCommitCandidate with empty candidates does nothing`() {
        whenever(inputState.pendingSuggestions).thenReturn(emptyList())
        handler.onCommitCandidate()
        verify(onCommit, never()).invoke(org.mockito.kotlin.any())
    }

    @Test
    fun `onCommitCandidate commits current candidate`() {
        whenever(inputState.pendingSuggestions).thenReturn(listOf("東京", "とうきょう"))
        handler.onNextCandidate() // select index 1 = "とうきょう"
        handler.onCommitCandidate()
        verify(onCommit).invoke("とうきょう")
    }

    @Test
    fun `onCommitCandidate resets index to 0`() {
        whenever(inputState.pendingSuggestions).thenReturn(listOf("東京", "とうきょう", "トウキョウ"))
        handler.onNextCandidate() // index → 1
        handler.onCommitCandidate()
        handler.onNextCandidate() // after reset, cycles from 0 → 1
        verify(outputBridge, times(2)).setComposingText("とうきょう", 1)
    }

    @Test
    fun `reset returns index to 0`() {
        whenever(inputState.pendingSuggestions).thenReturn(listOf("東京", "とうきょう"))
        handler.onNextCandidate() // index → 1
        handler.reset()
        handler.onNextCandidate() // from 0 → 1 again
        verify(outputBridge, times(2)).setComposingText("とうきょう", 1)
    }

    // ---- Japanese FIX 2: Space-cycling skips the trailing "＋登録" registration affordance. ----

    @Test
    fun `onNextCandidate skips the register affordance`() {
        val skipping = JapaneseCandidateHandler(
            inputState, outputBridge, onCommit,
            isRegisterAffordance = { it == "＋登録" }
        )
        whenever(inputState.pendingSuggestions).thenReturn(listOf("東京", "とうきょう", "＋登録"))
        skipping.onNextCandidate() // 0 → 1 (real candidate)
        skipping.onNextCandidate() // 1 → would be 2 (affordance) → skipped → wraps to 0
        verify(outputBridge).setComposingText("とうきょう", 1)
        verify(outputBridge).setComposingText("東京", 1)
    }

    @Test
    fun `onCommitCandidate never commits the register affordance`() {
        val skipping = JapaneseCandidateHandler(
            inputState, outputBridge, onCommit,
            isRegisterAffordance = { it == "＋登録" }
        )
        // Index 0 is the affordance; a deliberate commit there must not insert it as text.
        whenever(inputState.pendingSuggestions).thenReturn(listOf("＋登録", "東京"))
        skipping.onCommitCandidate()
        verify(onCommit, never()).invoke(org.mockito.kotlin.any())
    }
}
