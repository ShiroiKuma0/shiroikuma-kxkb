package com.urik.keyboard.service.voice

import java.io.File

/**
 * Text → Whisper token ids, for biasing the decoder towards the user's own words.
 *
 * The engine only detokenizes, and the keyboard ships no tokenizer file: the vocabulary is read from the
 * installed model itself — the detokenizer ONNX carries it as the `id_vocab` attribute of its BpeDecoder
 * node (one GPT-2 byte-level token per line, line number = token id). Whisper's tokenizer is tiktoken-style
 * byte-level BPE whose merge rank IS the token id, so encoding is: split the text into its pre-tokenizer
 * chunks, start each chunk from single bytes, and keep merging the adjacent pair whose concatenation has the
 * lowest id until no pair is in the vocabulary.
 */
class WhisperTokenizer internal constructor(private val ranks: Map<String, Int>) {
    val size: Int get() = ranks.size

    /** Token id → its bytes (a token may hold part of a multi-byte character). */
    private val bytesById: Map<Int, ByteArray> by lazy {
        HashMap<Int, ByteArray>(ranks.size * 2).apply {
            for ((text, id) in ranks) put(id, text.toByteArray(Charsets.ISO_8859_1))
        }
    }

    /** The bytes token [id] stands for, or null for a special / unknown id. */
    fun bytesOf(id: Int): ByteArray? = bytesById[id]

    /** Token ids of [text] (e.g. " Pavel" — the leading space belongs to the word, as Whisper writes it). */
    fun encode(text: String): IntArray {
        val out = ArrayList<Int>()
        for (match in PRETOKENIZE.findAll(text)) {
            val bytes = match.value.toByteArray(Charsets.UTF_8)
            val parts = ArrayList<String>(bytes.size)
            for (b in bytes) parts.add(String(byteArrayOf(b), Charsets.ISO_8859_1))
            while (parts.size > 1) {
                var best = -1
                var bestRank = Int.MAX_VALUE
                for (i in 0 until parts.size - 1) {
                    val rank = ranks[parts[i] + parts[i + 1]] ?: continue
                    if (rank < bestRank) {
                        bestRank = rank
                        best = i
                    }
                }
                if (best < 0) break
                parts[best] = parts[best] + parts[best + 1]
                parts.removeAt(best + 1)
            }
            for (p in parts) out.add(ranks[p] ?: return IntArray(0))
        }
        return out.toIntArray()
    }

    companion object {
        /** The GPT-2 / tiktoken pre-tokenizer, simplified to what single words and short phrases need. */
        private val PRETOKENIZE = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+""")

        @Volatile private var cached: Pair<String, WhisperTokenizer>? = null

        /** The tokenizer of the model whose detokenizer is [file]; cached per file version. Null when unreadable. */
        fun load(file: File): WhisperTokenizer? {
            val stamp = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            cached?.let { (key, tokenizer) -> if (key == stamp) return tokenizer }
            val tokenizer = try {
                fromDetokenizer(file.readBytes())
            } catch (_: Exception) {
                null
            } ?: return null
            cached = stamp to tokenizer
            return tokenizer
        }

        /** Parse the `id_vocab` attribute out of the detokenizer model bytes. */
        fun fromDetokenizer(model: ByteArray): WhisperTokenizer? {
            val name = "id_vocab".toByteArray(Charsets.US_ASCII)
            var at = indexOf(model, name, 0)
            while (at >= 0) {
                // AttributeProto: name (field 1) then `s` (field 4, wire type 2 → tag 0x22) with a varint length.
                var p = at + name.size
                if (p < model.size && model[p] == 0x22.toByte()) {
                    p++
                    var length = 0L
                    var shift = 0
                    while (p < model.size) {
                        val b = model[p++].toInt() and 0xFF
                        length = length or ((b and 0x7F).toLong() shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    if (length > 0 && p + length <= model.size) {
                        val text = String(model, p, length.toInt(), Charsets.UTF_8)
                        return fromVocabLines(text.split('\n'))
                    }
                }
                at = indexOf(model, name, at + 1)
            }
            return null
        }

        /** One GPT-2 byte-level token per line, the line number being the token id. */
        fun fromVocabLines(lines: List<String>): WhisperTokenizer? {
            val decoder = byteDecoder()
            val ranks = HashMap<String, Int>(lines.size * 2)
            lines.forEachIndexed { id, token ->
                // Special tokens (<|endoftext|>, <|cs|>, …) are never text a word encodes to.
                if (token.isEmpty() || (token.startsWith("<|") && token.endsWith("|>"))) return@forEachIndexed
                val bytes = ByteArray(token.length)
                for (i in token.indices) {
                    bytes[i] = (decoder[token[i]] ?: return@forEachIndexed).toByte()
                }
                ranks.putIfAbsent(String(bytes, Charsets.ISO_8859_1), id)
            }
            // Every single byte must be a token, or encoding could fail on ordinary text.
            return ranks.takeIf { it.size > 256 }?.let { WhisperTokenizer(it) }
        }

        /** GPT-2's printable stand-in character for each byte, reversed. */
        private fun byteDecoder(): Map<Char, Int> {
            val bs = ArrayList<Int>()
            bs.addAll('!'.code..'~'.code)
            bs.addAll('¡'.code..'¬'.code)
            bs.addAll('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            return bs.indices.associate { cs[it].toChar() to bs[it] }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in from..haystack.size - needle.size) {
                for (k in needle.indices) if (haystack[i + k] != needle[k]) continue@outer
                return i
            }
            return -1
        }
    }
}
