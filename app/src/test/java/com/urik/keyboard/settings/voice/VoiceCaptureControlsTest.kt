package com.urik.keyboard.settings.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one order the walk review's pills are shown in, under every line (白い熊, 2026-10-20).
 *
 * The algebra is what makes dragging in one row mean something in all of them: a row shows only the
 * controls it has, so a drop has to be read as "this pill moved HERE among the ones you can see" and
 * written back without disturbing the pills that row never showed.
 */
class VoiceCaptureControlsTest {
    private val sentenceRow = listOf(
        VoiceCaptureControls.LANGUAGE,
        VoiceCaptureControls.PLAY,
        VoiceCaptureControls.TRANSCRIBE,
        VoiceCaptureControls.EDIT,
        VoiceCaptureControls.COPY,
        VoiceCaptureControls.SHARE,
        VoiceCaptureControls.DELETE,
        VoiceCaptureControls.SAVE_OGG
    )

    /** A long recording: no text, so no Transcribe/Edit/Copy/Share — and Cut into sentences instead. */
    private val longRow = listOf(
        VoiceCaptureControls.LANGUAGE,
        VoiceCaptureControls.PLAY,
        VoiceCaptureControls.DELETE,
        VoiceCaptureControls.SAVE_OGG,
        VoiceCaptureControls.SPLIT
    )

    @Test
    fun `Save as OGG comes after Delete by default`() {
        val order = VoiceCaptureControls.DEFAULT
        assertTrue(
            "Save as OGG must sit after Delete, got: $order",
            order.indexOf(VoiceCaptureControls.SAVE_OGG) > order.indexOf(VoiceCaptureControls.DELETE)
        )
    }

    @Test
    fun `nothing stored means the default order`() {
        assertEquals(VoiceCaptureControls.DEFAULT, VoiceCaptureControls.parse(null))
        assertEquals(VoiceCaptureControls.DEFAULT, VoiceCaptureControls.parse("   "))
    }

    @Test
    fun `a stored order survives a round trip`() {
        val wanted = listOf(
            VoiceCaptureControls.DELETE,
            VoiceCaptureControls.SAVE_OGG,
            VoiceCaptureControls.LANGUAGE,
            VoiceCaptureControls.PLAY,
            VoiceCaptureControls.TRANSCRIBE,
            VoiceCaptureControls.EDIT,
            VoiceCaptureControls.COPY,
            VoiceCaptureControls.SHARE,
            VoiceCaptureControls.SPLIT
        )
        assertEquals(wanted, VoiceCaptureControls.parse(VoiceCaptureControls.encode(wanted)))
    }

    @Test
    fun `a control this build no longer has is dropped, and one it has gained is kept`() {
        // What an older build stored: no Cut into sentences (it had no long recordings), plus an id that
        // means nothing here. A pill must never vanish because the stored order predates it.
        val stored = "delete,ogg,language,play,transcribe,edit,copy,share,semaphore"
        val order = VoiceCaptureControls.parse(stored)

        assertTrue("an unknown id must not be kept, got: $order", "semaphore" !in order)
        assertEquals("every control must be present exactly once", VoiceCaptureControls.DEFAULT.size, order.size)
        assertEquals("the stored order's own sequence stands", VoiceCaptureControls.DELETE, order.first())
        assertEquals("the new control goes to the end", VoiceCaptureControls.SPLIT, order.last())
    }

    @Test
    fun `a pill dragged to the front of a long row leads under every line`() {
        // Delete lifted past Play and the language pill, on a row that shows four of the nine.
        val dropped = listOf(
            VoiceCaptureControls.DELETE,
            VoiceCaptureControls.LANGUAGE,
            VoiceCaptureControls.PLAY,
            VoiceCaptureControls.SAVE_OGG,
            VoiceCaptureControls.SPLIT
        )
        val moved = VoiceCaptureControls.moveWithin(
            VoiceCaptureControls.DEFAULT,
            VoiceCaptureControls.DELETE,
            dropped
        )

        assertEquals("Delete must now lead, got: $moved", VoiceCaptureControls.DELETE, moved.first())
        // And the pills that row never showed are exactly where they were: only Delete moved.
        assertEquals(
            listOf(
                VoiceCaptureControls.DELETE,
                VoiceCaptureControls.LANGUAGE,
                VoiceCaptureControls.PLAY,
                VoiceCaptureControls.TRANSCRIBE,
                VoiceCaptureControls.EDIT,
                VoiceCaptureControls.COPY,
                VoiceCaptureControls.SHARE,
                VoiceCaptureControls.SAVE_OGG,
                VoiceCaptureControls.SPLIT
            ),
            moved
        )
    }

    @Test
    fun `dropped last among the pills a row shows is not the end of the whole order`() {
        // Play dragged to the end of a LONG row. "Last here" means after Cut into sentences — it must not
        // jump past Transcribe/Edit/Copy/Share, which this row has no pills for at all.
        val dropped = listOf(
            VoiceCaptureControls.LANGUAGE,
            VoiceCaptureControls.DELETE,
            VoiceCaptureControls.SAVE_OGG,
            VoiceCaptureControls.SPLIT,
            VoiceCaptureControls.PLAY
        )
        val moved = VoiceCaptureControls.moveWithin(
            VoiceCaptureControls.DEFAULT,
            VoiceCaptureControls.PLAY,
            dropped
        )

        assertEquals("Play ends up last, got: $moved", VoiceCaptureControls.PLAY, moved.last())
        assertEquals(
            "the sentence-only pills keep their places, got: $moved",
            listOf(
                VoiceCaptureControls.LANGUAGE,
                VoiceCaptureControls.TRANSCRIBE,
                VoiceCaptureControls.EDIT,
                VoiceCaptureControls.COPY,
                VoiceCaptureControls.SHARE,
                VoiceCaptureControls.DELETE,
                VoiceCaptureControls.SAVE_OGG,
                VoiceCaptureControls.SPLIT,
                VoiceCaptureControls.PLAY
            ),
            moved
        )
    }

    @Test
    fun `a drag that changed nothing changes nothing`() {
        assertEquals(
            VoiceCaptureControls.DEFAULT,
            VoiceCaptureControls.moveWithin(
                VoiceCaptureControls.DEFAULT,
                VoiceCaptureControls.COPY,
                sentenceRow
            )
        )
    }

    @Test
    fun `a pill moved on a sentence row keeps the order legible on a long one`() {
        // Save as OGG dragged in front of Delete on a sentence row; the long row shows both, so it must
        // read the same way there.
        val dropped = listOf(
            VoiceCaptureControls.LANGUAGE,
            VoiceCaptureControls.PLAY,
            VoiceCaptureControls.TRANSCRIBE,
            VoiceCaptureControls.EDIT,
            VoiceCaptureControls.COPY,
            VoiceCaptureControls.SHARE,
            VoiceCaptureControls.SAVE_OGG,
            VoiceCaptureControls.DELETE
        )
        val moved = VoiceCaptureControls.moveWithin(
            VoiceCaptureControls.DEFAULT,
            VoiceCaptureControls.SAVE_OGG,
            dropped
        )

        val onLong = moved.filter { it in longRow }
        assertEquals(
            listOf(
                VoiceCaptureControls.LANGUAGE,
                VoiceCaptureControls.PLAY,
                VoiceCaptureControls.SAVE_OGG,
                VoiceCaptureControls.DELETE,
                VoiceCaptureControls.SPLIT
            ),
            onLong
        )
    }
}
