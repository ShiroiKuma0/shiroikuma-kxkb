package com.urik.keyboard.data

import android.content.Context
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.isUserUnlocked
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The walk-capture inbox: sentences spoken outside with the screen off, offered to us by 自由作業盤 over the
 * automation door, waiting at home to be decoded, reviewed and accepted.
 *
 * `filesDir/voice_capture/`: one `index.json` (the row per sentence), one `<uuid>.wav` per clip, and
 * `seen.json` — every uuid we ever took in, kept after the sentence itself is gone, so a clip re-offered
 * because the other side's delete did not land is answered "already here" instead of imported twice.
 *
 * The same shape as `voice_pending/`, deliberately: files plus one small JSON index, nothing in Room. A
 * queue of audio is what this is, and the Room corpus gets its row only when a sentence is accepted — so
 * the corpus keeps exactly one truth, written by the same calls the dictation review uses.
 *
 * Credential-protected storage: nothing is read or written before the first unlock.
 */
class VoiceCaptureStore(private val context: Context) {
    /**
     * One captured sentence. [recognized] is the decode (spoken punctuation already applied), [text] what
     * it reads as now — null until it has been decoded.
     *
     * [rawWords] / [rawConfidences] are the engine's own uncleaned decode and its per-word confidences,
     * kept verbatim rather than the derived per-word list: a `VoiceTranscript` rebuilt from them re-aligns
     * the confidences onto whatever the sentence reads as now, so the marks survive a correction and a
     * process death exactly as they do during dictation.
     */
    class Entry(
        val uuid: String,
        val clip: String,
        val capturedAt: Long,
        val durationMs: Long,
        val language: String,
        val byteLength: Long,
        val sha256: String,
        val recognized: String? = null,
        val text: String? = null,
        val rawWords: List<String>? = null,
        val rawConfidences: List<Float>? = null,
        /** Decode attempts that came back with nothing — a clip of silence says so instead of staying mute. */
        val attempts: Int = 0
    ) {
        val transcribed: Boolean get() = text != null
    }

    /** Why an offered clip was refused — the closed set the door reports back per uuid. */
    enum class Reject(val tag: String) {
        FORMAT("format"),
        SHA256("sha256"),
        LENGTH("length"),
        NO_FD("nofd"),
        IO("io")
    }

    sealed interface Import {
        /** Written and fsynced under our own storage — the caller may delete its copy. */
        data object Stored : Import

        /** We already hold this uuid (or held it once): also the caller's cue to delete. */
        data object Duplicate : Import

        data class Refused(val reason: Reject) : Import
    }

    private val dir: File get() = File(context.filesDir, DIR)

    val usable: Boolean get() = context.isUserUnlocked

    fun budgetBytes(): Long = BUDGET_BYTES

    fun usedBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun freeBytes(): Long = (BUDGET_BYTES - usedBytes()).coerceAtLeast(0L)

    fun clipFile(entry: Entry): File? = File(dir, entry.clip).takeIf { it.isFile }

    /** Every captured sentence, oldest first — the order they were spoken in, which is how they read. */
    fun entries(): List<Entry> = readIndex().sortedBy { it.capturedAt }

    fun contains(uuid: String): Boolean = readIndex().any { it.uuid == uuid } || uuid in readSeen()

