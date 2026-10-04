package com.urik.keyboard.data

import android.content.Context
import com.urik.keyboard.data.database.VoiceCorpusDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * The voice corpus as a backup part (in every default backup — the automation contract included): the rows as one JSON entry, and every recording as its own binary
 * entry under [CLIP_PREFIX] — streamed file to archive and back, never held in memory (a corpus runs to
 * hundreds of MB).
 *
 * Import MERGES like every other part: an utterance already present is kept, an event already present is
 * not duplicated, a recording is written only where none exists.
 */
@Singleton
class VoiceCorpusBackup
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val dao: VoiceCorpusDao,
    private val repository: VoiceCorpusRepository
) {
    private val corpusDir: File get() = File(context.filesDir, VoiceCorpusRepository.CORPUS_DIR)

    /** Write the rows as [jsonEntryName] and the recordings beside it; returns the number of dictations. */
    suspend fun export(zip: ZipOutputStream, jsonEntryName: String): Int {
        val utterances = dao.allUtterances()
        val events = dao.allEvents()
        val json = JSONObject()
            .put(
                "utterances",
                JSONArray().apply {
                    utterances.forEach { u ->
                        put(
                            JSONObject()
                                .put("id", u.id)
                                .put("languageTag", u.languageTag)
                                .put("recognizedText", u.recognizedText)
                                .put("finalText", u.finalText)
                                .put("clipFile", u.clipFile ?: JSONObject.NULL)
                                .put("sampleCount", u.sampleCount)
                                .put("createdAt", u.createdAt)
                        )
                    }
                }
            )
            .put(
                "events",
                JSONArray().apply {
                    events.forEach { e ->
                        put(
                            JSONObject()
                                .put("utteranceId", e.utteranceId)
                                .put("languageTag", e.languageTag)
                                .put("wordIndex", e.wordIndex)
                                .put("recognized", e.recognized)
                                .put("corrected", e.corrected)
                                .put("kind", e.kind)
                                .put("confidence", e.confidence?.toDouble() ?: JSONObject.NULL)
                                .put("reason", e.reason ?: JSONObject.NULL)
                                .put("createdAt", e.createdAt)
                        )
                    }
                }
            )
        zip.putNextEntry(ZipEntry(jsonEntryName))
        zip.write(json.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for (u in utterances) {
            val clip = u.clipFile?.let { File(corpusDir, it) }?.takeIf { it.isFile } ?: continue
            zip.putNextEntry(ZipEntry(CLIP_PREFIX + clip.name))
            clip.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return utterances.size
    }

    /** A recording entry met while reading an archive: written straight into the corpus (unless present). */
    fun importClip(entryName: String, data: InputStream) {
        val name = entryName.removePrefix(CLIP_PREFIX)
        // A clip name is ours (`<uuid>.wav`): anything with a path in it is not.
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.startsWith(".")) return
        corpusDir.mkdirs()
        val target = File(corpusDir, name)
        if (target.exists()) return
        val partial = File(corpusDir, "$name.part")
        try {
            partial.outputStream().use { data.copyTo(it) }
            partial.renameTo(target)
        } finally {
            partial.delete()
        }
    }

    /** Merge the rows of [json]; returns the number of dictations in it. */
    suspend fun importRows(json: JSONObject): Int {
        val utterances = json.optJSONArray("utterances") ?: JSONArray()
        for (i in 0 until utterances.length()) {
            val o = utterances.getJSONObject(i)
            dao.insertUtterance(
                id = o.getString("id"),
                languageTag = o.getString("languageTag"),
                recognizedText = o.optString("recognizedText"),
                finalText = o.optString("finalText"),
                clipFile = if (o.isNull("clipFile")) null else o.optString("clipFile"),
                sampleCount = o.optInt("sampleCount"),
                createdAt = o.optLong("createdAt")
            )
        }
        val events = json.optJSONArray("events") ?: JSONArray()
        for (i in 0 until events.length()) {
            val o = events.getJSONObject(i)
            val utteranceId = o.getString("utteranceId")
            val wordIndex = o.optInt("wordIndex")
            val recognized = o.getString("recognized")
            val corrected = o.getString("corrected")
            val kind = o.getString("kind")
            val createdAt = o.optLong("createdAt")
            if (dao.countEvent(utteranceId, wordIndex, recognized, corrected, kind, createdAt) > 0) continue
            dao.insertEvent(
                utteranceId = utteranceId,
                languageTag = o.getString("languageTag"),
                wordIndex = wordIndex,
                recognized = recognized,
                corrected = corrected,
                kind = kind,
                confidence = if (o.isNull("confidence")) null else o.optDouble("confidence").toFloat(),
                reason = if (o.isNull("reason")) null else o.optString("reason"),
                createdAt = createdAt
            )
        }
        repository.invalidate()
        return utterances.length()
    }

    companion object {
        /** Archive folder of the recordings (binary entries — never read as text). */
        const val CLIP_PREFIX = "voice_corpus/"
    }
}
