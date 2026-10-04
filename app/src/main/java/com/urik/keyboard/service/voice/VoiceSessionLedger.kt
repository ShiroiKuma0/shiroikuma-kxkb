package com.urik.keyboard.service.voice

/**
 * The in-memory record of what dictation put into the current field: every committed chunk with every
 * one of its words, where each word now sits in the field, whether it is still there unchanged, and
 * whether you corrected it. It is what lets a tap on a dictated word open the correction box, and what
 * decides — when the dictation is accepted — which suspect words you let stand.
 *
 * Positions are absolute field offsets. The ledger never trusts them blindly: [verify] re-reads the
 * text around the cursor and re-locates each entry's words by a word-level longest-common-subsequence,
 * so typing before a sentence shifts it without losing it, and a deleted or retyped word drops out
 * (`intact = false`) and can never be confirmed.
 *
 * Pure bookkeeping — no Android, no I/O — so every rule here is unit-tested.
 */
class VoiceSessionLedger {
    class Word(
        val index: Int,
        /** The word exactly as dictation committed it (whitespace token, punctuation attached). */
        val recognized: String,
        val confidence: Float?,
        val judgement: VoiceJudgement,
        /** The word as it stands now (differs from [recognized] after a correction). */
        var current: String,
        /** Absolute field offset of [current]. */
        var start: Int
    ) {
        /** Still in the field, unchanged since the last [verify] (or since our own edit). */
        var intact: Boolean = true

        /**
         * Swallowed into the word before it by a multi-word correction — gone for good, unlike a word that is
         * merely not [intact] (changed or deleted in the field), which every [verify] looks for again.
         */
        var merged: Boolean = false

        /** Corrected through the correction box (its CORRECTED event is already stored). */
        var corrected: Boolean = false

        /**
         * Replaced at commit by your earlier correction of the same recognition ([recognized] holds what the
         * recogniser produced, [current] the replacement). Cleared by any later edit of the word.
         */
        var autoReplaced: Boolean = false

        val end: Int get() = start + current.length
        val isSuspect: Boolean get() = judgement.isSuspect
    }

    class Entry(
        val utteranceId: String,
        val language: String,
        val recognizedText: String,
        val words: List<Word>,
        val samples: FloatArray?,
        /**
         * A recording that already exists as a file — a walk-capture clip, in the 16 kHz mono PCM16 WAV the
         * corpus itself writes. Set instead of [samples] so accepting the sentence MOVES the file into the
         * corpus rather than re-encoding it. Dictation leaves this null and keeps its samples.
         */
        val clipPath: String? = null
    ) {
        /**
         * Corrections you made by TYPING over this dictation's words (no correction box) — found by the last
         * [verify] and stored when the dictation is accepted, when the text is final.
         */
        var typedCorrections: List<TypedCorrection> = emptyList()

        /** The chunk as it reads now: corrections (boxed and typed) applied, merged and deleted words gone. */
        fun currentText(): String {
            val typed = typedCorrections.associateBy { it.from }
            val out = ArrayList<String>()
            var i = 0
            while (i < words.size) {
                val t = typed[i]
                if (t != null) {
                    out.add(t.corrected)
                    i = t.to + 1
                    continue
                }
                if (words[i].intact) out.add(words[i].current)
                i++
            }
            return out.joinToString(" ")
        }
    }

    private val entries = mutableListOf<Entry>()

    val isEmpty: Boolean get() = entries.isEmpty()

    fun entries(): List<Entry> = entries.toList()

    /**
     * Record a chunk committed as [committedText] starting at absolute offset [fieldStart]. [confidences]
     * and [judgements] are per whitespace word of the text.
     */
    fun add(
        utteranceId: String,
        language: String,
        committedText: String,
        fieldStart: Int,
        confidences: List<Float?>,
        judgements: List<VoiceJudgement>,
        samples: FloatArray?,
        originals: Map<Int, String> = emptyMap(),
        clipPath: String? = null
    ): Entry {
        val words = mutableListOf<Word>()
        var i = 0
        while (i < committedText.length) {
            if (committedText[i].isWhitespace()) {
                i++
                continue
            }
            val start = i
            while (i < committedText.length && !committedText[i].isWhitespace()) i++
            val token = committedText.substring(start, i)
            val index = words.size
            words.add(
                Word(
                    index = index,
                    recognized = originals[index] ?: token,
                    confidence = confidences.getOrNull(index),
                    judgement = judgements.getOrNull(index) ?: VoiceJudgement(null),
                    current = token,
                    start = fieldStart + start
                ).apply { autoReplaced = index in originals }
            )
        }
        val entry = Entry(utteranceId, language, committedText, words, samples, clipPath)
        entries.add(entry)
        return entry
    }

