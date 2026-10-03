package com.urik.keyboard.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Version 9 → 10 adds the voice corpus tables. A real v9 file (built from the exported v9 schema) is opened
 * through the production builder: Room validates the migrated schema against the v10 entities, existing rows
 * survive, and the new tables take writes.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceCorpusMigrationTest {
    @Test
    fun `a version 9 database migrates to 10 and keeps its rows`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration_test.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.delete()

        val schema = JSONObject(File("schemas/com.urik.keyboard.data.database.KeyboardDatabase/9.json").readText())
            .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (k in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                entity.optJSONArray("contentSyncTriggers")?.let { triggers ->
                    for (k in 0 until triggers.length()) db.execSQL(triggers.getString(k))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO user_dictionary (language_tag, kind, match_key, value, frequency, added_at, last_used) " +
                    "VALUES ('cs', 'word', 'Pavel', 'Pavel', 5, 1, 1)"
            )
            db.version = 9
        }

        val migrated = KeyboardDatabase.openFile(context, name, null)
        try {
            assertEquals("Pavel", migrated.userDictionaryDao().getForLanguage("cs").single().value)
            val dao = migrated.voiceCorpusDao()
            dao.insertUtterance("u1", "cs", "Ahoj Pavle", "Ahoj Pavle", "u1.wav", 16000, 1L)
            dao.insertEvent("u1", "cs", 1, "Pavle", "Pavle", "confirmed", 0.3f, "UNKNOWN_WORD", 2L)
            assertEquals(1, dao.utteranceCount())
            assertEquals("Pavle", dao.eventsForLanguage("cs").single().recognized)
        } finally {
            migrated.close()
        }
    }
}
