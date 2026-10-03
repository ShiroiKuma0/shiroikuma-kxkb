package com.urik.keyboard.service.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class CzechCommasTest {
    private fun c(text: String) = CzechCommas.apply(text)

    @Test
    fun `commas go before subordinating conjunctions`() {
        assertEquals("Myslím, že přijde.", c("Myslím že přijde."))
        assertEquals("Přišel, protože chtěl.", c("Přišel protože chtěl."))
        assertEquals("Nevím, kde je.", c("Nevím kde je."))
        assertEquals("Chci, abys šel.", c("Chci abys šel."))
    }

    @Test
    fun `relative pronouns, with the comma before a governing preposition`() {
        assertEquals("To je dům, který stojí.", c("To je dům který stojí."))
        assertEquals("To je dům, ve kterém bydlím.", c("To je dům ve kterém bydlím."))
    }

    @Test
    fun `no comma where one is already, at the start, or after a joining word`() {
        assertEquals("Myslím, že přijde.", c("Myslím, že přijde."))
        assertEquals("Že přijde, vím.", c("Že přijde, vím."))
        assertEquals("Řekl a že to ví.", c("Řekl a že to ví."))
        assertEquals("Půjdu i když prší.", c("Půjdu i když prší."))
        assertEquals("Udělal to tak, že spadl.", c("Udělal to tak že spadl."))
    }

    @Test
    fun `jak opens a clause unless a comparison word comes first`() {
        assertEquals("Uvidíme, jak nám to půjde.", c("Uvidíme jak nám to půjde."))
        assertEquals("Udělej to tak jak chceš.", c("Udělej to tak jak chceš."))
        assertEquals("Jak se máš?", c("Jak se máš?"))
    }

    @Test
    fun `ambiguous words are left alone`() {
        assertEquals("Je větší než ty.", c("Je větší než ty."))
        assertEquals("Přijď co nejdřív.", c("Přijď co nejdřív."))
    }
}
