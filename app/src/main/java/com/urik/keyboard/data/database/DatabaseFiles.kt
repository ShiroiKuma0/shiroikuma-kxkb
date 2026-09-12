package com.urik.keyboard.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.urik.keyboard.utils.ErrorLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/**
 * The keyboard database FILE, handled on one principle: **it is never deleted by code.**
 *
 * Both open helpers Room can use — the platform's and SQLCipher's — answer "corruption" by deleting the
 * database and starting over, and the platform counts "file is not a database" as corruption. So opening
 * the encrypted file without its passphrase, even once, used to wipe every learned word on the spot (the
 * 2026-09-03 loss). Here:
 *
 * - [probe] opens the file eagerly, read-write, through SQLCipher, with a handler that THROWS instead of
 *   deleting — so the decision what to do with an unreadable file is made by `DatabaseModule`, before Room
 *   ever touches it, rather than by a library's default.
 * - [moveAside] is the only "recovery": the file and its sidecars are renamed to
 *   `<name>.<reason>-<stamp>`, so a fresh database can be created while the old bytes stay for a manual
 *   rescue.
 * - [SafeEncryptedOpenHelperFactory] / [SafePlainOpenHelperFactory] are what Room actually opens through;
 *   their corruption handlers move aside too, for corruption that surfaces only mid-session.
 * - [reencrypt] moves a file from one key to another through `sqlcipher_export` into a fresh file; the
 *   original is renamed `<name>.rekeyed-<stamp>`, never deleted.
 *
 * **The key every database before `+324` was really opened with is [LEGACY_ZERO_KEY]** — 32 zero bytes. The
 * module zeroed the passphrase array in a `finally` right after building Room, and Room opens lazily, so by
 * the time SQLCipher read the array (`nativeKey` takes it by reference; nothing in the chain copies) it held
 * zeros. Upstream Urik's encryption has therefore always been nominal. A file that answers to the zero key is
 * re-encrypted to the real passphrase on first sight (`DatabaseModule`), and every recovery path tries it.
 */
object DatabaseFiles {
    /** What every pre-`+324` database is keyed with; see the class comment. A fresh copy each time. */
    val LEGACY_ZERO_KEY: ByteArray get() = ByteArray(PASSPHRASE_BYTES)

    private const val PASSPHRASE_BYTES = 32

    enum class Probe {
        /** Opens and reads with what we gave it. */
        OK,

        /** No file: a fresh database will be created. */
        ABSENT,

        /** SQLCipher could not read it with this key (or without one): wrong key, or a plain file. */
        NOT_A_DATABASE,

        /** SQLite reports the content itself damaged. */
        CORRUPT,

        /** Something else — I/O, permissions, locking. Nothing is known about the content. */
        ERROR
    }

    private const val TAG = "DatabaseFiles"

    /** The files [moveAside] carries along with the database. */
    val SIDECARS: List<String> = listOf("-wal", "-shm", "-journal")

    /**
     * Try to open the database with [passphrase] (null = as a plain SQLite file) and read its schema. Read-write,
     * exactly as the real open will be, so a hot journal is recovered here and not misread as damage; with a
     * wrong key SQLCipher fails at the header before it writes anything.
     */
    fun probe(context: Context, passphrase: ByteArray?): Probe =
        probeFile(context.getDatabasePath(KeyboardDatabase.DATABASE_NAME), passphrase)

