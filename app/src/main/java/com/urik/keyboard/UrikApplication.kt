package com.urik.keyboard

import android.app.Application
import android.database.sqlite.SQLiteDatabaseCorruptException
import com.urik.keyboard.data.database.DatabaseFiles
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.SetAsideRecovery
import com.urik.keyboard.di.ApplicationScope
import com.urik.keyboard.service.ClipboardMonitorService
import com.urik.keyboard.settings.SettingsRepository
import com.urik.keyboard.utils.ErrorLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/**
 * SQLCipher is loaded before any database access (must precede Room init).
 * Clipboard monitoring runs independently of keyboard visibility for complete capture.
 * Exception handler logs to local file only — no user data captured.
 */
@HiltAndroidApp
class UrikApplication : Application() {
    @Inject
    lateinit var clipboardMonitorService: ClipboardMonitorService

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var setAsideRecovery: SetAsideRecovery

    override fun onCreate() {
        // Both BEFORE super.onCreate(): that is where Hilt injects this class's fields, and the first thing the
        // graph builds is the database — whose eager probe (DatabaseModule) opens the file through SQLCipher and
        // logs its verdict. Loading the native library afterwards, as before 2026-09-11, was only ever safe
        // because Room opened lazily; it now crashes at launch with UnsatisfiedLinkError.
        ErrorLogger.init(applicationContext)
        loadSqlCipher()

        super.onCreate()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            ErrorLogger.logException(
                component = "Application",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = throwable,
                context = mapOf("thread" to thread.name)
            )

            if (isDatabaseCorruptionException(throwable)) {
                recoverFromDatabaseCorruption()
            }

            previousHandler?.uncaughtException(thread, throwable)
        }

        observeClipboardSettings()
        recoverSetAsideDatabases()
    }

    /** Merge back any database copy an earlier start set aside — see [SetAsideRecovery]. */
    private fun recoverSetAsideDatabases() {
        applicationScope.launch {
            try {
                setAsideRecovery.run()
            } catch (e: Exception) {
                ErrorLogger.logException(
                    component = "Application",
                    severity = ErrorLogger.Severity.CRITICAL,
                    exception = e,
                    context = mapOf("phase" to "set_aside_recovery")
                )
            }
        }
    }

    private fun loadSqlCipher() {
        val isTestEnvironment =
            try {
                Class.forName("org.robolectric.RobolectricTestRunner")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        if (isTestEnvironment) return
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            ErrorLogger.logException(
                component = "UrikApplication",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "sqlcipher_load")
            )
            throw e
        }
    }

    /**
     * Corruption that surfaces as an uncaught exception (a query on a background coroutine) ends the process
     * anyway; before it does, the file is SET ASIDE — never deleted — so the next launch creates a fresh
     * database while the old bytes stay for a manual rescue. `DatabaseModule`'s eager probe makes this rare:
     * a file that cannot be read with our key is judged before Room ever opens it.
     */
    private fun isDatabaseCorruptionException(throwable: Throwable): Boolean = generateSequence(throwable) { it.cause }
        .any { it is SQLiteNotADatabaseException || it is SQLiteDatabaseCorruptException }

    private fun recoverFromDatabaseCorruption() {
        try {
            KeyboardDatabase.resetInstance()
            DatabaseFiles.moveAside(applicationContext, "corrupt")
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "Application",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("phase" to "uncaught_corruption_recovery_failed")
            )
        }
    }

    private fun observeClipboardSettings() {
        applicationScope.launch {
            settingsRepository.settings
                .map { it.isClipboardFullyActive }
                .distinctUntilChanged()
                .collect { fullyActive ->
                    if (fullyActive) {
                        clipboardMonitorService.startMonitoring()
                    } else {
                        clipboardMonitorService.stopMonitoring()
                    }
                }
        }
    }
}
