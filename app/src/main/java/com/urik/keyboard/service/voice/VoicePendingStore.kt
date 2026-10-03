package com.urik.keyboard.service.voice

import android.content.Context
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.isUserUnlocked
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * The dictation under review, kept on disk until ✓ / the next mic press accepts it (or ✕ drops it), so a
 * keyboard process killed while you are in another app does not lose it. `filesDir/voice_pending/`: one
 * `ledger.json` (the field, every chunk and word with its state) plus one 16-bit PCM file per chunk.
 *
 * Credential-protected storage: nothing is read or written before the first unlock.
 */
class VoicePendingStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, DIR)

    fun save(field: String?, entries: List<VoiceSessionLedger.Entry>) {
        if (!context.isUserUnlocked) return
        try {
            if (entries.isEmpty()) {
                clear()
                return
            }
            dir.mkdirs()
            val keep = mutableSetOf(LEDGER)
            val json = JSONObject().put("field", field ?: JSONObject.NULL)
            val arr = JSONArray()
            for (e in entries) {
                val audio = e.samples?.takeIf { it.isNotEmpty() }?.let { "${e.utteranceId}.pcm" }
                if (audio != null) {
                    keep.add(audio)
                    val file = File(dir, audio)
                    if (!file.exists()) writePcm(file, e.samples)
                }
                arr.put(
                    JSONObject()
                        .put("id", e.utteranceId)
                        .put("language", e.language)
                        .put("recognizedText", e.recognizedText)
                        .put("audio", audio ?: JSONObject.NULL)
                        .put("words", JSONArray().apply { e.words.forEach { put(wordJson(it)) } })
                        .put(
                            "typed",
                            JSONArray().apply {
                                e.typedCorrections.forEach {
                                    put(
                                        JSONObject().put("from", it.from).put("to", it.to)
                                            .put("recognized", it.recognized).put("corrected", it.corrected)
                                    )
                                }
                            }
                        )
                )
            }
            json.put("entries", arr)
            val tmp = File(dir, "$LEDGER.part")
            tmp.writeText(json.toString())
            tmp.renameTo(File(dir, LEDGER))
            dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        } catch (e: Exception) {
            log("save", e)
        }
    }

    /** The saved review: its field and its chunks; null when there is none. */
    fun load(): Pair<String?, List<VoiceSessionLedger.Entry>>? {
        if (!context.isUserUnlocked) return null
        return try {
            val file = File(dir, LEDGER)
            if (!file.isFile) return null
            val json = JSONObject(file.readText())
            val field = if (json.isNull("field")) null else json.optString("field")
            val arr = json.optJSONArray("entries") ?: return null
            val entries = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val audio = if (o.isNull("audio")) null else o.optString("audio")
                val samples = audio?.let { File(dir, it) }?.takeIf { it.isFile }?.let(::readPcm)
                val wordsJson = o.getJSONArray("words")
                val words = (0 until wordsJson.length()).map { wordFrom(wordsJson.getJSONObject(it)) }
                VoiceSessionLedger.Entry(o.getString("id"), o.getString("language"), o.optString("recognizedText"), words, samples)
                    .apply {
                        val typed = o.optJSONArray("typed") ?: JSONArray()
                        typedCorrections = (0 until typed.length()).map {
                            val t = typed.getJSONObject(it)
                            VoiceSessionLedger.TypedCorrection(
                                t.getInt("from"),
                                t.getInt("to"),
                                t.getString("recognized"),
                                t.getString("corrected")
                            )
                        }
                    }
            }
            if (entries.isEmpty()) null else field to entries
        } catch (e: Exception) {
            log("load", e)
            null
        }
    }

    fun clear() {
        if (!context.isUserUnlocked) return
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            log("clear", e)
        }
    }

    private fun wordJson(w: VoiceSessionLedger.Word) = JSONObject()
        .put("index", w.index)
        .put("recognized", w.recognized)
        .put("confidence", w.confidence?.toDouble() ?: JSONObject.NULL)
        .put("reason", w.judgement.reason?.name ?: JSONObject.NULL)
        .put("alternative", w.judgement.alternative ?: JSONObject.NULL)
        .put("current", w.current)
        .put("start", w.start)
        .put("intact", w.intact)
        .put("corrected", w.corrected)
        .put("autoReplaced", w.autoReplaced)
        .put("merged", w.merged)

    private fun wordFrom(o: JSONObject): VoiceSessionLedger.Word {
        val reason = if (o.isNull("reason")) null else runCatching { SuspectReason.valueOf(o.getString("reason")) }.getOrNull()
        val alternative = if (o.isNull("alternative")) null else o.optString("alternative")
        return VoiceSessionLedger.Word(
            index = o.getInt("index"),
            recognized = o.getString("recognized"),
            confidence = if (o.isNull("confidence")) null else o.optDouble("confidence").toFloat(),
            judgement = VoiceJudgement(reason, alternative),
            current = o.getString("current"),
            start = o.getInt("start")
        ).apply {
            intact = o.optBoolean("intact", true)
            corrected = o.optBoolean("corrected")
            autoReplaced = o.optBoolean("autoReplaced")
            merged = o.optBoolean("merged")
        }
    }

    private fun writePcm(file: File, samples: FloatArray) {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        file.writeBytes(buffer.array())
    }

    private fun readPcm(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(buffer.remaining() / 2) { buffer.short / Short.MAX_VALUE.toFloat() }
    }

    private fun log(operation: String, e: Exception) {
        ErrorLogger.logException(
            component = "VoicePendingStore",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to operation)
        )
    }

    companion object {
        const val DIR = "voice_pending"
        private const val LEDGER = "ledger.json"
    }
}
