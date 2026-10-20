package com.urik.keyboard.service.voice

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming a kept recording. The encoding itself is `MediaCodec` + `MediaMuxer` and belongs to the phone, but
 * where the file lands is a rule, and it is the rule that cannot afford to be wrong: the directory is
 * 白い熊's own, holding whatever else they keep there, and a walk recording cannot be made a second
 * time — so a name that is taken is never written over, and nothing but naming happens here.
 */
class VoiceOggExportTest {
    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

    private fun dir(): File {
        val dir = File.createTempFile("recordings", "").let { probe ->
            probe.delete()
            File(probe.absolutePath + "-dir")
        }
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    @Test
    fun `a recording is named by the moment it was spoken`() {
        val dir = dir()
        val spokenAt = 1_760_000_000_000L

        val target = VoiceOggExport.targetIn(dir, spokenAt)

        assertEquals(dir, target.parentFile)
        assertEquals(stamp.format(Date(spokenAt)) + ".ogg", target.name)
    }

    @Test
    fun `naming the target writes nothing at all`() {
        val dir = dir()

        val target = VoiceOggExport.targetIn(dir, 1_760_000_000_000L)

        assertFalse("the file is named, not created", target.exists())
        assertEquals("and the directory is left as it was", 0, dir.listFiles()?.size ?: -1)
    }

    @Test
    fun `a name already taken moves aside instead of overwriting`() {
        val dir = dir()
        val spokenAt = 1_760_000_000_000L
        val first = VoiceOggExport.targetIn(dir, spokenAt)
        first.writeText("the recording that is already there")

        val second = VoiceOggExport.targetIn(dir, spokenAt)

        assertNotEquals(first.name, second.name)
        assertTrue("got ${second.name}", second.name.endsWith("_2.ogg"))
        assertEquals("the file that was there is untouched", "the recording that is already there", first.readText())
    }

    @Test
    fun `and keeps moving aside for every one after it`() {
        val dir = dir()
        // Two presses CAN share a second — the stamp is to the second — and 白い熊 may well have put a file
        // of that name there themselves.
        val spokenAt = 1_760_000_000_000L
        val names = mutableListOf<String>()
        repeat(3) {
            val target = VoiceOggExport.targetIn(dir, spokenAt)
            target.writeText("x")
            names.add(target.name)
        }

        assertEquals(3, names.toSet().size)
        assertTrue("got $names", names[1].endsWith("_2.ogg") && names[2].endsWith("_3.ogg"))
    }

    @Test
    fun `two recordings a second apart are two different files`() {
        val dir = dir()
        val first = VoiceOggExport.targetIn(dir, 1_760_000_000_000L)
        first.writeText("x")

        val second = VoiceOggExport.targetIn(dir, 1_760_000_001_000L)

        assertNotEquals(first.name, second.name)
        assertFalse("the later one needs no suffix", second.name.contains("_2"))
    }
}
