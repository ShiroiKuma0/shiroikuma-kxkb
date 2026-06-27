package com.urik.keyboard.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry of the unified, per-language **user dictionary** — the single store behind every
 * user-taught word, every text shortcut and every Japanese reading→kanji registration. It deliberately
 * replaces the old Japanese-only `user_kanji_frequency` path and the (never-surfaced) Latin learning gap.
 *
 * - [languageTag] scopes the entry to ONE language. Entries never bleed across languages: a `cs` word is
 *   only ever offered while typing Czech, never under `en`/`ru`/`ja`. (Firm requirement.)
 * - [kind] is one of [UserDictionaryKind]: a plain word, a shortcut→expansion, or a Japanese reading→surface.
 * - [matchKey] is what the user TYPES to trigger the entry (the word itself, the shortcut, or the hiragana
 *   reading); [value] is what gets OFFERED / inserted (the word, the expansion, or the kanji surface).
 * - [frequency] drives ranking: the user's own entries are heavily prioritised (see the suggestion path),
 *   and using/typing one increments it so repeated phrases climb to the very top.
 *
 * The flat (languageTag, kind, matchKey, value, frequency, timestamps) shape is intentionally
 * serialisation-friendly so the planned granular export/import module can treat the user dictionary as one
 * self-describing category.
 */
@Entity(
    tableName = "user_dictionary",
    indices = [
        Index(
            value = ["language_tag", "kind", "match_key", "value"],
            name = "idx_user_dict_unique",
            unique = true
        ),
        Index(
            value = ["language_tag", "kind"],
            name = "idx_user_dict_lang"
        )
    ]
)
data class UserDictionaryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "language_tag")
    val languageTag: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "match_key")
    val matchKey: String,
    @ColumnInfo(name = "value")
    val value: String,
    @ColumnInfo(name = "frequency")
    val frequency: Int = 1,
    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_used")
    val lastUsed: Long = System.currentTimeMillis()
)

/** The three entry shapes the user dictionary holds. Stored as the literal [tag] so exports stay readable. */
enum class UserDictionaryKind(val tag: String) {
    /** A plain word: [UserDictionaryEntry.matchKey] == [UserDictionaryEntry.value] (the word, original case). */
    WORD("word"),

    /** A shortcut→expansion: type [matchKey] (e.g. "omw") to get [value] (e.g. "on my way"). */
    SHORTCUT("shortcut"),

    /** A Japanese registration: hiragana reading [matchKey] (しろいくま) → kanji surface [value] (白い熊). */
    JAPANESE("japanese");

    companion object {
        fun fromTag(tag: String): UserDictionaryKind? = entries.firstOrNull { it.tag == tag }
    }
}