    /** The intact dictated word whose text spans [position] (either edge included), or null. */
    fun wordAt(position: Int): Pair<Entry, Word>? {
        for (entry in entries) {
            for (word in entry.words) {
                if (word.intact && position >= word.start && position <= word.end) return entry to word
            }
        }
        return null
    }

    /** Every intact suspect word, in dictation order (what the review strip lists). */
    fun suspects(): List<Pair<Entry, Word>> =
        entries.flatMap { e -> e.words.filter { it.intact && it.isSuspect && !it.corrected && !it.autoReplaced }.map { e to it } }

    /** Every intact word, in dictation order (the review strip's "…" expansion). */
    fun allWords(): List<Pair<Entry, Word>> = entries.flatMap { e -> e.words.filter { it.intact }.map { e to it } }

    /**
     * Words [from]..[to] of an entry, recognised as [recognized], now read [corrected] in the field — typed over
     * by hand.
     */
    data class TypedCorrection(val from: Int, val to: Int, val recognized: String, val corrected: String)

    /** A correction applied to the ledger: the (first) word it now lives in, and what was recognised there. */
    class Edit(val entry: Entry, val word: Word, val recognized: String)

    /** Absolute field offset of [word]'s core. */
    fun coreStartOf(word: Word): Int = word.start + VoiceWordJudge.coreStartIn(word.current)

    /**
     * The text from the core of word [from] to the core end of word [to] of [entry] — one word's core, or a
     * run of ADJACENT intact words with the outer punctuation left out ("lukou i ostupu"). [recognized]
     * builds the same span from what the recogniser produced instead of what stands now. Null when the run
     * is broken (a word gone, or something typed between).
     */
    fun spanText(entry: Entry, from: Int, to: Int, recognized: Boolean = false): String? {
        val ws = entry.words
        if (from < 0 || to >= ws.size || from > to) return null
        val run = (from..to).map { ws[it] }.filter { it.intact }
        if (run.isEmpty() || run.first() !== ws[from] || run.last() !== ws[to]) return null
        for (i in 0 until run.size - 1) if (run[i + 1].start != run[i].end + 1) return null
        fun text(w: Word) = if (recognized) w.recognized else w.current
        val first = text(run.first())
        val last = text(run.last())
        val firstOffset = VoiceWordJudge.coreStartIn(first)
        val lastCoreEnd = VoiceWordJudge.coreStartIn(last) + VoiceWordJudge.coreOf(last).length
        if (run.size == 1) return VoiceWordJudge.coreOf(first)
        val sb = StringBuilder(first.substring(firstOffset))
        for (i in 1 until run.size - 1) sb.append(' ').append(text(run[i]))
        sb.append(' ').append(last.substring(0, lastCoreEnd))
        return sb.toString()
    }

    /** Index of the intact word next to [index] in [direction] (-1 / +1) that directly touches it, or null. */
    fun adjacentIndex(entry: Entry, index: Int, direction: Int): Int? {
        val ws = entry.words
        var i = index + direction
        while (i in ws.indices && !ws[i].intact) i += direction
        if (i !in ws.indices) return null
        val (left, right) = if (direction > 0) ws[index] to ws[i] else ws[i] to ws[index]
        return i.takeIf { right.start == left.end + 1 }
    }

    /**
     * The correction box replaced [original] — one dictated word's core, or a run of adjacent dictated words
     * ("lukou i ostupu") — starting at field offset [coreStart] with [edited]. The run becomes ONE word (outer
     * punctuation kept), the words it swallowed drop out, and every later word shifts. Returns the edit, or
     * null when [coreStart]/[original] is not dictated text.
     */
    fun applyEdit(coreStart: Int, original: String, edited: String): Edit? {
        for (entry in entries) {
            val ws = entry.words
            for (from in ws.indices) {
                val first = ws[from]
                if (!first.intact || coreStartOf(first) != coreStart) continue
                var to = from
                while (true) {
                    val text = spanText(entry, from, to) ?: break
                    if (text == original) return merge(entry, from, to, original, edited)
                    if (text.length >= original.length) break
                    to = adjacentIndex(entry, to, +1) ?: break
                }
            }
        }
        return null
    }

