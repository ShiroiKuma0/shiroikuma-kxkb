package com.urik.keyboard.service

/**
 * Curated apostrophe-contraction completions for English. When the user types the apostrophe-less form
 * (e.g. "dont", "lets", "im"), the matching contraction ("don't", "let's", "I'm") is injected as the TOP
 * suggestion ahead of spell-check results so a single tap (or Space / punctuation auto-commit) fixes it.
 *
 * Lookup is case-insensitive on the typed word. The injected value is a plain SpellingSuggestion that the
 * normal casing path (CaseTransformer via SuggestionPipeline.capitalizeSuggestions) then cases for the
 * current keyboard state, so "dont" -> "don't" but "Dont" / sentence-start -> "Don't".
 *
 * The "I"-pronoun contractions are special: their canonical form is always capital-I ("I'm", not "i'm").
 * They are emitted with preserveCase = true so the casing path keeps the literal "I'm" verbatim (never
 * lower-cased mid-sentence, never upper-cased to "I'M" by caps-lock-style shift).
 */
object Contractions {
    // Pronoun-I contractions: canonical capital-I form, preserved verbatim by the casing path.
    private val PRONOUN_I = mapOf(
        "im" to "I'm",
        "ive" to "I've",
        "id" to "I'd",
        "ill" to "I'll"
    )

    // General contractions: lower-case canonical form; the casing path capitalises at shift / sentence start.
    private val GENERAL = mapOf(
        "dont" to "don't",
        "cant" to "can't",
        "wont" to "won't",
        "lets" to "let's",
        "youre" to "you're",
        "theyre" to "they're",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "didnt" to "didn't",
        "doesnt" to "doesn't",
        "wouldnt" to "wouldn't",
        "couldnt" to "couldn't",
        "shouldnt" to "shouldn't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "hadnt" to "hadn't",
        "wholl" to "who'll",
        "whos" to "who's",
        "whats" to "what's",
        "thats" to "that's",
        "heres" to "here's",
        "theres" to "there's",
        "hes" to "he's",
        "shes" to "she's",
        "its" to "it's",
        "weve" to "we've",
        "were" to "we're",
        "youve" to "you've",
        "theyve" to "they've",
        "youll" to "you'll",
        "theyll" to "they'll",
        "couldve" to "could've",
        "shouldve" to "should've",
        "wouldve" to "would've"
    )


    /**
     * Cluster-keyboard contractions: on a cluster layout the typed buffer is the tapped clusters' centre
     * letters, so the apostrophe-less key ("dont") is never in the buffer verbatim — but each tap is a
     * BAND of possible letters. A contraction is offered when a spelling of it is CONSISTENT with the tap
     * bands: one character per tap, each inside that tap's allowed set.
     *
     * TWO spellings are tried, because there are two ways to tap the same contraction:
     *  - the **bare key** ("its", "im"), when the apostrophe was not tapped at all — it is on no band, so
     *    the cluster DAWG can only ever answer with the bare dictionary words;
     *  - the **form itself** ("it's"), when the apostrophe WAS tapped: i, t, ', s — four taps, and the
     *    apostrophe matches its own one-character band. Without this shape the walk's one real answer
     *    competed on dictionary frequency with the possessive synthesized over every other head the taps
     *    allow, so "in's" (from the much commoner "in") led the real "it's" (白い熊, 2026-10-20).
     *
     * [allowedSets] is the per-tap accent-folded lowercase character set from SpellCheckManager's cluster
     * bands; the spellings here are lowercase ASCII (plus the straight apostrophe the cluster buffer is
     * canonicalised to), so they compare directly.
     *
     * Returns each matching contraction's apostrophe value paired with whether its case is preserved
     * verbatim (the pronoun-I forms), so the caller can build a SpellingSuggestion the casing path respects.
     */
    fun candidatesForBands(allowedSets: List<Set<Char>>): List<Pair<String, Boolean>> {
        if (allowedSets.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<String, Boolean>>()
        fun spellsOut(spelling: String): Boolean {
            if (spelling.length != allowedSets.size) return false
            for (i in spelling.indices) {
                if (spelling[i] !in allowedSets[i]) return false
            }
            return true
        }
        fun consider(key: String, value: String, preserveCase: Boolean) {
            if (spellsOut(key) || spellsOut(value.lowercase())) result += value to preserveCase
        }
        for ((key, value) in PRONOUN_I) consider(key, value, preserveCase = true)
        for ((key, value) in GENERAL) consider(key, value, preserveCase = false)
        return result
    }

    /**
     * Does the one tap made after a typed apostrophe spell the English possessive `s`? [tailSets] is the
     * per-tap accent-folded lowercase character set (the same cluster bands the DAWG walk uses), so a tap on
     * the `sh)` key answers yes — "notes'" plus that tap is "notes's", not the centre-letter "notes'h".
     *
     * The possessive is the ONE apostrophe ending worth synthesizing over an arbitrary word: it is an open
     * class, so no dictionary holds "notes's" and the constrained walk can never produce it. Every other
     * ending ('ll 've 're 't 'd 'm) is closed — "it'll", "we've", "don't" are dictionary words the walk finds
     * on its own — so synthesizing those only manufactured nonsense ("In'll", "On'll") ahead of the real one.
     */
    fun possessiveFollowsBands(tailSets: List<Set<Char>>): Boolean =
        tailSets.size == 1 && 's' in tailSets[0]

    /** The contraction for [typedWord] (case-insensitive), or null if none. */
    fun forWord(typedWord: String): SpellingSuggestion? {
        if (typedWord.isEmpty()) return null
        val key = typedWord.lowercase()
        PRONOUN_I[key]?.let {
            return SpellingSuggestion(it, confidence = Double.MAX_VALUE, ranking = 0, source = "contraction", preserveCase = true)
        }
        GENERAL[key]?.let {
            return SpellingSuggestion(it, confidence = Double.MAX_VALUE, ranking = 0, source = "contraction", preserveCase = false)
        }
        return null
    }

    /**
     * Inject the contraction for [typedWord] (the current buffer) as the top suggestion of [suggestions].
     * English only. Dedupes a spell-check result that already equals the contraction.
     */
    fun injectForWord(
        suggestions: List<SpellingSuggestion>,
        typedWord: String,
        language: String
    ): List<SpellingSuggestion> {
        if (language.split("-").first() != "en") return suggestions
        val contraction = forWord(typedWord) ?: return suggestions
        val deduped = suggestions.filterNot { it.word.equals(contraction.word, ignoreCase = true) }
        return listOf(contraction) + deduped
    }
}