    /**
     * Take in one offered clip: stream it to disk while hashing, verify it is the agreed format and the
     * agreed bytes, and only then publish it in the index. A clip that fails anything leaves nothing
     * behind — never a half-written `.wav` that would read as a captured sentence.
     */
    fun import(
        uuid: String,
        capturedAt: Long,
        durationMs: Long,
        language: String,
        declaredBytes: Long,
        sha256: String,
        source: InputStream?
    ): Import {
        if (!usable) return Import.Refused(Reject.IO)
        if (!isSafeUuid(uuid)) return Import.Refused(Reject.FORMAT)
        if (contains(uuid)) return Import.Duplicate
        if (source == null) return Import.Refused(Reject.NO_FD)
        if (declaredBytes !in MIN_FILE_BYTES..MAX_FILE_BYTES) return Import.Refused(Reject.LENGTH)
        dir.mkdirs()
        val part = File(dir, "$uuid$PART_SUFFIX")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    written += read
                    if (written > MAX_FILE_BYTES) break
                }
                out.flush()
                out.fd.sync()
            }
            val reason = verify(part, written, declaredBytes, digest.digest(), sha256)
            if (reason != null) {
                part.delete()
                return Import.Refused(reason)
            }
            val clip = File(dir, "$uuid.wav")
            if (!part.renameTo(clip)) {
                part.delete()
                return Import.Refused(Reject.IO)
            }
            val entries = readIndex().toMutableList()
            entries.add(
                Entry(
                    uuid = uuid,
                    clip = clip.name,
                    capturedAt = if (capturedAt > 0) capturedAt else System.currentTimeMillis(),
                    // Our own count from the bytes is the duration; the offered field is a cross-check only.
                    durationMs = wavDurationMs(clip) ?: durationMs,
                    language = language.ifBlank { "en" },
                    byteLength = clip.length(),
                    sha256 = sha256.lowercase()
                )
            )
            writeIndex(entries)
            remember(uuid)
            Import.Stored
        } catch (e: Exception) {
            part.delete()
            log("import", e)
            Import.Refused(Reject.IO)
        }
    }

    /** Store a fresh decode for [uuid] (its text starts out as what was recognised). */
    fun saveTranscript(
        uuid: String,
        recognized: String,
        text: String,
        rawWords: List<String>?,
        rawConfidences: List<Float>?
    ) {
        update(uuid) { e ->
            Entry(
                e.uuid, e.clip, e.capturedAt, e.durationMs, e.language, e.byteLength, e.sha256,
                recognized, text, rawWords, rawConfidences, e.attempts
            )
        }
    }

    /** A decode produced nothing (a clip of silence, or an engine that gave up): count the attempt. */
    fun countFailedAttempt(uuid: String) {
        update(uuid) { e ->
            Entry(
                e.uuid, e.clip, e.capturedAt, e.durationMs, e.language, e.byteLength, e.sha256,
                e.recognized, e.text, e.rawWords, e.rawConfidences, e.attempts + 1
            )
        }
    }

    /** Store the sentence as it reads after a correction (the recognition and its confidences stand). */
    fun saveText(uuid: String, text: String) {
        update(uuid) { e ->
            Entry(
                e.uuid, e.clip, e.capturedAt, e.durationMs, e.language, e.byteLength, e.sha256,
                e.recognized, text, e.rawWords, e.rawConfidences, e.attempts
            )
        }
    }

    /**
     * Drop a sentence from the inbox. [deleteClip] false hands the audio on instead (an accepted sentence
     * whose clip moves into the voice corpus) — the uuid is remembered either way.
     */
    fun remove(uuid: String, deleteClip: Boolean = true) {
        if (!usable) return
        val entries = readIndex()
        val entry = entries.firstOrNull { it.uuid == uuid } ?: return
        if (deleteClip) File(dir, entry.clip).delete()
        writeIndex(entries.filterNot { it.uuid == uuid })
        remember(uuid)
    }

    /**
     * Delete clips with no index row — what a failed corpus write leaves behind when a sentence was kept,
     * and any `.part` a killed import left. An accepted clip is MOVED out within milliseconds, so a file
     * still here after the grace period is an orphan rather than a sentence mid-acceptance.
     */
    fun sweepOrphans(graceMs: Long = ORPHAN_GRACE_MS) {
        if (!usable) return
        val known = readIndex().map { it.clip }.toHashSet()
        val cutoff = System.currentTimeMillis() - graceMs
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || file.name == INDEX || file.name == SEEN) return@forEach
            if (file.name in known || file.lastModified() > cutoff) return@forEach
            file.delete()
        }
    }

    /**
     * The clip as the recognizer wants it: 16 kHz mono float, peak-normalised — the same conversion
     * [com.urik.keyboard.service.voice.VoiceRecorder] applies to the microphone, so a captured sentence
     * reaches the engine exactly as a dictated one does (the confidence threshold is calibrated on it).
     */
    fun readSamples(entry: Entry): FloatArray? {
        val clip = clipFile(entry) ?: return null
        return try {
            val bytes = clip.readBytes()
            val data = dataRange(bytes) ?: return null
            val count = data.second / 2
            if (count <= 0) return null
            val buffer = ByteBuffer.wrap(bytes, data.first, count * 2).order(ByteOrder.LITTLE_ENDIAN)
            val samples = FloatArray(count)
            var maxAbs = 0f
            for (i in 0 until count) {
                samples[i] = buffer.short / 32768.0f
                val abs = kotlin.math.abs(samples[i])
                if (abs > maxAbs) maxAbs = abs
            }
            if (maxAbs > 0f) for (i in samples.indices) samples[i] /= maxAbs
            samples
        } catch (e: Exception) {
            log("readSamples", e)
            null
        }
    }

    // ---- validation ------------------------------------------------------------------------------------

    private fun verify(
        file: File,
        written: Long,
        declaredBytes: Long,
        digest: ByteArray,
        declaredSha: String
    ): Reject? {
        if (written != declaredBytes) return Reject.LENGTH
        if (declaredSha.isNotBlank() && digest.toHex() != declaredSha.lowercase()) return Reject.SHA256
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            log("verify", e)
            return Reject.IO
        }
        if (!isAgreedWav(bytes)) return Reject.FORMAT
        val duration = dataRange(bytes)?.second?.let { it * 1000L / (SAMPLE_RATE * 2) } ?: return Reject.FORMAT
        if (duration < MIN_MS || duration > MAX_MS) return Reject.LENGTH
        return null
    }

    /** 16 kHz mono PCM16 — byte-for-byte the format the voice corpus itself writes. */
    private fun isAgreedWav(bytes: ByteArray): Boolean {
        if (bytes.size < 44) return false
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return false
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return false
        val fmt = chunkRange(bytes, "fmt ") ?: return false
        if (fmt.second < 16) return false
        val b = ByteBuffer.wrap(bytes, fmt.first, 16).order(ByteOrder.LITTLE_ENDIAN)
        val audioFormat = b.short.toInt()
        val channels = b.short.toInt()
        val rate = b.int
        b.int // byte rate — derived, not trusted
        b.short // block align
        val bits = b.short.toInt()
        return audioFormat == 1 && channels == 1 && rate == SAMPLE_RATE && bits == 16
    }

    private fun dataRange(bytes: ByteArray): Pair<Int, Int>? = chunkRange(bytes, "data")

    /** (payload offset, payload size) of the first [id] chunk, walking the RIFF chunk list. */
    private fun chunkRange(bytes: ByteArray, id: String): Pair<Int, Int>? {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (size < 0) return null
            val payload = offset + 8
            if (name == id) return payload to minOf(size, bytes.size - payload).coerceAtLeast(0)
            offset = payload + size + (size and 1)
        }
        return null
    }

    private fun wavDurationMs(file: File): Long? = try {
        dataRange(file.readBytes())?.second?.let { it * 1000L / (SAMPLE_RATE * 2) }
    } catch (e: Exception) {
        log("duration", e)
        null
    }

    // ---- the index and the uuid memory -----------------------------------------------------------------

    private fun update(uuid: String, change: (Entry) -> Entry) {
        if (!usable) return
        val entries = readIndex()
        if (entries.none { it.uuid == uuid }) return
        writeIndex(entries.map { if (it.uuid == uuid) change(it) else it })
    }

    private fun readIndex(): List<Entry> {
        if (!usable) return emptyList()
        val file = File(dir, INDEX)
        if (!file.isFile) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("entries") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i -> entryFrom(arr.optJSONObject(i) ?: return@mapNotNull null) }
        } catch (e: Exception) {
            log("readIndex", e)
            emptyList()
        }
    }

    private fun entryFrom(o: JSONObject): Entry? {
        val uuid = o.optString("uuid").takeIf { isSafeUuid(it) } ?: return null
        val rawWords = o.optJSONArray("rawWords")?.let { arr ->
            (0 until arr.length()).map { i -> arr.optString(i) }
        }
        val rawConfidences = o.optJSONArray("rawConfidences")?.let { arr ->
            (0 until arr.length()).map { i -> arr.optDouble(i).toFloat() }
        }
        return Entry(
            uuid = uuid,
            clip = o.optString("clip", "$uuid.wav"),
            capturedAt = o.optLong("capturedAt"),
            durationMs = o.optLong("durationMs"),
            language = o.optString("language", "en"),
            byteLength = o.optLong("byteLength"),
            sha256 = o.optString("sha256"),
            recognized = if (o.isNull("recognized")) null else o.optString("recognized"),
            text = if (o.isNull("text")) null else o.optString("text"),
            rawWords = rawWords,
            rawConfidences = rawConfidences,
            attempts = o.optInt("attempts")
        )
    }

    private fun writeIndex(entries: List<Entry>) {
        if (!usable) return
        try {
            dir.mkdirs()
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject()
                        .put("uuid", e.uuid)
                        .put("clip", e.clip)
                        .put("capturedAt", e.capturedAt)
                        .put("durationMs", e.durationMs)
                        .put("language", e.language)
                        .put("byteLength", e.byteLength)
                        .put("sha256", e.sha256)
                        .put("recognized", e.recognized ?: JSONObject.NULL)
                        .put("text", e.text ?: JSONObject.NULL)
                        .put("attempts", e.attempts)
                        .put("rawWords", e.rawWords?.let { JSONArray(it) } ?: JSONObject.NULL)
                        .put(
                            "rawConfidences",
                            e.rawConfidences?.let { list -> JSONArray().apply { list.forEach { put(it.toDouble()) } } }
                                ?: JSONObject.NULL
                        )
                )
            }
            writeAtomically(File(dir, INDEX), JSONObject().put("entries", arr).toString())
        } catch (e: Exception) {
            log("writeIndex", e)
        }
    }

    private fun readSeen(): Set<String> {
        if (!usable) return emptySet()
        val file = File(dir, SEEN)
        if (!file.isFile) return emptySet()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }.toSet()
        } catch (e: Exception) {
            log("readSeen", e)
            emptySet()
        }
    }

    /** Remember a uuid for good, so a re-offer is answered rather than re-imported. */
    private fun remember(uuid: String) {
        try {
            val seen = readSeen().toMutableList()
            if (uuid in seen) return
            seen.add(uuid)
            // A walk is tens of sentences; the cap is only there so the file cannot grow without end.
            val kept = if (seen.size > MAX_SEEN) seen.takeLast(MAX_SEEN) else seen
            dir.mkdirs()
            writeAtomically(File(dir, SEEN), JSONArray(kept).toString())
        } catch (e: Exception) {
            log("remember", e)
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        FileOutputStream(part).use { out ->
            out.write(content.toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!part.renameTo(target)) {
            target.delete()
            part.renameTo(target)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun log(operation: String, e: Exception) {
        ErrorLogger.logException(
            component = "VoiceCaptureStore",
            severity = ErrorLogger.Severity.LOW,
            exception = e,
            context = mapOf("operation" to operation)
        )
    }

    companion object {
        const val DIR = "voice_capture"
        private const val INDEX = "index.json"
        private const val SEEN = "seen.json"
        private const val PART_SUFFIX = ".part"
        private const val SAMPLE_RATE = 16000
        private const val MAX_SEEN = 20_000

        /** Whisper's window is 30 s; 自由作業盤 splits a sentence at 28 s, so longer than 30 s loses its tail. */
        private const val MAX_MS = 30_000L

        /** Shorter than this is a stray key press, not a sentence. */
        private const val MIN_MS = 200L

        private const val MIN_FILE_BYTES = 44L + 2 * SAMPLE_RATE * MIN_MS / 1000
        private const val MAX_FILE_BYTES = 1024L + 2 * SAMPLE_RATE * MAX_MS / 1000

        /** How long a clip with no index row is left alone before it counts as an orphan. */
        private const val ORPHAN_GRACE_MS = 60 * 60 * 1000L

        /** How much captured audio may wait at once (half a gigabyte is many walks). */
        private const val BUDGET_BYTES = 512L * 1024 * 1024

        /** A uuid becomes a file name, so it may only be a plain one. */
        fun isSafeUuid(uuid: String): Boolean =
            uuid.isNotEmpty() && uuid.length <= 64 && uuid.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
