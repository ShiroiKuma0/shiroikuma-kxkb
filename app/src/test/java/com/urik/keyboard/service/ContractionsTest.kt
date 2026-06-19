package com.urik.keyboard.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractionsTest {
    @Test
    fun `dont maps to don't`() {
        assertEquals("don't", Contractions.forWord("dont")?.word)
    }

    @Test
    fun `lets maps to let's`() {
        assertEquals("let's", Contractions.forWord("lets")?.word)
    }

    @Test
    fun `pronoun im maps to capital I'm and preserves case`() {
        val s = Contractions.forWord("im")
        assertEquals("I'm", s?.word)
        assertTrue("I-contractions must preserve case", s?.preserveCase == true)
    }

    @Test
    fun `lookup is case-insensitive`() {
        assertEquals("don't", Contractions.forWord("Dont")?.word)
        assertEquals("don't", Contractions.forWord("DONT")?.word)
        assertEquals("I've", Contractions.forWord("IVE")?.word)
    }

    @Test
    fun `general contractions do not preserve case so they capitalize at sentence start`() {
        assertTrue(Contractions.forWord("dont")?.preserveCase == false)
        assertTrue(Contractions.forWord("cant")?.preserveCase == false)
    }

    @Test
    fun `unknown word returns null`() {
        assertNull(Contractions.forWord("hello"))
        assertNull(Contractions.forWord(""))
    }

    @Test
    fun `injectForWord places contraction first for English`() {
        val existing = listOf(
            SpellingSuggestion("done", 0.9, 0, "dictionary"),
            SpellingSuggestion("dot", 0.8, 1, "dictionary")
        )
        val result = Contractions.injectForWord(existing, "dont", "en")
        assertEquals("don't", result.first().word)
        assertEquals(3, result.size)
    }

    @Test
    fun `injectForWord dedupes an existing contraction surface form`() {
        val existing = listOf(
            SpellingSuggestion("don't", 0.9, 0, "dictionary"),
            SpellingSuggestion("done", 0.8, 1, "dictionary")
        )
        val result = Contractions.injectForWord(existing, "dont", "en")
        assertEquals("don't", result.first().word)
        assertEquals(1, result.count { it.word == "don't" })
        assertEquals(2, result.size)
    }

    @Test
    fun `injectForWord is a no-op for non-English`() {
        val existing = listOf(SpellingSuggestion("dont", 0.9, 0, "dictionary"))
        val result = Contractions.injectForWord(existing, "dont", "ru")
        assertEquals(existing, result)
    }

    @Test
    fun `injectForWord handles English locale variants like en-US`() {
        val result = Contractions.injectForWord(emptyList(), "cant", "en-US")
        assertEquals("can't", result.first().word)
    }

    @Test
    fun `injectForWord passes through when no contraction matches`() {
        val existing = listOf(SpellingSuggestion("hello", 0.9, 0, "dictionary"))
        val result = Contractions.injectForWord(existing, "hello", "en")
        assertEquals(existing, result)
    }

    // --- candidatesForBands: cluster-keyboard contractions (Bug 1) ---

    @Test
    fun `candidatesForBands offers don't when each bare-key char is in its tap band`() {
        // Four taps; each band contains the intended bare-key char (d, o, n, t) plus its cluster mates.
        // (On device the centre letters might read "gott" — but the BANDS still contain d,o,n,t.)
        val bands = listOf(
            setOf('d', 'g', 'o'),
            setOf('o', 'i', 'p'),
            setOf('n', 't', 'm'),
            setOf('t', 'r', 'y')
        )
        val values = Contractions.candidatesForBands(bands).map { it.first }
        assertTrue("don't must be offered", "don't" in values)
    }

    @Test
    fun `candidatesForBands offers what's and let's when consistent with bands`() {
        // "whats" -> w,h,a,t,s
        val whats = listOf(
            setOf('w', 'q'),
            setOf('h', 'j'),
            setOf('a', 's'),
            setOf('t', 'r'),
            setOf('s', 'd')
        )
        assertTrue("what's" in Contractions.candidatesForBands(whats).map { it.first })

        // "lets" -> l,e,t,s
        val lets = listOf(
            setOf('l', 'k'),
            setOf('e', 'w'),
            setOf('t', 'r'),
            setOf('s', 'a')
        )
        assertTrue("let's" in Contractions.candidatesForBands(lets).map { it.first })
    }

    @Test
    fun `candidatesForBands rejects a contraction whose length differs from tap count`() {
        // Only three taps, but "dont" is four chars — must not match.
        val bands = listOf(setOf('d', 'o'), setOf('o', 'n'), setOf('n', 't'))
        assertTrue("dont", Contractions.candidatesForBands(bands).none { it.first == "don't" })
    }

    @Test
    fun `candidatesForBands rejects when a bare-key char is outside its band`() {
        // "dont" length-matches, but the 3rd band lacks 'n', so it must not be offered.
        val bands = listOf(
            setOf('d', 'g'),
            setOf('o', 'i'),
            setOf('x', 'z'),
            setOf('t', 'r')
        )
        assertTrue(Contractions.candidatesForBands(bands).none { it.first == "don't" })
    }

    @Test
    fun `candidatesForBands marks pronoun-I forms preserveCase`() {
        // "im" -> i,m
        val bands = listOf(setOf('i', 'u'), setOf('m', 'n'))
        val im = Contractions.candidatesForBands(bands).firstOrNull { it.first == "I'm" }
        assertTrue("I'm must be offered", im != null)
        assertTrue("I-contractions preserve case", im?.second == true)
    }

    @Test
    fun `candidatesForBands marks general forms as not preserveCase`() {
        val bands = listOf(setOf('d', 'g'), setOf('o', 'i'), setOf('n', 'm'), setOf('t', 'r'))
        val dont = Contractions.candidatesForBands(bands).firstOrNull { it.first == "don't" }
        assertTrue(dont != null)
        assertEquals(false, dont?.second)
    }

    @Test
    fun `candidatesForBands returns empty for empty input`() {
        assertTrue(Contractions.candidatesForBands(emptyList()).isEmpty())
    }
}
