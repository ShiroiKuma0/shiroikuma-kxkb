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

    fun modelDir(context: Context): File? = context.getExternalFilesDir(null)

    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context) ?: return false
        return REQUIRED_FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }
}
