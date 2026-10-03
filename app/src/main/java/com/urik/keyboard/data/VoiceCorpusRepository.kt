package com.urik.keyboard.data

import android.content.Context
import com.urik.keyboard.data.database.DatabaseAvailability
import com.urik.keyboard.data.database.VoiceCorpusDao
import com.urik.keyboard.data.database.VoiceUtterance
import com.urik.keyboard.data.database.VoiceWordEvent
import com.urik.keyboard.data.database.VoiceWordEventKind
import com.urik.keyboard.service.voice.VoiceKnowledge
import com.urik.keyboard.service.voice.VoiceSessionLedger
import com.urik.keyboard.service.voice.VoiceWordJudge
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The private voice corpus: every correction you make to a dictated word and every suspect word you let
 * stand, each stored with the recording of the sentence it came from. Nothing leaves the device.
 *
 * Two uses:
 * - **Now** — [knowledge] tells the marking judge what you already settled: a recognition you confirmed is
 *   never marked again; one you corrected is marked with your correction offered as the alternative.
 * - **Later** — the (recording, reviewed sentence) pairs are a personal training set.
 *
 * Writes run on the repository's own scope, never the caller's: an acceptance fired as the keyboard hides
 * or the app switches must not be cancelled half-way. Nothing is written while the word database is the
 * in-memory stand-in (it would vanish with the process and leave orphaned recordings).
 */
