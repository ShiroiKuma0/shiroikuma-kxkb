package com.urik.keyboard.service.voice

/**
 * Spoken punctuation for dictation: saying “čárka”, “tečka”, “otazník”, “nový řádek”… (cs), “comma”,
 * “period”… (en) or “запятая”, “точка”… (ru) puts the mark in instead of the word.
 *
 * Whisper punctuates on its own from pauses and handles spoken marks inconsistently: it writes the word,
 * sometimes the mark itself, and very often wraps the word in its own commas (“slovo, čárka, další”). So
 * this is a deterministic pass over the decoded text that keeps Whisper's auto-punctuation and, wherever a
 * command word stands, drops the marks Whisper put around it and puts the spoken mark in their place.
 *
 * - Commands match the NOMINATIVE only (“čárka”, never “čárku”) — the inflected forms are what natural
 *   speech uses (“dej tam čárku”), so they stay words.
 * - The escape word — “doslova” / “literal” / “буквально” — makes the very next command a plain word
 *   (“doslova tečka” → “tečka”) and disappears itself; followed by anything else it is an ordinary word.
 *   When Whisper already turned the command into a bare mark (“doslova .”), the escape turns it back.
 * - Japanese is not mapped (まる / てん are ordinary words; Whisper punctuates Japanese well).
 *
 * Pure text in, text out — unit-tested.
 */
object SpokenPunctuation {
    /** Declared first: the tables below fold their words through it while the object initialises. */
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    private enum class Glue { NONE, LEFT, RIGHT, BOTH }

    private class Mark(val text: String, val glue: Glue, val capitalizeNext: Boolean = false, val quote: Boolean = false)

    private class Table(
        val escape: String,
        val commands: List<Pair<List<String>, Mark>>,
        val quotes: Pair<String, String>,
        val escapeKey: String
    ) {
        /** The phrases' words accent-folded for matching ("čárka" → "carka"). */
        val commandKeys: List<Pair<List<String>, Mark>> = commands.map { (words, mark) -> words.map(::fold) to mark }
    }

    private val SENTENCE_END = Mark(".", Glue.LEFT, capitalizeNext = true)
    private val COMMA = Mark(",", Glue.LEFT)
    private val QUESTION = Mark("?", Glue.LEFT, capitalizeNext = true)
    private val EXCLAMATION = Mark("!", Glue.LEFT, capitalizeNext = true)
    private val COLON = Mark(":", Glue.LEFT)
    private val SEMICOLON = Mark(";", Glue.LEFT)
    private val ELLIPSIS = Mark("…", Glue.LEFT)
    private val HYPHEN = Mark("-", Glue.BOTH)
    private val TIGHT_DASH = Mark("–", Glue.BOTH)
    private val SPACED_DASH = Mark("–", Glue.NONE)
    private val NEW_LINE = Mark("\n", Glue.BOTH, capitalizeNext = true)
    private val NEW_PARAGRAPH = Mark("\n\n", Glue.BOTH, capitalizeNext = true)
    private val QUOTE = Mark("\"", Glue.NONE, quote = true)

    private fun table(escape: String, quotes: Pair<String, String>, vararg commands: Pair<String, Mark>) =
        Table(
            escape,
            // Longest phrase first, so “tři tečky” beats “tečka” and “точка с запятой” beats “точка”.
            commands.map { (phrase, mark) -> phrase.split(' ') to mark }.sortedByDescending { it.first.size },
            quotes,
            escapeKey = fold(escape)
        )

    private val TABLES = mapOf(
        "cs" to table(
            "doslova",
            "„" to "“",
            "tečka" to SENTENCE_END,
            "čárka" to COMMA,
            "otazník" to QUESTION,
            "vykřičník" to EXCLAMATION,
            "dvojtečka" to COLON,
            "středník" to SEMICOLON,
            "pomlčka" to TIGHT_DASH,
            "spojovník" to HYPHEN,
            "tři tečky" to ELLIPSIS,
            "nový řádek" to NEW_LINE,
            "nový odstavec" to NEW_PARAGRAPH,
            "uvozovky" to QUOTE
        ),
        "en" to table(
            "literal",
            "“" to "”",
            "period" to SENTENCE_END,
            "full stop" to SENTENCE_END,
            "comma" to COMMA,
            "question mark" to QUESTION,
            "exclamation mark" to EXCLAMATION,
            "exclamation point" to EXCLAMATION,
            "colon" to COLON,
            "semicolon" to SEMICOLON,
            "dash" to TIGHT_DASH,
            "hyphen" to HYPHEN,
            "ellipsis" to ELLIPSIS,
            "new line" to NEW_LINE,
            "new paragraph" to NEW_PARAGRAPH,
            "quote" to QUOTE,
            "unquote" to QUOTE
        ),
        "ru" to table(
            "буквально",
            "«" to "»",
            "точка" to SENTENCE_END,
            "запятая" to COMMA,
            "вопросительный знак" to QUESTION,
            "восклицательный знак" to EXCLAMATION,
            "двоеточие" to COLON,
            "точка с запятой" to SEMICOLON,
            "тире" to SPACED_DASH,
            "дефис" to HYPHEN,
            "многоточие" to ELLIPSIS,
            "новая строка" to NEW_LINE,
            "новый абзац" to NEW_PARAGRAPH,
            "кавычки" to QUOTE
        )
    )

