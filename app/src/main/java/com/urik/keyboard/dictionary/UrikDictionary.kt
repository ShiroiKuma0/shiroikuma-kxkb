@file:Suppress("UnnecessaryParentheses")

package com.urik.keyboard.dictionary

import java.io.InputStream

class UrikDictionary(inputStream: InputStream, private val removedWords: Set<String> = emptySet()) {
    private val data: ByteArray = inputStream.readBytes()

    val wordCount: Int
    val stateCount: Int
    private val stateTableOffset: Int = UrikFormat.HEADER_SIZE

    init {
        require(data.size >= UrikFormat.HEADER_SIZE) { "URIK file too small: ${data.size} bytes" }
        val magic = data.copyOfRange(0, 4)
        require(magic.contentEquals(UrikFormat.MAGIC)) { "Invalid URIK magic: ${magic.toList()}" }
        val version = readInt(4)
        require(version == UrikFormat.VERSION) { "Unsupported URIK version: $version" }
        wordCount = readInt(8)
        stateCount = readInt(12)
    }

    private fun readInt(offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)

    fun lookup(word: String): Boolean = getFreqByte(word) != null

    fun getFrequency(word: String): Long {
        val b = getFreqByte(word) ?: return 0L
        return UrikFormat.dequantizeFreq(b)
    }

    private fun isRemoved(word: String): Boolean = removedWords.isNotEmpty() && word.lowercase() in removedWords

    private fun getFreqByte(word: String): Int? {
        if (isRemoved(word)) return null
        var stateOffset = stateTableOffset
        for ((idx, ch) in word.withIndex()) {
            val arc = findArc(stateOffset, ch) ?: return null
            stateOffset = stateTableOffset + arc.targetRelOffset
            if (idx == word.length - 1) {
                val isFinal = (data[stateOffset].toInt() and 0x80) != 0
                return if (isFinal) arc.freq else null
            }
        }
        return null
    }

