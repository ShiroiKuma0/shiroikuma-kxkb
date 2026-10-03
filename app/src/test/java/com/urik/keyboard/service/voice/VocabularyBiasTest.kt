package com.urik.keyboard.service.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VocabularyBiasTest {
    /** GPT-2's printable stand-in for a byte (the vocabulary file's encoding). */
    private val byteEncoder: Map<Int, Char> by lazy {
        val bs = ArrayList<Int>()
        bs.addAll('!'.code..'~'.code)
        bs.addAll('¡'.code..'¬'.code)
        bs.addAll('®'.code..'ÿ'.code)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) {
            bs.add(b)
            cs.add(256 + n)
            n++
        }
        bs.indices.associate { bs[it] to cs[it].toChar() }
    }

    private fun encoded(text: String) = text.toByteArray(Charsets.UTF_8).joinToString("") { byteEncoder[it.toInt() and 0xFF]!!.toString() }

    /** 256 single bytes (ids 0–255), then merges in rank order: id 256 = " P", 257 = "av", 258 = " Pav", … */
    private val merges = listOf(" P", "av", " Pav", "le", "el", "ou", "ka", "č")
    private val tokenizer: WhisperTokenizer by lazy {
        val lines = (0..255).map { byteEncoder[it]!!.toString() } + merges.map(::encoded) + "<|endoftext|>"
        WhisperTokenizer.fromVocabLines(lines)!!
    }

    private fun id(token: String) = 256 + merges.indexOf(token)

    @Test
    fun `byte-level BPE merges by rank`() {
        assertArrayEquals(intArrayOf(id(" Pav"), id("le")), tokenizer.encode(" Pavle"))
        assertArrayEquals(intArrayOf(id(" Pav"), id("el")), tokenizer.encode(" Pavel"))
        // A multi-byte letter becomes its merged token; unknown pairs stay single bytes.
        assertArrayEquals(intArrayOf('x'.code, id("č")), tokenizer.encode("xč"))
    }

    @Test
    fun `the vocabulary is read out of a detokenizer model's id_vocab attribute`() {
        val vocab = ((0..255).map { byteEncoder[it]!!.toString() } + merges.map(::encoded)).joinToString("\n")
        val payload = vocab.toByteArray(Charsets.UTF_8)
        val model = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(1, 2, 3))
            write("id_vocab".toByteArray())
            write(0x22)
            var n = payload.size
            while (true) {
                val b = n and 0x7F
                n = n ushr 7
                if (n == 0) {
                    write(b)
                    break
                }
                write(b or 0x80)
            }
            write(payload)
            write(byteArrayOf(9, 9))
        }.toByteArray()
        val parsed = WhisperTokenizer.fromDetokenizer(model)
        assertNotNull(parsed)
        assertArrayEquals(intArrayOf(id(" Pav"), id("le")), parsed!!.encode(" Pavle"))
    }

    @Test
    fun `only the continuation of a started word is boosted, never its first token`() {
        val bias = VocabularyBias.build(tokenizer, listOf("Pavle"), bonus = 3f)
        bias.reset()
        val logits = FloatArray(300)
        bias.adjust(logits)
        assertEquals(0f, logits.sum())

        bias.accept(id(" Pav"))
        bias.adjust(logits)
        assertEquals(3f, logits[id("le")])
        assertEquals(0f, logits[id("el")])

        // Off the path: the boost is gone.
        val fresh = FloatArray(300)
        bias.accept('x'.code)
        bias.adjust(fresh)
        assertEquals(0f, fresh.sum())
    }

    @Test
    fun `words you usually type next get a small first-token bonus, then the full continuation`() {
        val bias = VocabularyBias.build(tokenizer, emptyList(), bonus = 2f, followers = mapOf("ahoj" to listOf("pavle")))
        assertEquals(1, bias.predictedWords)
        bias.reset()
        for (t in tokenizer.encode(" Ahoj")) bias.accept(t)
        val first = FloatArray(300)
        bias.adjust(first)
        assertEquals(0.5f, first[' '.code])

        bias.accept(' '.code) // the follower starts
        val next = FloatArray(300)
        bias.adjust(next)
        assertEquals(2f, next['p'.code])
    }

    @Test
    fun `nothing is predicted after a sentence end`() {
        val bias = VocabularyBias.build(tokenizer, emptyList(), bonus = 2f, followers = mapOf("ahoj" to listOf("pavle")))
        bias.reset()
        for (t in tokenizer.encode(" Ahoj.")) bias.accept(t)
        val logits = FloatArray(300)
        bias.adjust(logits)
        assertEquals(0f, logits.sum())
    }

    @Test
    fun `one-token words add nothing and reset clears the state`() {
        assertEquals(0, VocabularyBias(listOf(intArrayOf(id("č"))), 3f).size)
        assertEquals(1, VocabularyBias(listOf(intArrayOf(id(" Pav"), id("le"))), 3f).size)
        val two = VocabularyBias.build(tokenizer, listOf("Pavle"), bonus = 3f)
        two.accept(id(" Pav"))
        two.reset()
        val logits = FloatArray(300)
        two.adjust(logits)
        assertEquals(0f, logits.sum())
    }
}
