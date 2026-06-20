package com.urik.keyboard.service

interface ScriptConverter {
    val supportedLanguages: Set<String>
    val isReady: Boolean

    suspend fun getCandidates(input: String, languageCode: String): List<ConversionCandidate>

    fun recordSelection(input: String, surface: String)

    /**
     * Register a user-defined reading→surface mapping so it is offered as a high-priority candidate for
     * [reading] from then on — even when [surface] is absent from the bundled dictionary (e.g. しろいくま →
     * 白い熊). Persisted via the same store as learned selections. No-op for converters that don't support
     * a user dictionary. (Japanese FIX 2.)
     */
    fun registerEntry(reading: String, surface: String) {}

    /**
     * Forget a previously learned/registered user reading→surface mapping so it is no longer offered for
     * [reading]. No-op for converters without a user dictionary. Used by the user-dictionary settings editor.
     * (BUG A.)
     */
    fun removeEntry(reading: String, surface: String) {}

    fun release()
}
