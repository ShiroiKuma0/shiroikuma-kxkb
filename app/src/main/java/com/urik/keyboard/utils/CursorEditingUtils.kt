package com.urik.keyboard.utils

object CursorEditingUtils {
    private const val NON_SEQUENTIAL_THRESHOLD = 5

    private const val MAX_WORD_BOUNDARY_LOOKBACK = 64

    private const val RECOMPOSITION_TOLERANCE = 1

    private val nonPunctuationChars = setOf('#', '$', '%', '&', '*')

    private const val UNAMBIGUOUS_CLOSERS = ")]}»"
    private const val QUOTE_GLYPHS = "“”\"«"

    // Marks that END a word, so an opener/dash following one needs a separator space before it.
    private const val WORD_ENDING_MARKS = ".,?!:;…)]}\"”»"

    // Marks that can sit INSIDE a number — time/ratio, decimal, thousands separator. See isNumberInternalMark.
    private const val NUMBER_INTERNAL_MARKS = ":,."

    /**
     * True when the character immediately before the cursor closes a bracket/quote pair — a NEW word
     * started there needs a separator space ("(test)|so" → "(test) so"). Unambiguous closers always
     * count; quote glyphs are openers in one language and closers in another (U+201C closes Czech
     * „…“ but opens English “…”), so they count only when preceded by a letter/digit — i.e. they
     * just closed a word. Apostrophes are excluded (elisions/contractions glue legitimately).
     */
    fun needsSpaceAfterClosingPair(textBeforeCursor: String?): Boolean {
        if (textBeforeCursor.isNullOrEmpty()) return false
        val last = textBeforeCursor.last()
        if (last in UNAMBIGUOUS_CLOSERS) return true
        if (last in QUOTE_GLYPHS) {
            return textBeforeCursor.length >= 2 && textBeforeCursor[textBeforeCursor.length - 2].isLetterOrDigit()
        }
        return false
    }

    /**
     * The mirror of [needsSpaceAfterClosingPair]: true when an OPENING mark (a typed `(`/`“`/dash, or a
     * whole cursor pair `(…)` tapped on the custom row) would glue to what precedes it. Only a real word
     * end earns the separator — a letter/digit, or a mark that closed one (`.,?!:;`, `)]}"”»`, `…`).
     * Whitespace already separates; an opener or dash right before glues legitimately („(|“ + "[" -> „([“);
     * nothing before means the field/line just started. Inside a pair („Ahoj|“) there is no preceding space
     * to keep — the word commit suppressed it — so the mark has to write one itself.
     */
    fun needsSpaceBeforeOpeningPair(textBeforeCursor: String?): Boolean {
        val prev = textBeforeCursor?.lastOrNull() ?: return false
        return prev.isLetterOrDigit() || prev in WORD_ENDING_MARKS
    }

    /**
     * True when the character right after the cursor is whitespace or a mark — typically the closing
     * half of a pair the text already continues with („word|“, “word|”, (word|) ). A commit there must
     * NOT append its own trailing space, or the space lands INSIDE the pair („word “). The suppressed
     * space is the FOLLOWING word's separator: callers remember it in
     * InputStateManager.pendingWordSeparator and the next word start inserts it. Shared by the word
     * commits (SuggestionPipeline) and the punctuation commits (NonLetterInputHandler) so a mark typed
     * inside quotes behaves exactly like a word typed there.
     */
    fun trailingSpaceSuppressedByNextChar(nextChar: Char?): Boolean {
        val next = nextChar ?: return false
        return next.isWhitespace() || isPunctuation(next)
    }

    /**
     * A colon, comma or period typed straight after a digit may belong INSIDE the number — a 24-hour time
     * or ratio ("10:35", "16:9"), a Czech decimal or English thousands separator ("3,14", "1,000"), an
     * English decimal ("3.14") — so it must not push a space between the two halves.
     *
     * The space is deferred, not dropped: the caller remembers it in
     * InputStateManager.pendingWordSeparator, which only a following WORD consumes. So the rest of the
     * number arrives glued ("10:35", "3,14") while the readings that DO end a clause get their space back
     * as soon as a word follows — "bod 3: text", the Czech ordinal "10. května", "…in 1999, then…".
     */
    fun isNumberInternalMark(mark: Char, charBefore: Char?): Boolean =
        mark in NUMBER_INTERNAL_MARKS && charBefore != null && charBefore.isDigit()

    fun isPunctuation(char: Char): Boolean {
        if (char == '\'' || char == '\u2019' || char == '-') return false

        if (char in nonPunctuationChars) {
            return false
        }

        val type = Character.getType(char.code)
        return type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt()
    }

