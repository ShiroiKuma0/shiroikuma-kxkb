package com.urik.keyboard.data.database

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.urik.keyboard.service.BackupManager
import com.urik.keyboard.service.BackupPart
import com.urik.keyboard.service.BlacklistRepository
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The way back for a database [DatabaseFiles.moveAside] set aside: every `keyboard_database.<reason>-<stamp>`
 * in the database directory that still opens — with the real passphrase, with the zero key every pre-`+324`
 * build used, or as a plain file — is MERGED into the live database through the backup engine (a second
 * [BackupManager] over the copy's DAOs exports the dictionary parts, the live one imports them: the same
 * row-merging import a restore uses), then renamed `<name>.recovered-<stamp>` so it is not merged twice.
 *
 * Why this exists: `+323` judged every existing database "unreadable" (it probed with the real passphrase, but
 * the files were keyed with zeros), set it aside and started fresh — on every process start. The words are
 * all still on disk, in those copies. Runs once per process, off the main thread, only against a REAL live
 * database, and never deletes anything: a copy that opens with no key at all is left where it is.
 */
@Singleton
class SetAsideRecovery
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val securityManager: DatabaseSecurityManager,
    private val backupManager: BackupManager,
    private val settingsRepository: SettingsRepository,
    private val blacklistRepository: BlacklistRepository
) {
    /** The file probe and the copy opener — both need SQLCipher's native library; the tests swap them. */
    @VisibleForTesting
    internal var prober: (File, ByteArray?) -> DatabaseFiles.Probe = DatabaseFiles::probeFile

    @VisibleForTesting
    internal var opener: (Context, String, ByteArray?) -> KeyboardDatabase = KeyboardDatabase::openFile

    /** Merge every recoverable set-aside copy into the live database. Returns how many were merged. */
    suspend fun run(): Int = withContext(Dispatchers.IO) {
        if (!DatabaseAvailability.isReal) return@withContext 0
        val dir = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME).parentFile ?: return@withContext 0
        val candidates = dir.listFiles { f -> isSetAsideCopy(f) }?.sortedBy { it.name } ?: return@withContext 0
        if (candidates.isEmpty()) return@withContext 0
        val real = (securityManager.resolvePassphrase() as? PassphraseResult.Available)?.passphrase
        try {
            candidates.count { mergeOne(it, real) }
        } finally {
            real?.fill(0)
        }
    }

    private fun isSetAsideCopy(f: File): Boolean =
        f.isFile &&
            f.name.startsWith(KeyboardDatabase.DATABASE_NAME + ".") &&
            RECOVERABLE_REASONS.any { f.name.startsWith("${KeyboardDatabase.DATABASE_NAME}.$it-") } &&
            DatabaseFiles.SIDECARS.none { f.name.endsWith(it) }

    private suspend fun mergeOne(file: File, real: ByteArray?): Boolean {
        val key = KEY_ORDER(real).firstOrNull { prober(file, it) == DatabaseFiles.Probe.OK }
        val opens = key != null || prober(file, null) == DatabaseFiles.Probe.OK
        if (!opens) {
            ErrorLogger.logException(
                component = TAG,
                severity = ErrorLogger.Severity.HIGH,
                exception = IllegalStateException("${file.name} opens with no key; left in place"),
                context = mapOf("phase" to "set_aside_recovery")
            )
            return false
        }
        return try {
            merge(file, key)
            rename(file)
            true
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = TAG,
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "set_aside_recovery", "file" to file.name)
            )
            false
        }
    }

    private suspend fun merge(file: File, key: ByteArray?) {
        val copy = opener(context, file.name, key)
        try {
            val source = BackupManager(
                context,
                settingsRepository,
                copy.customKeyMappingDao(),
                copy.userDictionaryDao(),
                copy.learnedWordDao(),
                copy.userWordFrequencyDao(),
                copy.userWordBigramDao(),
                blacklistRepository
            )
            val archive = ByteArrayOutputStream()
            val exported = source.export(PARTS, archive, "set-aside ${file.name}")
            check(exported.ok) { "export from the copy failed: ${exported.errors}" }
            val imported = backupManager.import(PARTS, ByteArrayInputStream(archive.toByteArray()))
            check(imported.ok) { "import into the live database failed: ${imported.errors}" }
            ErrorLogger.logException(
                component = TAG,
                severity = ErrorLogger.Severity.HIGH,
                exception = IllegalStateException("merged ${file.name}: ${imported.lines.joinToString("; ")}"),
                context = mapOf("phase" to "set_aside_recovery", "keyed" to (key != null).toString())
            )
        } finally {
            copy.close()
        }
    }

    /** `<name>.<reason>-<stamp>` → `<name>.recovered-<stamp>`, sidecars along; a failed rename is only logged. */
    private fun rename(file: File) {
        val reason = RECOVERABLE_REASONS.first { file.name.startsWith("${KeyboardDatabase.DATABASE_NAME}.$it-") }
        val target = File(file.parentFile, file.name.replaceFirst(".$reason-", ".recovered-"))
        if (!file.renameTo(target)) {
            ErrorLogger.logException(
                component = TAG,
                severity = ErrorLogger.Severity.HIGH,
                exception = IllegalStateException("cannot rename ${file.name} after recovery"),
                context = mapOf("phase" to "set_aside_recovery")
            )
            return
        }
        DatabaseFiles.SIDECARS.forEach { File(file.path + it).renameTo(File(target.path + it)) }
    }

    companion object {
        private const val TAG = "SetAsideRecovery"

        /** The reasons [DatabaseFiles.moveAside] writes that are worth a merge attempt. */
        val RECOVERABLE_REASONS: List<String> = listOf("unreadable", "corrupt", "rekeyed")

        /** The Room-backed parts — what a set-aside copy can hold that the live database lacks. */
        val PARTS: Set<BackupPart> = setOf(BackupPart.USER_DICTIONARY, BackupPart.LEARNED_WORDS, BackupPart.NEXT_WORD)

        /** Keys to try, most likely first: the zero key of every old build, then the real passphrase. */
        private val KEY_ORDER: (ByteArray?) -> List<ByteArray> = { real ->
            listOfNotNull(DatabaseFiles.LEGACY_ZERO_KEY, real)
        }
    }
}
