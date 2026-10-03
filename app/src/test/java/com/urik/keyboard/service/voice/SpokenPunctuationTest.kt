package com.urik.keyboard.service.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenPunctuationTest {
    private fun cs(text: String) = SpokenPunctuation.apply(text, "cs")

    @Test
    fun `spoken marks replace the words`() {
        assertEquals("Ahoj, jak se máš?", cs("Ahoj čárka jak se máš otazník"))
        assertEquals("Pozor! Konec.", cs("Pozor vykřičník konec tečka"))
        assertEquals("Seznam: mléko; chleba…", cs("Seznam dvojtečka mléko středník chleba tři tečky"))
    }

    @Test
    fun `Whisper's own commas around a command give way to it`() {
        assertEquals("Ahoj, jak se máš?", cs("Ahoj, čárka, jak se máš, otazník."))
        assertEquals("Konec.", cs("Konec, tečka."))
    }

    @Test
    fun `the escape keeps the next command a word and disappears`() {
        assertEquals("To je tečka a hotovo", cs("To je doslova tečka a hotovo"))
        assertEquals("Na konci je tečka.", cs("Na konci je doslova tečka."))
        assertEquals("Řekl to doslova takhle", cs("Řekl to doslova takhle"))
        assertEquals("Doslova", cs("Doslova"))
    }

    @Test
    fun `the escape turns a bare mark Whisper made back into the word`() {
        assertEquals("Napiš tečka", cs("Napiš doslova ."))
    }

    @Test
    fun `doubled escape gives the escape word`() {
        assertEquals("Je to doslova pravda", cs("Je to doslova doslova pravda"))
    }

    @Test
    fun `a command misspelled by the recogniser in its accents still counts`() {
        assertEquals("Zkouška, zkouška.", cs("Zkouška čarka, zkouška."))
        assertEquals("Konec. A dál", cs("Konec tecka a dál"))
        assertEquals("Je to tecka", cs("Je to doslova tecka"))
    }

    @Test
    fun `Whisper's own punctuation is untouched when no command is spoken`() {
        assertEquals("Uvidíme, jak nám to půjde.", cs("Uvidíme, jak nám to půjde."))
        assertEquals("Ahoj! Co děláš? Nic.", cs("Ahoj! Co děláš? Nic."))
        assertEquals("Zkouška, zkouška, další.", cs("Zkouška čárka zkouška, další."))
    }

    @Test
    fun `inflected forms stay words`() {
        assertEquals("Dej tam čárku a tečku", cs("Dej tam čárku a tečku"))
    }

    @Test
    fun `a sentence mark capitalises the next word, a line break too`() {
        assertEquals("Konec. Další věta", cs("Konec tečka další věta"))
        assertEquals("První\nDruhý", cs("První nový řádek druhý"))
        assertEquals("První\n\nDruhý", cs("První nový odstavec druhý"))
    }

    @Test
    fun `quotes alternate open and close, dashes follow the language`() {
        assertEquals("Řekl „ano“ a šel", cs("Řekl uvozovky ano uvozovky a šel"))
        assertEquals("slovo–slovo", cs("slovo pomlčka slovo"))
        assertEquals("česko-slovenský", cs("česko spojovník slovenský"))
        assertEquals("Он пришёл – и ушёл", SpokenPunctuation.apply("Он пришёл тире и ушёл", "ru"))
    }

    @Test
    fun `multi-word commands win over their first word`() {
        assertEquals("a; b", SpokenPunctuation.apply("a точка с запятой b", "ru"))
        assertEquals("Hello, world.", SpokenPunctuation.apply("Hello comma world full stop", "en"))
        assertEquals("Is it literally? Yes", SpokenPunctuation.apply("Is it literally question mark yes", "en"))
    }

    @Test
    fun `a chunk opening with a mark glues to the text before it`() {
        val out = cs("Čárka a pak")
        assertEquals(", a pak", out)
        assertTrue(SpokenPunctuation.startsGlued(out))
        assertFalse(SpokenPunctuation.startsGlued("Ahoj"))
        assertTrue(SpokenPunctuation.endsGlued(cs("Konec nový řádek")))
    }

    @Test
    fun `Japanese and unknown languages pass through`() {
        assertEquals("まる てん", SpokenPunctuation.apply("まる てん", "ja"))
        assertFalse(SpokenPunctuation.supports("ja"))
    }
}
