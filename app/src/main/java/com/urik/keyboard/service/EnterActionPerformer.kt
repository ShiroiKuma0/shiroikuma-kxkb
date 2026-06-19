package com.urik.keyboard.service

import android.view.inputmethod.EditorInfo

/**
 * The single, shared decision for what an Enter key (or Enter editor action) should DO once any composing
 * word has already been finished. It NEVER commits a "\n" or a " ": a committed newline is silently
 * normalised to a SPACE by single-line fields (the "ac " bug, where typing `ac` + Enter in the Android
 * Settings search box left `ac ` instead of searching).
 *
 * Both Enter shapes route here:
 *  - a plain `{"type":"action","action":"enter"}` key (via routeAction → onEnterAction → performInputAction),
 *  - a compass/flick Enter (`{"type":"compass","char":"⏎","flick":{"center":{"action":"enter"}}}`, used by the
 *    column layouts), via handleGnuAction("enter").
 * The compass path previously called `outputBridge.sendEnter()` directly, which committed a "\n" — that is
 * the path that produced the trailing space, and why fixing only `performInputAction` had no effect.
 *
 * Behaviour:
 *  - An explicit field editor action (SEARCH/SEND/DONE/GO/NEXT/PREVIOUS) → perform it.
 *  - Otherwise (the plain Enter, IME_ACTION_NONE) → let the framework perform the field's default editor
 *    action (`sendDefaultEditorAction(true)`), which honours the field's imeOptions and single-line vs
 *    multi-line semantics. Only when it declines (a multi-line field where Enter means a literal newline) do
 *    we send a real KEYCODE_ENTER — never a committed "\n".
 */
object EnterActionPerformer {
    fun perform(
        imeAction: Int,
        performEditorAction: (Int) -> Unit,
        sendDefaultEditorAction: () -> Boolean,
        sendEnterKeyEvent: () -> Unit
    ) {
        val performed = when (imeAction) {
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS -> {
                performEditorAction(imeAction)
                true
            }

            else -> sendDefaultEditorAction()
        }
        if (!performed) {
            sendEnterKeyEvent()
        }
    }
}