@Singleton
class VoiceCorpusRepository
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val dao: VoiceCorpusDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val knowledgeByLanguage = ConcurrentHashMap<String, MutableMap<String, VoiceKnowledge>>()
    private val loadMutex = Mutex()

    /** Utterances already written (row + recording) in this process. */
    private val savedUtterances = ConcurrentHashMap.newKeySet<String>()

    private val enabled: Boolean get() = DatabaseAvailability.isReal

    /** Load [language]'s settled words (call before judging; cheap once cached). */
    suspend fun ensureLoaded(language: String) {
        val lang = language.substringBefore("-")
        if (knowledgeByLanguage.containsKey(lang)) return
        loadMutex.withLock {
            if (knowledgeByLanguage.containsKey(lang)) return
            val map = ConcurrentHashMap<String, VoiceKnowledge>()
            if (enabled) {
                try {
                    dao.eventsForLanguage(lang).forEach { applyEvent(map, it.recognized, it.corrected, it.kind) }
                } catch (e: Exception) {
                    log("load", e)
                }
            }
            knowledgeByLanguage[lang] = map
        }
    }

    /** What the corpus knows about the case-folded recognised word [coreLower] in [language]. */
    fun knowledge(language: String, coreLower: String): VoiceKnowledge? =
        knowledgeByLanguage[language.substringBefore("-")]?.get(coreLower)

    /**
     * You corrected [recognized] — one dictated word's core, or a run of words ("lukou i ostupu") starting at
     * [word] of [entry] — to [edited] (an unchanged [edited] counts as an explicit confirmation). Stored at
     * once — a correction is never held back for the acceptance.
     */
    fun recordCorrection(
        entry: VoiceSessionLedger.Entry,
        word: VoiceSessionLedger.Word,
        recognized: String,
        edited: String
    ) {
        val corrected = edited.trim()
        if (recognized.isEmpty() || corrected.isEmpty()) return
        val kind = if (corrected == recognized) VoiceWordEventKind.CONFIRMED else VoiceWordEventKind.CORRECTED
        val finalText = entry.currentText()
        val lang = entry.language.substringBefore("-")
        cache(lang, recognized, corrected, kind.tag)
        if (!enabled) return
        scope.launch {
            writeMutex.withLock {
                try {
                    ensureUtteranceSaved(entry, finalText)
                    insertEvent(entry, word, recognized, corrected, kind)
                    dao.updateFinalText(entry.utteranceId, finalText)
                } catch (e: Exception) {
                    log("recordCorrection", e)
                }
            }
        }
    }

    /**
     * The dictation in [entries] was accepted: every suspect word still standing unchanged is stored as
     * confirmed. An entry with nothing to store (no suspect left standing, nothing corrected) keeps no
     * recording — unless [keepAll] ("Keep all dictation audio"): then a clean sentence is kept too, as a
     * recording with its confirmed-correct text.
     */
    fun recordAcceptance(entries: List<VoiceSessionLedger.Entry>, keepAll: Boolean = false) {
        if (entries.isEmpty()) return
        val work = entries.mapNotNull { entry ->
            val confirmed = entry.words.filter { it.intact && it.isSuspect && !it.corrected && !it.autoReplaced }
            val replaced = entry.words.filter { it.intact && it.autoReplaced }
            val anyCorrected = entry.words.any { it.corrected } || entry.typedCorrections.isNotEmpty()
            if (confirmed.isEmpty() && replaced.isEmpty() && !anyCorrected && !keepAll) return@mapNotNull null
            val lang = entry.language.substringBefore("-")
            for (word in confirmed) {
                val core = VoiceWordJudge.coreOf(word.recognized)
                if (core.isNotEmpty()) cache(lang, core, core, VoiceWordEventKind.CONFIRMED.tag)
            }
            for (typed in entry.typedCorrections) cache(lang, typed.recognized, typed.corrected, VoiceWordEventKind.CORRECTED.tag)
            Accepted(entry, confirmed, replaced, entry.currentText())
        }
        if (work.isEmpty() || !enabled) return
        scope.launch {
            writeMutex.withLock {
                for ((entry, confirmed, replaced, finalText) in work) {
                    try {
                        ensureUtteranceSaved(entry, finalText)
                        for (word in confirmed) {
                            val core = VoiceWordJudge.coreOf(word.recognized)
                            if (core.isNotEmpty()) insertEvent(entry, word, core, core, VoiceWordEventKind.CONFIRMED)
                        }
                        // An automatic replacement you let stand is your correction again — another example
                        // with its recording, and the rule stays as it is.
                        for (word in replaced) {
                            val heard = VoiceWordJudge.coreOf(word.recognized)
                            val now = VoiceWordJudge.coreOf(word.current)
                            if (heard.isNotEmpty() && now.isNotEmpty()) {
                                insertEvent(entry, word, heard, now, VoiceWordEventKind.CORRECTED)
                            }
                        }
                        // Corrections you typed by hand over dictated words — the same evidence as the box's.
                        for (typed in entry.typedCorrections) {
                            val word = entry.words.getOrNull(typed.from) ?: continue
                            insertEvent(entry, word, typed.recognized, typed.corrected, VoiceWordEventKind.CORRECTED)
                        }
                        dao.updateFinalText(entry.utteranceId, finalText)
                    } catch (e: Exception) {
                        log("recordAcceptance", e)
                    }
                }
            }
        }
    }

    // ---- the corpus page -------------------------------------------------------------------------------

    /** One kept dictation for the corpus page: the row, its recording (if on disk) and its word evidence. */
    class Item(val utterance: VoiceUtterance, val clip: File?, val events: List<VoiceWordEvent>)

    /**
     * The words the corpus taught in [language], as you write them: every correction you typed and every
     * dictated word you confirmed (the vocabulary the decoder is biased towards).
     */
    suspend fun vocabulary(language: String): Set<String> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptySet()
        try {
            dao.eventsForLanguage(language.substringBefore("-")).map { it.corrected.trim() }.filter { it.isNotEmpty() }.toSet()
        } catch (e: Exception) {
            log("vocabulary", e)
            emptySet()
        }
    }

    /** Every kept dictation, newest first. */
    suspend fun items(): List<Item> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val events = dao.allEvents().groupBy { it.utteranceId }
        dao.allUtterances().map { u -> Item(u, clipFile(u.clipFile), events[u.id].orEmpty()) }
    }

    /** Bytes the recordings take on disk. */
    fun corpusBytes(): Long = File(context.filesDir, CORPUS_DIR).listFiles()?.sumOf { it.length() } ?: 0L

    fun clipFile(name: String?): File? = name?.let { File(File(context.filesDir, CORPUS_DIR), it) }?.takeIf { it.isFile }

    /**
     * Delete one kept dictation — its recording, its row and its word evidence. What it taught (a confirmed
     * word, a correction rule) is forgotten too: the language's knowledge is rebuilt from what remains.
     */
    suspend fun delete(id: String) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val u = dao.utterance(id) ?: return@withContext
            clipFile(u.clipFile)?.delete()
            dao.deleteEventsOf(id)
            dao.deleteUtterance(id)
            savedUtterances.remove(id)
            knowledgeByLanguage.remove(u.languageTag)
        }
    }

    /** Delete the whole corpus: every recording, row and rule. */
    suspend fun deleteAll() = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            File(context.filesDir, CORPUS_DIR).listFiles()?.forEach { it.delete() }
            dao.deleteAllEvents()
            dao.deleteAllUtterances()
            savedUtterances.clear()
            knowledgeByLanguage.clear()
        }
    }

    /** Forget the cached knowledge (after an import added evidence); it reloads on the next dictation. */
    fun invalidate() {
        knowledgeByLanguage.clear()
        savedUtterances.clear()
    }

    private data class Accepted(
        val entry: VoiceSessionLedger.Entry,
        val confirmed: List<VoiceSessionLedger.Word>,
        val replaced: List<VoiceSessionLedger.Word>,
        val finalText: String
    )

    private suspend fun insertEvent(
        entry: VoiceSessionLedger.Entry,
        word: VoiceSessionLedger.Word,
        recognized: String,
        corrected: String,
        kind: VoiceWordEventKind
    ) {
        dao.insertEvent(
            utteranceId = entry.utteranceId,
            languageTag = entry.language.substringBefore("-"),
            wordIndex = word.index,
            recognized = recognized,
            corrected = corrected,
            kind = kind.tag,
            confidence = word.confidence,
            reason = word.judgement.reason?.name,
            createdAt = System.currentTimeMillis()
        )
    }

    private suspend fun ensureUtteranceSaved(entry: VoiceSessionLedger.Entry, finalText: String) {
        if (entry.utteranceId in savedUtterances) return
        val samples = entry.samples
        val clip = samples?.takeIf { it.isNotEmpty() }?.let { writeClip(entry.utteranceId, it) }
        dao.insertUtterance(
            id = entry.utteranceId,
            languageTag = entry.language.substringBefore("-"),
            recognizedText = entry.recognizedText,
            finalText = finalText,
            clipFile = clip,
            sampleCount = samples?.size ?: 0,
            createdAt = System.currentTimeMillis()
        )
        savedUtterances.add(entry.utteranceId)
    }

    /** 16 kHz mono 16-bit PCM WAV under `filesDir/voice_corpus/`; returns the file name, or null on failure. */
    private fun writeClip(id: String, samples: FloatArray): String? = try {
        val dir = File(context.filesDir, CORPUS_DIR).apply { mkdirs() }
        val name = "$id.wav"
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (s in samples) buffer.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        FileOutputStream(File(dir, name)).use { it.write(buffer.array()) }
        name
    } catch (e: Exception) {
        log("writeClip", e)
        null
    }

    private fun cache(lang: String, recognized: String, corrected: String, kind: String) {
        val map = knowledgeByLanguage.getOrPut(lang) { ConcurrentHashMap() }
        applyEvent(map, recognized, corrected, kind)
    }

    private fun log(operation: String, e: Exception) {
        ErrorLogger.logException(
            component = "VoiceCorpusRepository",
            severity = ErrorLogger.Severity.HIGH,
            exception = e,
            context = mapOf("operation" to operation)
        )
    }

    companion object {
        const val CORPUS_DIR = "voice_corpus"
        private const val SAMPLE_RATE = 16_000
        private const val WAV_HEADER_BYTES = 44

        /**
         * Fold one event into a language's knowledge — newest wins. A confirmation settles the word; a
         * correction marks the recognition as a misrecognition AND settles what you typed (a word you typed
         * yourself is not suspect when the recogniser gets it right next time).
         */
        fun applyEvent(map: MutableMap<String, VoiceKnowledge>, recognized: String, corrected: String, kind: String) {
            val key = recognized.lowercase()
            if (kind == VoiceWordEventKind.CORRECTED.tag && corrected.lowercase() != key) {
                map[key] = VoiceKnowledge.Misrecognized(corrected)
                if (corrected.none { it.isWhitespace() }) map[corrected.lowercase()] = VoiceKnowledge.Confirmed
            } else {
                map[key] = VoiceKnowledge.Confirmed
            }
        }
    }
}