    /** [probe] for any database file — the set-aside copies included. */
    fun probeFile(file: File, passphrase: ByteArray?): Probe {
        if (!file.exists()) return Probe.ABSENT
        if (!sqlCipherLoaded()) return Probe.ERROR
        return try {
            openRaw(file, passphrase).use { open ->
                open.rawQuery("SELECT count(*) FROM sqlite_master", null)?.use { it.moveToFirst() }
            }
            Probe.OK
        } catch (_: SQLiteNotADatabaseException) {
            Probe.NOT_A_DATABASE
        } catch (_: SQLiteDatabaseCorruptException) {
            Probe.CORRUPT
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = TAG,
                severity = ErrorLogger.Severity.HIGH,
                exception = e,
                context = mapOf("phase" to "probe", "encrypted" to (passphrase != null).toString())
            )
            Probe.ERROR
        }
    }

    /** A raw SQLCipher handle, read-write, corruption thrown rather than handled; null key = plain file. */
    private fun openRaw(file: File, passphrase: ByteArray?): SQLiteDatabase =
        if (passphrase == null) {
            SQLiteDatabase.openDatabase(
                file.absolutePath, "", null, SQLiteDatabase.OPEN_READWRITE, ThrowingErrorHandler, null
            )
        } else {
            SQLiteDatabase.openDatabase(
                file.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READWRITE, ThrowingErrorHandler, null
            )
        }

    /**
     * Re-encrypt the database from key [from] (null = a plain file) to [to]: `sqlcipher_export` into a fresh
     * file next to it, verify that file opens with [to], then swap — the original is renamed
     * `<name>.rekeyed-<stamp>` (with its sidecars), never deleted. The pre-encryption upgrade, the repair of a
     * plain file an old build's wipe left behind, and the move off [LEGACY_ZERO_KEY] all go through here.
     */
    fun reencrypt(context: Context, from: ByteArray?, to: ByteArray): Boolean {
        val live = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        val temp = File(live.parentFile, "${live.name}_reencrypt")
        if (!live.exists() || !sqlCipherLoaded()) return false
        return try {
            val hex = to.joinToString("") { "%02x".format(it) }
            val safePath = temp.absolutePath
            check(!safePath.contains('\'')) { "Unexpected single-quote in database path: $safePath" }
            temp.delete()
            SIDECARS.forEach { File(temp.path + it).delete() }

            openRaw(live, from).use { db ->
                db.execSQL("ATTACH DATABASE '$safePath' AS encrypted KEY \"x'$hex'\"")
                db.rawQuery("SELECT sqlcipher_export('encrypted')", null)?.use { }
                db.execSQL("DETACH DATABASE encrypted")
            }
            check(probeFile(temp, to) == Probe.OK) { "re-encrypted copy does not open with the new key" }

            // The original keeps its bytes under a new name; the verified copy takes its place.
            val aside = moveAside(context, "rekeyed") ?: error("cannot set the original aside")
            if (!temp.renameTo(live)) {
                // Put the original back so the next start finds it exactly as it was.
                aside.renameTo(live)
                SIDECARS.forEach { File(aside.path + it).renameTo(File(live.path + it)) }
                error("cannot move the re-encrypted copy into place")
            }
            true
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = TAG,
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "reencrypt", "from_plain" to (from == null).toString())
            )
            temp.delete()
            SIDECARS.forEach { File(temp.path + it).delete() }
            false
        }
    }

    /**
     * Rename the database and its `-wal`/`-shm`/`-journal` sidecars to `<name>.<reason>-<stamp>` (sidecar
     * suffixes kept, so the set can be renamed back by hand). Returns the new base file, or null when the main
     * file could not be moved — in which case nothing has been touched and the caller must not open it.
     */
    fun moveAside(context: Context, reason: String): File? {
        val db = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        val target = File(db.parentFile, "${db.name}.$reason-$stamp")
        if (db.exists() && !db.renameTo(target)) {
            ErrorLogger.logException(
                component = TAG,
                severity = ErrorLogger.Severity.CRITICAL,
                exception = IllegalStateException("cannot rename ${db.name} to ${target.name}"),
                context = mapOf("phase" to "move_aside", "reason" to reason)
            )
            return null
        }
        for (suffix in SIDECARS) {
            val sidecar = File(db.path + suffix)
            if (!sidecar.exists()) continue
            // A stale sidecar next to a fresh database is ignored by SQLite (its salt won't match), but it
            // is the old file's data: keep it with the old file if at all possible.
            if (!sidecar.renameTo(File(target.path + suffix)) && !sidecar.delete()) {
                ErrorLogger.logException(
                    component = TAG,
                    severity = ErrorLogger.Severity.HIGH,
                    exception = IllegalStateException("cannot move or remove sidecar ${sidecar.name}"),
                    context = mapOf("phase" to "move_aside", "reason" to reason)
                )
            }
        }
        ErrorLogger.logException(
            component = TAG,
            severity = ErrorLogger.Severity.CRITICAL,
            exception = IllegalStateException("database set aside as ${target.name}"),
            context = mapOf("phase" to "move_aside", "reason" to reason)
        )
        return target
    }

    /**
     * The application loads SQLCipher's native library before Hilt builds the graph; this is the belt to that
     * brace — a repeated `loadLibrary` is a no-op, and a probe without the library must answer [Probe.ERROR]
     * (nothing known, file untouched) rather than crash the process with an `UnsatisfiedLinkError`.
     */
    private fun sqlCipherLoaded(): Boolean = try {
        System.loadLibrary("sqlcipher")
        true
    } catch (e: UnsatisfiedLinkError) {
        ErrorLogger.logException(
            component = TAG,
            severity = ErrorLogger.Severity.CRITICAL,
            exception = e,
            context = mapOf("phase" to "probe", "action" to "sqlcipher_not_loaded")
        )
        false
    }

    /** For [probe]: surface corruption to the caller instead of letting SQLCipher delete the file. */
    private object ThrowingErrorHandler : DatabaseErrorHandler {
        override fun onCorruption(db: SQLiteDatabase, exception: android.database.sqlite.SQLiteException) {
            throw exception
        }
    }

    /** Runtime corruption (mid-session): close, set the file aside, let the helper create a fresh one. */
    private class MoveAsideErrorHandler(private val context: Context) : DatabaseErrorHandler {
        override fun onCorruption(db: SQLiteDatabase, exception: android.database.sqlite.SQLiteException) {
            ErrorLogger.logException(
                component = TAG,
                severity = ErrorLogger.Severity.CRITICAL,
                exception = exception,
                context = mapOf("phase" to "runtime_corruption", "encrypted" to "true")
            )
            runCatching { if (db.isOpen) db.close() }
            moveAside(context, "corrupt")
        }
    }

    /**
     * Room's open helper for the ENCRYPTED database. SQLCipher's own `SupportOpenHelperFactory` hard-wires a
     * null error handler — i.e. its default, which deletes the file on corruption — and never consults Room's
     * callback for it; this is the same thin adapter with [MoveAsideErrorHandler] in that slot.
     */
    class SafeEncryptedOpenHelperFactory(passphrase: ByteArray) : SupportSQLiteOpenHelper.Factory {
        // A COPY, held for the life of the process: SQLCipher keys from this array by reference at every
        // (lazy) open, and the caller zeroes its own array the moment Room is built. Holding the reference
        // is what keyed every earlier database with zeros.
        private val key = passphrase.copyOf()

        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            SafeEncryptedOpenHelper(configuration, key)
    }

    private class SafeEncryptedOpenHelper(
        configuration: SupportSQLiteOpenHelper.Configuration,
        passphrase: ByteArray
    ) : SupportSQLiteOpenHelper {
        private val callback = configuration.callback
        private val helper = object : SQLiteOpenHelper(
            configuration.context,
            configuration.name,
            passphrase,
            null,
            callback.version,
            0,
            MoveAsideErrorHandler(configuration.context),
            null,
            false
        ) {
            override fun onConfigure(db: SQLiteDatabase) = callback.onConfigure(db)

            override fun onCreate(db: SQLiteDatabase) = callback.onCreate(db)

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SQLiteDatabase) = callback.onOpen(db)
        }

        override val databaseName: String? get() = helper.databaseName

        override val writableDatabase: SupportSQLiteDatabase get() = helper.writableDatabase

        override val readableDatabase: SupportSQLiteDatabase get() = helper.readableDatabase

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) = helper.setWriteAheadLoggingEnabled(enabled)

        override fun close() = helper.close()
    }

    /**
     * Room's open helper for the PLAIN database (no lock screen, nothing ever encrypted). The platform helper
     * routes corruption to the callback's `onCorruption`, whose default deletes the file; wrapped to move aside.
     */
    class SafePlainOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
        private val delegate = FrameworkSQLiteOpenHelperFactory()

        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val inner = configuration.callback
            val wrapped = object : SupportSQLiteOpenHelper.Callback(inner.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)

                override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    inner.onUpgrade(db, oldVersion, newVersion)

                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    inner.onDowngrade(db, oldVersion, newVersion)

                override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    ErrorLogger.logException(
                        component = TAG,
                        severity = ErrorLogger.Severity.CRITICAL,
                        exception = IllegalStateException("corruption reported on ${db.path}"),
                        context = mapOf("phase" to "runtime_corruption", "encrypted" to "false")
                    )
                    runCatching { if (db.isOpen) db.close() }
                    moveAside(configuration.context, "corrupt")
                }
            }
            return delegate.create(
                SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                    .name(configuration.name)
                    .callback(wrapped)
                    .noBackupDirectory(configuration.useNoBackupDirectory)
                    .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                    .build()
            )
        }
    }
}