    /** The marks Whisper itself sets that a spoken command replaces when they touch it. */
    private const val WHISPER_MARKS = ".,;:!?…"

    private class Segment(var text: String, val glue: Glue, val isMark: Boolean)

    /** Whether [language] has spoken punctuation at all. */
    fun supports(language: String): Boolean = language.substringBefore("-") in TABLES

    /** [text] with every spoken command of [language] replaced by its mark. Unsupported languages pass through. */
    fun apply(text: String, language: String): String {
        val table = TABLES[language.substringBefore("-")] ?: return text
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return text
        val out = mutableListOf<Segment>()
        var capitalizeNext = false
        var quoteOpen = false
        var i = 0

        fun emitWord(word: String) {
            val w = if (capitalizeNext) capitalize(word) else word
            capitalizeNext = false
            out.add(Segment(w, Glue.NONE, isMark = false))
        }

        while (i < tokens.size) {
            val token = tokens[i]

            // The escape: the very next command (or a bare mark Whisper made of it) stays a word.
            if (key(token) == table.escapeKey && i + 1 < tokens.size) {
                val literal = matchAt(table, tokens, i + 1)
                if (literal != null) {
                    val (length, _) = literal
                    val words = tokens.subList(i + 1, i + 1 + length).map(::stripWhisperMarks)
                    val last = tokens[i + length]
                    words.forEachIndexed { k, w ->
                        // Keep Whisper's own trailing mark on the last literal word ("doslova tečka." → "tečka.").
                        emitWord(if (k == words.size - 1) w + trailingMarks(last) else w)
                    }
                    i += 1 + length
                    continue
                }
                val bare = tokens[i + 1]
                val spelled = table.commands.firstOrNull { it.second.text == bare && it.second.glue != Glue.BOTH }
                if (spelled != null) {
                    emitWord(spelled.first.joinToString(" "))
                    i += 2
                    continue
                }
                if (key(tokens[i + 1]) == table.escapeKey) {
                    emitWord(stripWhisperMarks(tokens[i + 1]) + trailingMarks(tokens[i + 1]))
                    i += 2
                    continue
                }
            }

            val match = matchAt(table, tokens, i)
            if (match == null) {
                emitWord(token)
                i++
                continue
            }
            val (length, mark) = match
            // Whisper's own mark on the word before the command gives way to the spoken one.
            out.lastOrNull()?.takeIf { !it.isMark }?.let { prev -> prev.text = prev.text.trimEnd { it in WHISPER_MARKS } }
            // Two sentence marks in a row (Whisper's "." kept on a mark segment, then "otazník"): the later wins.
            if (mark.text.length == 1 && mark.text[0] in WHISPER_MARKS) {
                val prev = out.lastOrNull()
                if (prev != null && prev.isMark && prev.text.length == 1 && prev.text[0] in WHISPER_MARKS) out.removeAt(out.size - 1)
            }
            if (mark.quote) {
                if (quoteOpen) {
                    out.add(Segment(table.quotes.second, Glue.LEFT, isMark = true))
                } else {
                    out.add(Segment(table.quotes.first, Glue.RIGHT, isMark = true))
                }
                quoteOpen = !quoteOpen
            } else {
                out.add(Segment(mark.text, mark.glue, isMark = true))
                if (mark.capitalizeNext) capitalizeNext = true
            }
            i += length
        }
        return join(out)
    }

    /** The command starting at token [index]: (token count, mark), or null. */
    private fun matchAt(table: Table, tokens: List<String>, index: Int): Pair<Int, Mark>? {
        for ((phrase, mark) in table.commandKeys) {
            if (index + phrase.size > tokens.size) continue
            if (phrase.indices.all { k -> key(tokens[index + k]) == phrase[k] }) return phrase.size to mark
        }
        return null
    }

    /**
     * The matching key: the word core, lower-cased and ACCENT-FOLDED — Whisper often gets Czech length marks
     * wrong ("čarka" for "čárka"), and a command must not hinge on them.
     */
    private fun key(token: String): String = fold(VoiceWordJudge.coreOf(token))

    private fun fold(word: String): String =
        java.text.Normalizer.normalize(word.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")


    private fun stripWhisperMarks(token: String): String = token.trim { it in WHISPER_MARKS }

    private fun trailingMarks(token: String): String = token.takeLastWhile { it in WHISPER_MARKS }

    private fun capitalize(word: String): String {
        val index = word.indexOfFirst { it.isLetter() }
        if (index < 0) return word
        return word.substring(0, index) + word[index].uppercaseChar() + word.substring(index + 1)
    }

    private fun join(segments: List<Segment>): String {
        val sb = StringBuilder()
        var previous: Segment? = null
        for (segment in segments) {
            if (segment.text.isEmpty()) continue
            val prev = previous
            if (prev != null) {
                val glued = prev.glue == Glue.RIGHT || prev.glue == Glue.BOTH ||
                    segment.glue == Glue.LEFT || segment.glue == Glue.BOTH
                if (!glued) sb.append(' ')
            }
            sb.append(segment.text)
            previous = segment
        }
        return sb.toString()
    }

    /** True when [text] begins with a mark that attaches to the text before it (no separating space). */
    fun startsGlued(text: String): Boolean = text.isNotEmpty() && text[0] in ".,;:!?…–-\n"

    /** True when [text] ends with something after which no space follows (a line break). */
    fun endsGlued(text: String): Boolean = text.endsWith("\n")
}
