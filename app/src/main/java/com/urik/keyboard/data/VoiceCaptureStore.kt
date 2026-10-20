package com.urik.keyboard.data

import android.content.Context
import com.urik.keyboard.service.voice.VoiceLanguages
import com.urik.keyboard.service.voice.VoiceSettledWords
import com.urik.keyboard.utils.ErrorLogger
import com.urik.keyboard.utils.isUserUnlocked
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
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
    data class Entry(
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
        val attempts: Int = 0,
        /**
         * [text] is what YOU settled it reads as — a word corrected in the box, or the whole sentence
         * typed over — and not merely what the decode produced. Nothing may rewrite such a sentence
         * afterwards: your corrections are evidence for the next DECODE, never a licence to edit a
         * sentence you already approved (see `VoiceJudging.judge`'s `replace`).
         */
        val edited: Boolean = false,
        /**
         * What you have settled about the individual words of [text] — the word an earlier correction
         * replaced, the word you corrected here. Derived marks (the recogniser's doubt, a word the
         * dictionary does not know) are not here: those are worked out again from [rawConfidences] and
         * the dictionary every time the row is built. See [VoiceSettledWords].
         */
        val settled: List<VoiceSettledWords.Settled>? = null,
        /**
         * A sentence to decode, or a long recording to keep or cut up (contract §9). The press that
         * starts a walk recording is also 白い熊's "record this for later", and what a long clip is FOR is
         * decided here in the review — never by decoding it, which Whisper's 30 s window cannot do.
         */
        val kind: Kind = Kind.SENTENCE,
        /** 自由作業盤 recovered this one from a kill mid-recording; it is otherwise ordinary. */
        val salvaged: Boolean = false,
        /** The long recording this sentence was cut out of, for as long as that recording is here. */
        val parent: String? = null,
        /** Where this long recording was written out as an OGG, once it has been. */
        val ogg: String? = null,
        /** How many sentences this long recording was cut into, 0 until it has been. */
        val pieces: Int = 0
    ) {
        val transcribed: Boolean get() = text != null

        val isLong: Boolean get() = kind == Kind.LONG
    }

    /** What an offered clip is. Absent on the wire means a sentence — the only kind there was before §9. */
    enum class Kind(val tag: String) {
        SENTENCE("sentence"),
        LONG("long");

        companion object {
            fun of(tag: String?): Kind = entries.firstOrNull { it.tag == tag } ?: SENTENCE
        }
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
        kind: Kind = Kind.SENTENCE,
        salvaged: Boolean = false,
        source: InputStream?
    ): Import {
        if (!usable) return Import.Refused(Reject.IO)
        if (!isSafeUuid(uuid)) return Import.Refused(Reject.FORMAT)
        if (contains(uuid)) return Import.Duplicate
        if (source == null) return Import.Refused(Reject.NO_FD)
        if (declaredBytes !in MIN_FILE_BYTES..maxBytesOf(kind)) return Import.Refused(Reject.LENGTH)
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
                    if (written > maxBytesOf(kind)) break
                }
                out.flush()
                out.fd.sync()
            }
            val reason = verify(part, written, declaredBytes, digest.digest(), sha256, kind)
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
                    // en or cs and nothing else: this field decides the decode AND, through the
                    // contract, 言語島's translation direction and voice (§7).
                    language = VoiceLanguages.captureOrDefault(language),
                    byteLength = clip.length(),
                    sha256 = sha256.lowercase(),
                    kind = kind,
                    salvaged = salvaged
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

    /**
     * Store a fresh decode for [uuid] (its text starts out as what was recognised). [edited] is for the one
     * caller that writes a "decode" by hand — the sentence typed onto a clip the engine heard nothing in —
     * which is as settled as any correction and must not be rewritten either.
     */
    fun saveTranscript(
        uuid: String,
        recognized: String,
        text: String,
        rawWords: List<String>?,
        rawConfidences: List<Float>?,
        edited: Boolean = false
    ) {
        update(uuid) { e ->
            e.copy(
                recognized = recognized,
                text = text,
                rawWords = rawWords,
                rawConfidences = rawConfidences,
                edited = edited,
                settled = null
            )
        }
    }

    /** A decode produced nothing (a clip of silence, or an engine that gave up): count the attempt. */
    fun countFailedAttempt(uuid: String) {
        update(uuid) { e -> e.copy(attempts = e.attempts + 1) }
    }

    /**
     * Store the sentence as it reads after a correction (the recognition and its confidences stand), and
     * mark it [Entry.edited]: from here on it is what YOU say it says.
     *
     * [settled] goes with it in the same write, because it describes THIS text: the words of it you had
     * corrected, and the words an earlier correction replaced. The caller carries them across the edit
     * ([VoiceSettledWords.carry]) so a word it did not touch keeps its mark.
     */
    fun saveText(uuid: String, text: String, settled: List<VoiceSettledWords.Settled> = emptyList()) {
        update(uuid) { e -> e.copy(text = text, edited = true, settled = settled.ifEmpty { null }) }
    }

    /**
     * Set the language this sentence is to be decoded as (contract §7 — `en` or `cs`, normalised here, so
     * nothing but the agreed pair can ever be stored or sent on). False when nothing changed.
     */
    fun setLanguage(uuid: String, language: String): Boolean {
        if (!usable) return false
        val wanted = VoiceLanguages.captureOrDefault(language)
        val entries = readIndex()
        if (entries.none { it.uuid == uuid && it.language != wanted }) return false
        writeIndex(entries.map { if (it.uuid == uuid) relanguaged(it, wanted) else it })
        return true
    }

    /**
     * The same for the whole inbox — a walk spoken in one language is marked in one gesture, and in one
     * index write rather than one per sentence. Returns how many sentences changed.
     */
    fun setLanguageForAll(language: String): Int {
        if (!usable) return 0
        val wanted = VoiceLanguages.captureOrDefault(language)
        val entries = readIndex()
        val changing = entries.count { it.language != wanted }
        if (changing == 0) return 0
        writeIndex(entries.map { relanguaged(it, wanted) })
        return changing
    }

    /**
     * [entry] as [wanted], and therefore NOT transcribed any more: a decode belongs to the language it was
     * made in, and a Czech sentence read as English is not a sentence to correct but one to decode again.
     * The failed-attempt count goes with it, so a clip that came back silent in the wrong language is not
     * still accused of silence. Corrections already made are untouched — they were written to the corpus
     * the moment they were saved, which is what makes them evidence rather than screen state.
     */
    private fun relanguaged(entry: Entry, wanted: String): Entry {
        if (entry.language == wanted) return entry
        return entry.copy(
            language = wanted,
            recognized = null,
            text = null,
            rawWords = null,
            rawConfidences = null,
            attempts = 0,
            edited = false,
            settled = null
        )
    }

    // ---- long recordings (contract §9) -----------------------------------------------------------------

    /**
     * Remember that this long recording has been written out as an OGG at [path]. The WAV stays: the OGG
     * and the sentences are two different things to want from one recording, and doing one of them may not
     * cost the other. The caller deletes the recording when the last thing wanted from it is done.
     */
    fun markOgg(uuid: String, path: String) = update(uuid) { e -> e.copy(ogg = path) }

    /** The same for a recording cut into [pieces] sentences. */
    fun markSplit(uuid: String, pieces: Int) = update(uuid) { e -> e.copy(pieces = pieces) }

    /**
     * One sentence cut out of the long recording [parent]: [pcm] (raw PCM16 at the agreed rate) written as
     * a canonical WAV under a uuid of its own, and an index row that is an ORDINARY captured sentence — so
     * it is decoded, reviewed and handed to 言語島 exactly as a sentence spoken into one press is, and §2
     * needs to know nothing about any of this.
     *
     * [offsetMs] is where the piece began inside the recording, which is what makes its `capturedAt` the
     * time it was really spoken. The checksum is empty: nobody declared these bytes to us, we cut them.
     */
    fun addPiece(parent: Entry, offsetMs: Long, pcm: ByteArray): String? {
        if (!usable) return null
        if (pcm.size < MIN_MS * BYTES_PER_MS) return null
        val uuid = UUID.randomUUID().toString()
        dir.mkdirs()
        val part = File(dir, "$uuid$PART_SUFFIX")
        return try {
            FileOutputStream(part).use { out ->
                out.write(wavHeader(pcm.size))
                out.write(pcm)
                out.flush()
                out.fd.sync()
            }
            val clip = File(dir, "$uuid.wav")
            if (!part.renameTo(clip)) {
                part.delete()
                return null
            }
            val entries = readIndex().toMutableList()
            entries.add(
                Entry(
                    uuid = uuid,
                    clip = clip.name,
                    capturedAt = parent.capturedAt + offsetMs,
                    durationMs = pcm.size.toLong() / BYTES_PER_MS,
                    language = parent.language,
                    byteLength = clip.length(),
                    sha256 = "",
                    parent = parent.uuid
                )
            )
            writeIndex(entries)
            uuid
        } catch (e: Exception) {
            part.delete()
            log("addPiece", e)
            null
        }
    }

    /** The 44-byte canonical header for [dataBytes] of the agreed audio — the one the contract names. */
    private fun wavHeader(dataBytes: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            putShort(1).putShort(1).putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        }.array()

    /**
     * Where the audio of [clip] lies and how much of it there is, read from the header alone — what the
     * splitter needs in order to walk a four-hour recording frame by frame without holding it in memory.
     */
    fun pcmOf(clip: File): Pcm? = readHeader(clip)?.let { Pcm(it.dataOffset, it.dataBytes) }

    class Pcm(val offset: Long, val bytes: Long)

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
    fun readSamples(entry: Entry): FloatArray? = readSamples(clipFile(entry) ?: return null)

    /**
     * The same conversion for a clip given by path. A sentence whose words were corrected has already
     * handed its recording to the voice corpus, so the file is no longer in this store's directory — the
     * caller resolves it there and passes it in rather than losing the ability to decode it again.
     */
    fun readSamples(clip: File): FloatArray? {
        if (!clip.isFile) return null
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
        declaredSha: String,
        kind: Kind
    ): Reject? {
        if (written != declaredBytes) return Reject.LENGTH
        if (declaredSha.isNotBlank() && digest.toHex() != declaredSha.lowercase()) return Reject.SHA256
        val header = try {
            readHeader(file)
        } catch (e: Exception) {
            log("verify", e)
            return Reject.IO
        } ?: return Reject.FORMAT
        if (!header.agreed) return Reject.FORMAT
        val duration = header.durationMs
        if (duration < MIN_MS || duration > maxMsOf(kind)) return Reject.LENGTH
        return null
    }

    /**
     * A clip's shape, read from its header and nothing else: a four-hour recording is 460 MB, and reading
     * it whole in order to look at 44 bytes is an out-of-memory error with a walk's audio inside it. The
     * chunk list is walked by SEEKING, so a header of any shape is handled and no payload is ever read.
     */
    private class Header(
        val dataOffset: Long,
        val dataBytes: Long,
        val format: Int,
        val channels: Int,
        val rate: Int,
        val bits: Int
    ) {
        /** From the bytes that are REALLY there — a recording a kill cut short declares more than it has. */
        val durationMs: Long get() = dataBytes / BYTES_PER_MS

        /** 16 kHz mono PCM16 — byte-for-byte the format the voice corpus itself writes. */
        val agreed: Boolean get() = format == 1 && channels == 1 && rate == SAMPLE_RATE && bits == 16
    }

    private fun readHeader(file: File): Header? {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < 44) return null
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var format: ByteBuffer? = null
            var dataOffset = -1L
            var dataBytes = 0L
            var offset = 12L
            val head = ByteArray(8)
            while (offset + 8 <= length) {
                raf.seek(offset)
                raf.readFully(head)
                val name = String(head, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (size < 0) return null
                val payload = offset + 8
                if (name == "fmt " && size >= 16 && format == null) {
                    val fmt = ByteArray(16)
                    raf.readFully(fmt)
                    format = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                } else if (name == "data" && dataOffset < 0) {
                    dataOffset = payload
                    dataBytes = minOf(size.toLong(), length - payload).coerceAtLeast(0L)
                }
                if (format != null && dataOffset >= 0) break
                offset = payload + size + (size and 1)
            }
            val fmt = format ?: return null
            if (dataOffset < 0) return null
            val audioFormat = fmt.short.toInt()
            val channels = fmt.short.toInt()
            val rate = fmt.int
            fmt.int // byte rate — derived, not trusted
            fmt.short // block align
            val bits = fmt.short.toInt()
            return Header(dataOffset, dataBytes, audioFormat, channels, rate, bits)
        }
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
        readHeader(file)?.durationMs
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
        val recognized = if (o.isNull("recognized")) null else o.optString("recognized")
        val text = if (o.isNull("text")) null else o.optString("text")
        return Entry(
            uuid = uuid,
            clip = o.optString("clip", "$uuid.wav"),
            capturedAt = o.optLong("capturedAt"),
            durationMs = o.optLong("durationMs"),
            language = o.optString("language", "en"),
            byteLength = o.optLong("byteLength"),
            sha256 = o.optString("sha256"),
            recognized = recognized,
            text = text,
            rawWords = rawWords,
            rawConfidences = rawConfidences,
            attempts = o.optInt("attempts"),
            // Written before the flag existed: a sentence whose text already differs from the decode was
            // settled by hand, which is exactly what the flag would have said about it.
            edited = o.optBoolean("edited", text != null && text != recognized),
            settled = settledFrom(o.optJSONArray("settled")),
            kind = Kind.of(o.optString("kind")),
            salvaged = o.optBoolean("salvaged"),
            parent = if (o.isNull("parent")) null else o.optString("parent"),
            ogg = if (o.isNull("ogg")) null else o.optString("ogg"),
            pieces = o.optInt("pieces")
        )
    }

    /**
     * The settled words as stored: one small object per word, by the index of the word in the text they
     * were written with. An unknown state is dropped rather than guessed — a mark nobody can explain is
     * worse than no mark.
     */
    private fun settledFrom(arr: JSONArray?): List<VoiceSettledWords.Settled>? {
        if (arr == null) return null
        val out = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val index = o.optInt("i", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val heard = o.optString("heard").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val state = when (o.optString("state")) {
                "replaced" -> VoiceSettledWords.State.REPLACED
                "corrected" -> VoiceSettledWords.State.CORRECTED
                else -> return@mapNotNull null
            }
            VoiceSettledWords.Settled(index, heard, state)
        }
        return out.ifEmpty { null }
    }

    private fun settledTo(settled: List<VoiceSettledWords.Settled>?): Any {
        if (settled.isNullOrEmpty()) return JSONObject.NULL
        val arr = JSONArray()
        for (word in settled) {
            arr.put(
                JSONObject()
                    .put("i", word.index)
                    .put("heard", word.heard)
                    .put(
                        "state",
                        when (word.state) {
                            VoiceSettledWords.State.REPLACED -> "replaced"
                            VoiceSettledWords.State.CORRECTED -> "corrected"
                        }
                    )
            )
        }
        return arr
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
                        .put("edited", e.edited)
                        .put("settled", settledTo(e.settled))
                        .put("kind", e.kind.tag)
                        .put("salvaged", e.salvaged)
                        .put("parent", e.parent ?: JSONObject.NULL)
                        .put("ogg", e.ogg ?: JSONObject.NULL)
                        .put("pieces", e.pieces)
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

        /** The recorder's own ceiling for one press-to-press recording (contract §9): four hours. */
        private const val MAX_LONG_MS = 4 * 60 * 60 * 1000L

        /** Shorter than this is a stray key press, not a sentence. */
        private const val MIN_MS = 200L

        private const val MIN_FILE_BYTES = 44L + 2 * SAMPLE_RATE * MIN_MS / 1000
        private const val MAX_FILE_BYTES = 1024L + 2 * SAMPLE_RATE * MAX_MS / 1000
        private const val MAX_LONG_FILE_BYTES = 1024L + 2 * SAMPLE_RATE * MAX_LONG_MS / 1000

        /** The agreed audio, in bytes per millisecond — 16 kHz × PCM16 mono. */
        const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000

        /** The longest and the shortest a clip of [kind] may be. */
        private fun maxBytesOf(kind: Kind) = if (kind == Kind.LONG) MAX_LONG_FILE_BYTES else MAX_FILE_BYTES

        private fun maxMsOf(kind: Kind) = if (kind == Kind.LONG) MAX_LONG_MS else MAX_MS

        /** How long a clip with no index row is left alone before it counts as an orphan. */
        private const val ORPHAN_GRACE_MS = 60 * 60 * 1000L

        /**
         * How much captured audio may wait at once. Two gigabytes rather than §1's 512 MB because a long
         * recording is up to four hours (460 MB) and CUTTING one needs room for the pieces beside the
         * original — which is also why the long recordings and the sentences share one budget instead of
         * each getting their own: a split must never fail for want of room the recording itself occupies.
         */
        private const val BUDGET_BYTES = 2048L * 1024 * 1024

        /** A uuid becomes a file name, so it may only be a plain one. */
        fun isSafeUuid(uuid: String): Boolean =
            uuid.isNotEmpty() && uuid.length <= 64 && uuid.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
