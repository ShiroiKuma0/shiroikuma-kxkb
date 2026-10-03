package com.urik.keyboard.service.voice

import android.content.Context
import java.io.File

/**
 * Location and presence check for the Whisper ONNX model set. The engine compiled from the
 * whisperIMEplus submodule ([com.whisperonnx.voice_translation.neural_networks.voice.Recognizer])
 * hard-codes its model paths to the root of the app's external files dir, so the six files must
 * live exactly there. The set is the RTranslator 6-file export (any size variant with these names
 * works — small int8 is the only published one today).
 */
object VoiceModelStore {
    val REQUIRED_FILES = listOf(
        "Whisper_initializer.onnx",
        "Whisper_encoder.onnx",
        "Whisper_decoder.onnx",
        "Whisper_cache_initializer.onnx",
        "Whisper_cache_initializer_batch.onnx",
        "Whisper_detokenizer.onnx"
    )

    enum class Status {
        INSTALLED,
        MISSING,

        /**
         * All six files are there but the keyboard may not open them: they were copied in by someone else
         * (adb / a restore tool runs as the shell user and leaves `rw-rw----` files owned by shell), and the
         * engine then fails to load the instant the mic is pressed. Importing the zip again fixes it.
         */
        UNREADABLE
    }

    fun modelDir(context: Context): File? = context.getExternalFilesDir(null)

    fun status(context: Context): Status {
        val dir = modelDir(context) ?: return Status.MISSING
        val files = REQUIRED_FILES.map { File(dir, it) }
        if (!files.all { it.isFile && it.length() > 0 }) return Status.MISSING
        return if (files.all { it.canRead() }) Status.INSTALLED else Status.UNREADABLE
    }

    fun isInstalled(context: Context): Boolean = status(context) == Status.INSTALLED
}
