package com.urik.keyboard.service

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScriptConverterRegistry @Inject constructor(converters: Set<@JvmSuppressWildcards ScriptConverter>) {
    private val byLanguage: Map<String, ScriptConverter> =
        converters.flatMap { c -> c.supportedLanguages.map { it to c } }.toMap()

    fun forLanguage(languageCode: String): ScriptConverter? =
        byLanguage[languageCode] ?: byLanguage[languageCode.substringBefore('-')]

    /**
     * One-shot hand-off from [com.urik.keyboard.RegisterWordActivity] back to the IME: when a brand-new
     * reading→surface pair has just been registered, the Activity stashes it here. On regaining the input
     * view the IME consumes it (read-and-clear) to replace the still-typed reading with the registered
     * surface (しろいくま → 白い熊). The registry is the natural carrier — it is the SAME singleton injected
     * into both the Activity and the IME. Cancel/back never sets it, so a dismissed dialog leaves no signal.
     */
    private val pendingRegistration = AtomicReference<Pair<String, String>?>(null)

    /** Called by the registration Activity ONLY on a successful Save (reading→surface just registered). */
    fun signalRegistration(reading: String, surface: String) {
        pendingRegistration.set(reading to surface)
    }

    /** Read-and-clear the pending registration signal, if any (consumed once by the IME). */
    fun consumePendingRegistration(): Pair<String, String>? = pendingRegistration.getAndSet(null)
}