    private fun merge(entry: Entry, from: Int, to: Int, original: String, edited: String): Edit {
        val ws = entry.words
        val recognized = spanText(entry, from, to, recognized = true) ?: original
        val first = ws[from]
        val last = ws[to]
        val firstOffset = VoiceWordJudge.coreStartIn(first.current)
        val lastCoreEnd = VoiceWordJudge.coreStartIn(last.current) + VoiceWordJudge.coreOf(last.current).length
        val updated = first.current.substring(0, firstOffset) + edited + last.current.substring(lastCoreEnd)
        val oldEnd = last.end
        val lastStart = last.start
        for (i in from + 1..to) {
            ws[i].intact = false
            ws[i].merged = true
        }
        first.current = updated
        if (edited != original || from != to) {
            first.corrected = true
            first.autoReplaced = false
        }
        val delta = first.end - oldEnd
        if (delta != 0) shiftAfter(lastStart, delta)
        return Edit(entry, first, recognized)
    }

    private fun shiftAfter(position: Int, delta: Int) {
        for (entry in entries) for (word in entry.words) if (word.start > position) word.start += delta
    }

    /**
     * Re-locate every entry inside [window], the field text that starts at absolute offset [windowStart].
     * [reachesFieldStart] / [reachesFieldEnd] say the window runs to the field's edge (the read came back
     * shorter than asked) — text deleted near the end makes an entry's old span overhang the field, and it
     * must still be checked. An entry whose span runs past a window edge that is NOT a field edge is left
     * as it was (we cannot see it).
     */
    fun verify(window: String, windowStart: Int, reachesFieldStart: Boolean = true, reachesFieldEnd: Boolean = true) {
        val windowEnd = windowStart + window.length
        val tokens = tokenize(window, windowStart)
        for (entry in entries) {
            // Every word not merged away is looked for again — a word changed mid-typing at one check may be
            // complete (or restored) at the next.
            val live = entry.words.filter { !it.merged }
            if (live.isEmpty()) continue
            val located = live.filter { it.intact }.ifEmpty { live }
            val expectedStart = located.first().start
            val expectedEnd = located.last().end
            if (expectedStart < windowStart && !reachesFieldStart) continue
            if (expectedEnd > windowEnd && !reachesFieldEnd) continue
            val slack = SLACK_CHARS + (expectedEnd - expectedStart)
            val candidates = tokens.filter { (_, pos) -> pos >= expectedStart - slack && pos <= expectedEnd + slack }
            entry.typedCorrections = align(entry, live, candidates)
        }
    }

    /**
     * LCS of the entry's live words against the window tokens (exact text); unmatched words drop out. A word
     * corrected into several ("Jak se") is split into its tokens and stays only when all of them match in a row.
     *
     * A run of unmatched words with matched words on both sides, where 1–(run + 1) other tokens now stand
     * between those anchors, was TYPED OVER — that is returned as a [TypedCorrection]. At the very start or end
     * of the dictation (one anchor) the replacement must be exactly as many tokens as the run and sit where the
     * run was. No tokens in between is a deletion, not a correction.
     */
    private fun align(entry: Entry, words: List<Word>, tokens: List<Pair<String, Int>>): List<TypedCorrection> {
        val previousStarts = words.map { it.start }
        val parts = words.flatMapIndexed { index, w -> tokenize(w.current, 0).map { index to it.first } }
        val n = parts.size
        val m = tokens.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] =
                    if (parts[i].second == tokens[j].first) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val matchedAt = IntArray(n) { -1 }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                parts[i].second == tokens[j].first -> {
                    matchedAt[i] = j
                    i++
                    j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }
        val firstToken = IntArray(words.size) { -1 }
        val lastToken = IntArray(words.size) { -1 }
        for ((index, word) in words.withIndex()) {
            val mine = parts.indices.filter { parts[it].first == index }
            val hits = mine.map { matchedAt[it] }
            val whole = hits.isNotEmpty() && hits.all { it >= 0 } && hits.zipWithNext().all { (a, b) -> b == a + 1 }
            if (whole) {
                word.start = tokens[hits.first()].second
                word.intact = true
                firstToken[index] = hits.first()
                lastToken[index] = hits.last()
            } else {
                word.intact = false
            }
        }

