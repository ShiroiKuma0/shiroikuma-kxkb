package com.urik.keyboard.service.voice

import com.whisperonnx.voice_translation.neural_networks.voice.LogitBias
import java.io.ByteArrayOutputStream

/**
 * Shallow fusion towards how YOU write, in two parts:
 *
 * 1. **Your vocabulary** — a trie of the token sequences of your words (user dictionary, corrections,
 *    confirmed dictated words). Once the decoder has picked a token that STARTS one of these words, the tokens
 *    that continue it get [bonus] — a half-recognised name is finished the way you write it ("Pav" → "le" of
 *    "Pavle"). A word's first token is never boosted: that would pull ordinary speech towards the list.
 * 2. **Your typing** — the next-word statistics from your keyboard use. While the word being decoded is one
 *    you often follow with certain words, the FIRST token of those words gets the smaller [predictionBonus]
 *    (a prediction is less certain than a word already begun); once one of them has started, its continuation
 *    gets the full [bonus]. Nothing is predicted after a sentence end.
 *
 * The bonuses only tilt close calls; a clearly different sound still wins. One instance per dictation
 * session; [reset]/[adjust]/[accept] arrive on the recogniser's thread.
 */
class VocabularyBias(
    sequences: Collection<IntArray>,
    private val bonus: Float,
    nextWords: Map<String, Collection<IntArray>> = emptyMap(),
    private val predictionBonus: Float = 0f,
    private val tokenBytes: (Int) -> ByteArray? = { null }
) : LogitBias {
    private class Node {
        val children = HashMap<Int, Node>(4)
    }

    private val root = Node()

    /** Per preceding word (lower-cased), the trie of the words you follow it with. */
    private val predictionRoots = HashMap<String, Node>()

    private var active: List<Node> = emptyList()
    private val currentWord = ByteArrayOutputStream()

    /** How many distinct vocabulary sequences the trie holds. */
    val size: Int

    /** How many preceding words have next-word predictions. */
    val predictedWords: Int get() = predictionRoots.size

    init {
        var count = 0
        for (sequence in sequences) {
            if (sequence.size < 2) continue // a one-token word has nothing to continue
            insert(root, sequence)
            count++
        }
        size = count
        for ((previous, followers) in nextWords) {
            val node = Node()
            for (sequence in followers) if (sequence.isNotEmpty()) insert(node, sequence)
            if (node.children.isNotEmpty()) predictionRoots[previous] = node
        }
    }

    private fun insert(at: Node, sequence: IntArray) {
        var node = at
        for (token in sequence) node = node.children.getOrPut(token) { Node() }
    }

    override fun reset() {
        active = emptyList()
        currentWord.reset()
    }

    override fun adjust(logits: FloatArray) {
        val boosted = HashSet<Int>()
        for (node in active) {
            for (token in node.children.keys) {
                if (token in logits.indices && boosted.add(token)) logits[token] += bonus
            }
        }
        if (predictionBonus > 0f) {
            val predictions = predictionRoots[wordKey()] ?: return
            for (token in predictions.children.keys) {
                if (token in logits.indices && boosted.add(token)) logits[token] += predictionBonus
            }
        }
    }

    override fun accept(token: Int) {
        val bytes = tokenBytes(token)
        val startsWord = bytes != null && bytes.isNotEmpty() && bytes[0] == SPACE
        val next = ArrayList<Node>(active.size + 2)
        for (node in active) node.children[token]?.let { if (it.children.isNotEmpty()) next.add(it) }
        root.children[token]?.let { if (it.children.isNotEmpty()) next.add(it) }
        if (startsWord) {
            // The word before is complete: a predicted follower that starts here is continued in full.
            predictionRoots[wordKey()]?.children?.get(token)?.let { if (it.children.isNotEmpty()) next.add(it) }
            currentWord.reset()
            currentWord.write(bytes!!, 1, bytes.size - 1)
        } else if (bytes != null) {
            currentWord.write(bytes, 0, bytes.size)
        }
        active = next
    }

    /** The word being decoded, as a prediction key: lower-cased core; empty after a sentence end. */
    private fun wordKey(): String {
        val text = currentWord.toString(Charsets.UTF_8.name())
        if (text.isEmpty() || text.last() in SENTENCE_END) return ""
        return VoiceWordJudge.coreOf(text).lowercase()
    }

    companion object {
        private const val SPACE = ' '.code.toByte()
        private const val SENTENCE_END = ".?!…"

        /** How many followers per preceding word are predicted. */
        const val MAX_FOLLOWERS = 5

        /**
         * The bias for [words] — each as Whisper writes it mid-sentence (" word") and at a sentence start
         * ("Word"), lower- and capitalised — plus [followers]: for each word you type (lower-cased), the words you
         * most often type after it.
         */
        fun build(
            tokenizer: WhisperTokenizer,
            words: Collection<String>,
            bonus: Float,
            followers: Map<String, List<String>> = emptyMap(),
            predictionBonus: Float = bonus / 4f
        ): VocabularyBias {
            val sequences = LinkedHashMap<String, IntArray>()
            for (raw in words) {
                val word = raw.trim()
                if (word.isEmpty()) continue
                val capitalised = word.replaceFirstChar { it.uppercaseChar() }
                for (variant in linkedSetOf(" $word", " $capitalised", word, capitalised)) {
                    if (variant in sequences) continue
                    val ids = tokenizer.encode(variant)
                    if (ids.isNotEmpty()) sequences[variant] = ids
                }
            }
            val next = HashMap<String, List<IntArray>>()
            for ((previous, after) in followers) {
                val encoded = after.take(MAX_FOLLOWERS).map { tokenizer.encode(" ${it.trim()}") }.filter { it.isNotEmpty() }
                if (encoded.isNotEmpty()) next[previous.lowercase()] = encoded
            }
            return VocabularyBias(sequences.values, bonus, next, predictionBonus, tokenizer::bytesOf)
        }
    }
}