    fun isValidTextInput(text: String): Boolean {
        if (text.isBlank()) return false

        return text.any { char ->
            Character.isLetter(char.code) ||
                Character.isIdeographic(char.code) ||
                Character.getType(char.code) == Character.OTHER_LETTER.toInt() ||
                char == '\'' ||
                char == '\u2019' ||
                char == '-'
        }
    }

    fun shouldClearStateOnEmptyField(
        newSelStart: Int,
        newSelEnd: Int,
        textBefore: String?,
        textAfter: String?,
        displayBuffer: String,
        hasWordStateContent: Boolean
    ): Boolean {
        if (newSelStart != 0 || newSelEnd != 0) return false

        return textBefore.isNullOrEmpty() &&
            textAfter.isNullOrEmpty() &&
            (displayBuffer.isNotEmpty() || hasWordStateContent)
    }

    fun calculateCursorPositionInWord(
        absoluteCursorPos: Int,
        composingRegionStart: Int,
        displayBufferLength: Int
    ): Int {
        if (composingRegionStart == -1) return displayBufferLength
        return (absoluteCursorPos - composingRegionStart).coerceIn(0, displayBufferLength)
    }

    fun recalculateComposingRegionStart(currentTextLength: Int, displayBufferLength: Int): Int =
        currentTextLength - displayBufferLength

    fun isNonSequentialCursorMovement(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        composingRegionStart: Int,
        composingRegionEnd: Int
    ): Boolean {
        val hadSelection = oldSelStart != oldSelEnd
        val hasSelection = newSelStart != newSelEnd

        if (hadSelection || hasSelection) return true

        val distance = kotlin.math.abs(newSelStart - oldSelStart)

        if (distance <= 1) return false

        if (composingRegionStart != -1 && composingRegionEnd != -1) {
            val composingLength = composingRegionEnd - composingRegionStart
            val maxExpectedDistance = composingLength + 2
            if (distance <= maxExpectedDistance) return false
        }

        return distance > NON_SEQUENTIAL_THRESHOLD
    }

    fun extractWordBoundedByParagraph(
        textBeforeCursor: String,
        maxWordLength: Int = MAX_WORD_BOUNDARY_LOOKBACK
    ): Pair<String, Int>? {
        if (textBeforeCursor.isEmpty()) return null

        val searchText =
            if (textBeforeCursor.length > maxWordLength) {
                textBeforeCursor.takeLast(maxWordLength)
            } else {
                textBeforeCursor
            }

        val paragraphBoundary = searchText.lastIndexOf('\n')

        val textInParagraph =
            if (paragraphBoundary >= 0) {
                searchText.substring(paragraphBoundary + 1)
            } else {
                searchText
            }

        if (textInParagraph.isEmpty()) return null

        val wordBoundary =
            textInParagraph.indexOfLast { char ->
                char.isWhitespace() || isPunctuation(char)
            }

        val word =
            if (wordBoundary >= 0) {
                textInParagraph.substring(wordBoundary + 1)
            } else {
                textInParagraph
            }

        if (word.isEmpty() || !isValidTextInput(word)) return null

        val absoluteBoundary =
            if (textBeforeCursor.length > maxWordLength) {
                val offset = textBeforeCursor.length - maxWordLength
                if (paragraphBoundary >= 0) {
                    offset + paragraphBoundary + 1 + if (wordBoundary >= 0) wordBoundary else -1
                } else {
                    offset + if (wordBoundary >= 0) wordBoundary else -1
                }
            } else {
                if (paragraphBoundary >= 0) {
                    paragraphBoundary + 1 + if (wordBoundary >= 0) wordBoundary else -1
                } else {
                    if (wordBoundary >= 0) wordBoundary else -1
                }
            }

        return Pair(word, absoluteBoundary)
    }

    fun shouldAbortRecomposition(
        expectedCursorPosition: Int,
        actualCursorPosition: Int,
        expectedComposingStart: Int,
        actualComposingStart: Int,
        tolerance: Int = RECOMPOSITION_TOLERANCE
    ): Boolean {
        val cursorMismatch = kotlin.math.abs(expectedCursorPosition - actualCursorPosition) > tolerance
        val composingMismatch =
            expectedComposingStart != -1 &&
                actualComposingStart != -1 &&
                expectedComposingStart != actualComposingStart

        return cursorMismatch || composingMismatch
    }

    fun crossesParagraphBoundary(startPosition: Int, endPosition: Int, textContext: String): Boolean {
        if (startPosition < 0 || endPosition < 0) return false
        if (startPosition >= textContext.length || endPosition > textContext.length) return false

        val relevantText =
            textContext.substring(
                kotlin.math.min(startPosition, endPosition),
                kotlin.math.max(startPosition, endPosition)
            )

        return relevantText.contains('\n')
    }
}