        val corrections = mutableListOf<TypedCorrection>()
        var k = 0
        while (k < words.size) {
            if (firstToken[k] >= 0) {
                k++
                continue
            }
            val runStart = k
            while (k < words.size && firstToken[k] < 0) k++
            val runEnd = k - 1
            val runLength = runEnd - runStart + 1
            if (runLength > MAX_TYPED_RUN) continue
            val before = if (runStart > 0) lastToken[runStart - 1] else -1
            val after = if (k < words.size) firstToken[k] else -1
            val range = when {
                before >= 0 && after >= 0 -> (before + 1) until after
                before >= 0 -> (before + 1) until minOf(tokens.size, before + 1 + runLength)
                after >= 0 -> maxOf(0, after - runLength) until after
                else -> continue
            }
            val replacement = range.map { tokens[it] }
            if (replacement.isEmpty()) continue
            if (before >= 0 && after >= 0) {
                if (replacement.size > runLength + 1) continue
            } else {
                // One anchor: the same number of tokens, right where the run stood.
                if (replacement.size != runLength) continue
                if (kotlin.math.abs(replacement.first().second - previousStarts[runStart]) > MAX_TYPED_DRIFT) continue
            }
            val fromIndex = entry.words.indexOf(words[runStart])
            if (replacement.size == runLength) {
                // Word for word ("houské rohlike" → "housky rohlíky"): one correction per changed word.
                for (offset in 0 until runLength) {
                    val index = entry.words.indexOf(words[runStart + offset])
                    val now = VoiceWordJudge.coreOf(replacement[offset].first)
                    val heard = VoiceWordJudge.coreOf(entry.words[index].recognized)
                    if (now.none { it.isLetter() } || heard.isEmpty() || heard == now) continue
                    corrections.add(TypedCorrection(index, index, heard, now))
                }
                continue
            }
            val first = replacement.first().first
            val last = replacement.last().first
            val corrected = if (replacement.size == 1) {
                VoiceWordJudge.coreOf(first)
            } else {
                (
                    first.substring(VoiceWordJudge.coreStartIn(first)) + " " +
                        replacement.subList(1, replacement.size - 1).joinToString(" ") { it.first } +
                        (if (replacement.size > 2) " " else "") +
                        last.substring(0, VoiceWordJudge.coreStartIn(last) + VoiceWordJudge.coreOf(last).length)
                    ).replace("  ", " ")
            }
            if (corrected.none { it.isLetter() }) continue
            val toIndex = entry.words.indexOf(words[runEnd])
            val heard = recognizedRun(entry, fromIndex, toIndex)
            if (heard.isEmpty() || heard == corrected) continue
            corrections.add(TypedCorrection(fromIndex, toIndex, heard, corrected))
        }
        return corrections
    }

    /** The recognised words [from]..[to] of [entry], cores at the outer edges ("lukou i ostupu"). */
    private fun recognizedRun(entry: Entry, from: Int, to: Int): String {
        val ws = entry.words
        if (from == to) return VoiceWordJudge.coreOf(ws[from].recognized)
        val first = ws[from].recognized
        val last = ws[to].recognized
        val sb = StringBuilder(first.substring(VoiceWordJudge.coreStartIn(first)))
        for (i in from + 1 until to) if (!ws[i].merged) sb.append(' ').append(ws[i].recognized)
        sb.append(' ').append(last.substring(0, VoiceWordJudge.coreStartIn(last) + VoiceWordJudge.coreOf(last).length))
        return sb.toString()
    }

    /** Put back entries saved earlier (a restarted keyboard process). */
    fun restore(saved: List<Entry>) {
        entries.clear()
        entries.addAll(saved)
    }

    /** Hand every entry over (for confirmation) and start empty. */
    fun drain(): List<Entry> {
        val out = entries.toList()
        entries.clear()
        return out
    }

    fun clear() = entries.clear()

    companion object {
        /** How far an entry may have moved (text typed before it) and still be re-located. */
        const val SLACK_CHARS = 400

        /** The longest run of dictated words a typed-over correction may replace. */
        const val MAX_TYPED_RUN = 4

        /** With one anchor only, how far (chars) the typed replacement may sit from where the run was. */
        const val MAX_TYPED_DRIFT = 24

        fun tokenize(text: String, offset: Int): List<Pair<String, Int>> {
            val out = mutableListOf<Pair<String, Int>>()
            var i = 0
            while (i < text.length) {
                if (text[i].isWhitespace()) {
                    i++
                    continue
                }
                val start = i
                while (i < text.length && !text[i].isWhitespace()) i++
                out.add(text.substring(start, i) to offset + start)
            }
            return out
        }
    }
}