    fun getCandidates(word: String, maxEditDistance: Int = 2): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()
        val auto = LevenshteinAutomaton(word, maxEditDistance)
        dfs(stateTableOffset, auto, auto.start(), StringBuilder(), results)
        return results.sortedBy { it.second }
    }

    private fun dfs(
        stateAbsOffset: Int,
        auto: LevenshteinAutomaton,
        autoState: LevenshteinAutomaton.State,
        path: StringBuilder,
        results: MutableList<Pair<String, Int>>
    ) {
        if (!auto.canReachFinal(autoState)) return

        val stateHeader = data[stateAbsOffset].toInt() and 0xFF
        val arcCount = stateHeader and 0x7F
        val isFinalDawg = (stateHeader and 0x80) != 0

        if (isFinalDawg && auto.isAccepting(autoState)) {
            val word = path.toString()
            if (!isRemoved(word)) {
                results.add(word to autoState.row.last())
            }
        }

        var arcOffset = stateAbsOffset + 1
        repeat(arcCount) {
            val labelHi = data[arcOffset].toInt() and 0xFF
            val labelLo = data[arcOffset + 1].toInt() and 0xFF
            val label = Char((labelHi shl 8) or labelLo)
            val targetHi = data[arcOffset + 3].toInt() and 0xFF
            val targetMid = data[arcOffset + 4].toInt() and 0xFF
            val targetLo = data[arcOffset + 5].toInt() and 0xFF
            val targetRelOffset = (targetHi shl 16) or (targetMid shl 8) or targetLo
            arcOffset += 6

            val nextAutoState = auto.step(autoState, label)
            path.append(label)
            dfs(stateTableOffset + targetRelOffset, auto, nextAutoState, path, results)
            path.deleteCharAt(path.length - 1)
        }
    }

    /**
     * Every (word, frequency) in the dictionary — a full DAWG traversal. Offline tooling only (dictionary
     * cleaning); never called on the hot path. Ignores [removedWords] so the cleaner sees the raw contents.
     */
    fun allWords(): List<Pair<String, Long>> {
        val results = ArrayList<Pair<String, Long>>(1 shl 18)
        collectAllWords(stateTableOffset, StringBuilder(), 0, results)
        return results
    }

    private fun collectAllWords(
        stateAbsOffset: Int,
        path: StringBuilder,
        incomingFreqByte: Int,
        results: MutableList<Pair<String, Long>>
    ) {
        val stateHeader = data[stateAbsOffset].toInt() and 0xFF
        val arcCount = stateHeader and 0x7F
        if ((stateHeader and 0x80) != 0 && path.isNotEmpty()) {
            results.add(path.toString() to UrikFormat.dequantizeFreq(incomingFreqByte))
        }
        var arcOffset = stateAbsOffset + 1
        repeat(arcCount) {
            val labelHi = data[arcOffset].toInt() and 0xFF
            val labelLo = data[arcOffset + 1].toInt() and 0xFF
            val label = Char((labelHi shl 8) or labelLo)
            val freqByte = data[arcOffset + 2].toInt() and 0xFF
            val targetHi = data[arcOffset + 3].toInt() and 0xFF
            val targetMid = data[arcOffset + 4].toInt() and 0xFF
            val targetLo = data[arcOffset + 5].toInt() and 0xFF
            val targetRelOffset = (targetHi shl 16) or (targetMid shl 8) or targetLo
            arcOffset += 6
            path.append(label)
            collectAllWords(stateTableOffset + targetRelOffset, path, freqByte, results)
            path.deleteCharAt(path.length - 1)
        }
    }

    fun getWordsWithPrefix(prefix: String, maxResults: Int = 10): List<Pair<String, Long>> {
        var stateOffset = stateTableOffset
        var lastFreqByte = 0
        for (ch in prefix) {
            val arc = findArc(stateOffset, ch) ?: return emptyList()
            lastFreqByte = arc.freq
            stateOffset = stateTableOffset + arc.targetRelOffset
        }
        val results = mutableListOf<Pair<String, Long>>()
        collectWordsWithFreq(stateOffset, StringBuilder(prefix), lastFreqByte, results, maxResults)
        return results
    }

    private fun collectWordsWithFreq(
        stateAbsOffset: Int,
        path: StringBuilder,
        incomingFreqByte: Int,
        results: MutableList<Pair<String, Long>>,
        maxResults: Int
    ) {
        if (results.size >= maxResults) return
        val stateHeader = data[stateAbsOffset].toInt() and 0xFF
        val arcCount = stateHeader and 0x7F
        val isFinal = (stateHeader and 0x80) != 0

        if (isFinal) {
            val word = path.toString()
            if (!isRemoved(word)) {
                results.add(word to UrikFormat.dequantizeFreq(incomingFreqByte))
            }
        }
        if (results.size >= maxResults) return

        var arcOffset = stateAbsOffset + 1
        repeat(arcCount) {
            if (results.size >= maxResults) return
            val labelHi = data[arcOffset].toInt() and 0xFF
            val labelLo = data[arcOffset + 1].toInt() and 0xFF
            val label = Char((labelHi shl 8) or labelLo)
            val freqByte = data[arcOffset + 2].toInt() and 0xFF
            val targetHi = data[arcOffset + 3].toInt() and 0xFF
            val targetMid = data[arcOffset + 4].toInt() and 0xFF
            val targetLo = data[arcOffset + 5].toInt() and 0xFF
            val targetRelOffset = (targetHi shl 16) or (targetMid shl 8) or targetLo
            arcOffset += 6

            path.append(label)
            collectWordsWithFreq(stateTableOffset + targetRelOffset, path, freqByte, results, maxResults)
            path.deleteCharAt(path.length - 1)
        }
    }

    /**
     * Cluster prediction: enumerate dictionary words of length `allowedSets.size` whose i-th letter (accent-
     * folded) is in `allowedSets[i]` — a constrained DAWG walk. Returns (word, dequantized frequency),
     * ranked by frequency. The allowed sets must already be base-folded + lowercased by the caller; the
     * arc labels are folded here so accented dictionary letters (á, č, …) match a base-letter cluster.
     */
    fun clusterCandidates(allowedSets: List<Set<Char>>, maxResults: Int = 8): List<Pair<String, Long>> {
        if (allowedSets.isEmpty()) return emptyList()
        val results = mutableListOf<Pair<String, Long>>()
        // Collect a GENEROUS pool before ranking, not just maxResults: clusterDfs walks the DAWG in arc order,
        // NOT by frequency, so capping the DFS at maxResults can drop the highest-frequency word entirely if
        // it happens to be traversed late (Czech "teď" — by far the most frequent t·e·d match — was visited
        // after 16 rarer band-matches and never collected). Gather up to CLUSTER_COLLECT_CAP, then sort by
        // frequency and take the top maxResults so the bar shows the genuinely most frequent candidates.
        val collectCap = maxOf(maxResults, CLUSTER_COLLECT_CAP)
        clusterDfs(stateTableOffset, allowedSets, 0, 0, StringBuilder(), results, collectCap)
        return results.sortedByDescending { it.second }.take(maxResults)
    }

    private fun clusterDfs(
        stateAbsOffset: Int,
        allowedSets: List<Set<Char>>,
        pos: Int,
        incomingFreqByte: Int,
        path: StringBuilder,
        results: MutableList<Pair<String, Long>>,
        maxResults: Int
    ) {
        if (results.size >= maxResults) return
        val stateHeader = data[stateAbsOffset].toInt() and 0xFF
        if (pos == allowedSets.size) {
            if ((stateHeader and 0x80) != 0) {
                val word = path.toString()
                if (!isRemoved(word)) results.add(word to UrikFormat.dequantizeFreq(incomingFreqByte))
            }
            return
        }
        val allowed = allowedSets[pos]
        val arcCount = stateHeader and 0x7F
        var arcOffset = stateAbsOffset + 1
        repeat(arcCount) {
            if (results.size >= maxResults) return
            val labelHi = data[arcOffset].toInt() and 0xFF
            val labelLo = data[arcOffset + 1].toInt() and 0xFF
            val label = Char((labelHi shl 8) or labelLo)
            if (foldChar(label) in allowed) {
                val freqByte = data[arcOffset + 2].toInt() and 0xFF
                val targetHi = data[arcOffset + 3].toInt() and 0xFF
                val targetMid = data[arcOffset + 4].toInt() and 0xFF
                val targetLo = data[arcOffset + 5].toInt() and 0xFF
                val targetRel = (targetHi shl 16) or (targetMid shl 8) or targetLo
                path.append(label)
                clusterDfs(stateTableOffset + targetRel, allowedSets, pos + 1, freqByte, path, results, maxResults)
                path.deleteCharAt(path.length - 1)
            }
            arcOffset += 6
        }
    }

    /** Fold a single char to its base lowercase letter (ASCII fast path; NFD-strip otherwise). */
    private fun foldChar(c: Char): Char {
        if (c.code < 128) return c.lowercaseChar()
        val nfd = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
        val base = nfd.firstOrNull {
            Character.getType(it) != Character.NON_SPACING_MARK.toInt()
        } ?: c
        return base.lowercaseChar()
    }

    private companion object {
        // Upper bound on words gathered by a cluster DFS before frequency-ranking. Generous enough to capture
        // every realistic band-match (cluster words are short, so the band-product space is small) while
        // bounding worst-case cost on a long word with wide bands.
        const val CLUSTER_COLLECT_CAP = 2000
    }

    private data class ArcInfo(val freq: Int, val targetRelOffset: Int)

    private fun findArc(stateAbsOffset: Int, ch: Char): ArcInfo? {
        val arcCount = data[stateAbsOffset].toInt() and 0x7F
        val target = ch.code
        var arcOffset = stateAbsOffset + 1
        repeat(arcCount) {
            val labelHi = data[arcOffset].toInt() and 0xFF
            val labelLo = data[arcOffset + 1].toInt() and 0xFF
            if ((labelHi shl 8) or labelLo == target) {
                val freq = data[arcOffset + 2].toInt() and 0xFF
                val targetHi = data[arcOffset + 3].toInt() and 0xFF
                val targetMid = data[arcOffset + 4].toInt() and 0xFF
                val targetLo = data[arcOffset + 5].toInt() and 0xFF
                return ArcInfo(freq, (targetHi shl 16) or (targetMid shl 8) or targetLo)
            }
            arcOffset += 6
        }
        return null
    }
}
