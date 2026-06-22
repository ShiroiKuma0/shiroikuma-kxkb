package com.urik.keyboard.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class SpecialTokensTest {
    private val epoch = Date(0L)

    @Test
    fun `cursor pairs split into prefix and suffix`() {
        assertEquals(SpecialTokens.Effect.Commit("(", ")"), SpecialTokens.interpret("(…)"))
        assertEquals(SpecialTokens.Effect.Commit("“", "”"), SpecialTokens.interpret("“…”"))
        assertEquals(SpecialTokens.Effect.Commit("«", "»"), SpecialTokens.interpret("«…»"))
        assertEquals(SpecialTokens.Effect.Commit("\"", "\""), SpecialTokens.interpret("\"…\""))
        // bracket pair must NOT be read as an action — the `…` keeps it a pair
        assertEquals(SpecialTokens.Effect.Commit("[", "]"), SpecialTokens.interpret("[…]"))
    }

    @Test
    fun `action tokens map to editor actions`() {
        assertEquals(SpecialTokens.Effect.Action("paste"), SpecialTokens.interpret("[Paste]"))
        assertEquals(SpecialTokens.Effect.Action("copy"), SpecialTokens.interpret("[Copy]"))
        assertEquals(SpecialTokens.Effect.Action("cut"), SpecialTokens.interpret("[Cut]"))
        assertEquals(SpecialTokens.Effect.Action("select_all"), SpecialTokens.interpret("[All]"))
        assertEquals(SpecialTokens.Effect.Action("tab"), SpecialTokens.interpret("[Tab]"))
    }

    @Test
    fun `date templates expand via the pattern`() {
        val e = SpecialTokens.interpret("{{yyyy-MM-dd ", epoch) as SpecialTokens.Effect.Commit
        assertTrue("got: '${e.prefix}'", Regex("""\d{4}-\d{2}-\d{2} """).matches(e.prefix))
        assertEquals("", e.suffix)
    }

    @Test
    fun `a bad date pattern falls back to a literal`() {
        // an unterminated quote is an invalid SimpleDateFormat pattern
        assertEquals(SpecialTokens.Effect.Commit("{{yyyy'"), SpecialTokens.interpret("{{yyyy'"))
    }

    @Test
    fun `literals and the unhandled MC macro commit verbatim`() {
        assertEquals(SpecialTokens.Effect.Commit("+"), SpecialTokens.interpret("+"))
        assertEquals(SpecialTokens.Effect.Commit(":@)"), SpecialTokens.interpret(":@)"))
        assertEquals(SpecialTokens.Effect.Commit("…"), SpecialTokens.interpret("…")) // a bare ellipsis is literal
        assertEquals(SpecialTokens.Effect.Commit("[MC:# [DEL]:#]"), SpecialTokens.interpret("[MC:# [DEL]:#]"))
    }

    @Test
    fun `isSpecial flags only the interpreted tokens`() {
        listOf("(…)", "“…”", "[…]", "[Paste]", "[Tab]", "{{yyyy-MM-dd ").forEach {
            assertTrue("special: $it", SpecialTokens.isSpecial(it))
        }
        listOf("+", "-", "*", "#", ":@)", "☺", "…", "[MC:# [DEL]:#]", "the").forEach {
            assertFalse("literal: $it", SpecialTokens.isSpecial(it))
        }
    }
}
