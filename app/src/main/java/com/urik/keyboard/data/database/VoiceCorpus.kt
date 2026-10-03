package com.urik.keyboard.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One dictated chunk kept in the voice corpus: the recording ([clipFile], a 16 kHz mono 16-bit WAV under
 * `filesDir/voice_corpus/`), what the recogniser made of it and what it reads as after your review. The
 * (audio, [finalText]) pair is a ready training example for a later personal fine-tune; the per-word
 * evidence lives in [VoiceWordEvent].
 */
@Entity(tableName = "voice_utterance")
data class VoiceUtterance(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "language_tag")
    val languageTag: String,
    @ColumnInfo(name = "recognized_text")
    val recognizedText: String,
    @ColumnInfo(name = "final_text")
    val finalText: String,
    @ColumnInfo(name = "clip_file")
    val clipFile: String?,
    @ColumnInfo(name = "sample_count")
    val sampleCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

/**
 * One word-level piece of evidence: the recogniser heard [recognized] and you either corrected it to
 * [corrected] ([VoiceWordEventKind.CORRECTED]) or let it stand ([VoiceWordEventKind.CONFIRMED],
 * `corrected == recognized`). Words are stored without their surrounding punctuation. The newest event
 * for a (language, recognised word) decides how that recognition is treated next time.
 */
@Entity(
    tableName = "voice_word_event",
    indices = [
        Index(value = ["language_tag", "recognized"], name = "idx_voice_event_recognized"),
        Index(value = ["utterance_id"], name = "idx_voice_event_utterance")
    ]
)
data class VoiceWordEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "utterance_id")
    val utteranceId: String,
    @ColumnInfo(name = "language_tag")
    val languageTag: String,
    @ColumnInfo(name = "word_index")
    val wordIndex: Int,
    @ColumnInfo(name = "recognized")
    val recognized: String,
    @ColumnInfo(name = "corrected")
    val corrected: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "confidence")
    val confidence: Float?,
    @ColumnInfo(name = "reason")
    val reason: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

enum class VoiceWordEventKind(val tag: String) {
    CORRECTED("corrected"),
    CONFIRMED("confirmed")
}

@Dao
interface VoiceCorpusDao {
    @Query(
        """
        INSERT OR IGNORE INTO voice_utterance
            (id, language_tag, recognized_text, final_text, clip_file, sample_count, created_at)
        VALUES (:id, :languageTag, :recognizedText, :finalText, :clipFile, :sampleCount, :createdAt)
        """
    )
    suspend fun insertUtterance(
        id: String,
        languageTag: String,
        recognizedText: String,
        finalText: String,
        clipFile: String?,
        sampleCount: Int,
        createdAt: Long
    )

    @Query("UPDATE voice_utterance SET final_text = :finalText WHERE id = :id")
    suspend fun updateFinalText(id: String, finalText: String): Int

    @Query(
        """
        INSERT INTO voice_word_event
            (utterance_id, language_tag, word_index, recognized, corrected, kind, confidence, reason, created_at)
        VALUES (:utteranceId, :languageTag, :wordIndex, :recognized, :corrected, :kind, :confidence, :reason, :createdAt)
        """
    )
    suspend fun insertEvent(
        utteranceId: String,
        languageTag: String,
        wordIndex: Int,
        recognized: String,
        corrected: String,
        kind: String,
        confidence: Float?,
        reason: String?,
        createdAt: Long
    )

    @Query("SELECT * FROM voice_word_event WHERE language_tag = :languageTag ORDER BY created_at, id")
    suspend fun eventsForLanguage(languageTag: String): List<VoiceWordEvent>

    @Query("SELECT COUNT(*) FROM voice_utterance")
    suspend fun utteranceCount(): Int

    @Query("SELECT * FROM voice_utterance ORDER BY created_at DESC")
    suspend fun allUtterances(): List<VoiceUtterance>

    @Query("SELECT * FROM voice_word_event ORDER BY created_at, id")
    suspend fun allEvents(): List<VoiceWordEvent>

    @Query("SELECT * FROM voice_utterance WHERE id = :id")
    suspend fun utterance(id: String): VoiceUtterance?

    @Query("DELETE FROM voice_word_event WHERE utterance_id = :utteranceId")
    suspend fun deleteEventsOf(utteranceId: String): Int

    @Query("DELETE FROM voice_utterance WHERE id = :id")
    suspend fun deleteUtterance(id: String): Int

    @Query("DELETE FROM voice_word_event")
    suspend fun deleteAllEvents(): Int

    @Query("DELETE FROM voice_utterance")
    suspend fun deleteAllUtterances(): Int

    /** Whether this exact event is already stored (a re-imported backup must not duplicate the evidence). */
    @Query(
        """
        SELECT COUNT(*) FROM voice_word_event
        WHERE utterance_id = :utteranceId AND word_index = :wordIndex AND recognized = :recognized
          AND corrected = :corrected AND kind = :kind AND created_at = :createdAt
        """
    )
    suspend fun countEvent(
        utteranceId: String,
        wordIndex: Int,
        recognized: String,
        corrected: String,
        kind: String,
        createdAt: Long
    ): Int
}
