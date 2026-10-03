package com.urik.keyboard.service.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceModelStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir: File get() = VoiceModelStore.modelDir(context)!!

    @After
    fun cleanup() {
        VoiceModelStore.REQUIRED_FILES.forEach { File(dir, it).apply { setReadable(true) }.delete() }
    }

    private fun writeAll() = VoiceModelStore.REQUIRED_FILES.forEach { File(dir, it).writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `all six readable files are installed`() {
        writeAll()
        assertEquals(VoiceModelStore.Status.INSTALLED, VoiceModelStore.status(context))
    }

    @Test
    fun `a missing or empty file is missing`() {
        writeAll()
        File(dir, VoiceModelStore.REQUIRED_FILES.first()).writeBytes(ByteArray(0))
        assertEquals(VoiceModelStore.Status.MISSING, VoiceModelStore.status(context))
    }

    @Test
    fun `files the keyboard may not read are reported as unreadable, not installed`() {
        writeAll()
        File(dir, "Whisper_decoder.onnx").setReadable(false, false)
        assertEquals(VoiceModelStore.Status.UNREADABLE, VoiceModelStore.status(context))
    }
}
