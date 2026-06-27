package com.urik.keyboard.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface UserDictionaryDao {
    @Query("SELECT * FROM user_dictionary WHERE language_tag = :languageTag")
    suspend fun getForLanguage(languageTag: String): List<UserDictionaryEntry>

    @Query("SELECT * FROM user_dictionary ORDER BY language_tag, kind, match_key, value")
    suspend fun getAll(): List<UserDictionaryEntry>

    /**
     * Add a new entry or, if (language, kind, key, value) already exists, bump its frequency by [amount] and
     * touch [now]. An explicit "add" seeds with a large [amount] (so the entry is immediately top-ranked);
     * an ordinary use seeds/increments by 1 (so a repeated phrase climbs). Mirrors the kanji DAO's upsert.
     */
    @Query(
        """
        INSERT INTO user_dictionary (language_tag, kind, match_key, value, frequency, added_at, last_used)
        VALUES (:languageTag, :kind, :matchKey, :value, :amount, :now, :now)
        ON CONFLICT(language_tag, kind, match_key, value)
        DO UPDATE SET frequency = frequency + :amount, last_used = :now
        """
    )
    suspend fun upsertIncrement(
        languageTag: String,
        kind: String,
        matchKey: String,
        value: String,
        amount: Int,
        now: Long
    )

    /** Increment every entry of [languageTag] whose inserted [value] matches a just-committed word. */
    @Query(
        "UPDATE user_dictionary SET frequency = frequency + 1, last_used = :now " +
            "WHERE language_tag = :languageTag AND value = :value"
    )
    suspend fun incrementByValue(languageTag: String, value: String, now: Long): Int

    /** Edit an existing entry (from the settings editor), keyed by its stable row [id]. */
    @Query(
        "UPDATE user_dictionary SET match_key = :matchKey, value = :value, last_used = :now WHERE id = :id"
    )
    suspend fun updateEntry(id: Long, matchKey: String, value: String, now: Long): Int

    @Query("DELETE FROM user_dictionary WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM user_dictionary WHERE language_tag = :languageTag")
    suspend fun clearLanguage(languageTag: String): Int

    @Query("DELETE FROM user_dictionary")
    suspend fun clearAll(): Int
}
