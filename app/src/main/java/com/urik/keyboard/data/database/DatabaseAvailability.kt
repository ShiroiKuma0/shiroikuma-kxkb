package com.urik.keyboard.data.database

import androidx.annotation.VisibleForTesting

/**
 * Whether the injected [KeyboardDatabase] is the REAL, on-disk store or a throwaway in-memory stand-in.
 *
 * The stand-in exists so the keyboard can always come up — before first unlock, or when the database's
 * passphrase cannot be decrypted at process start (see `DatabaseModule`). It types fine, but nothing written
 * to it survives the process, and everything READ from it is empty. Anything that would present its contents
 * as the user's data — the backup export above all — must ask here first: on 2026-09-03 a batch backup taken
 * against an empty store produced a valid-looking archive with no words in it, and that archive was later
 * restored onto a new phone in good faith.
 */
object DatabaseAvailability {
    enum class Mode {
        /** The encrypted (or, without a lock screen, plain) database file. */
        REAL,

        /** Direct Boot: credential-protected storage is locked; the process restarts itself on unlock. */
        BEFORE_FIRST_UNLOCK,

        /** The stored passphrase could not be decrypted at this process start; a restart while unlocked heals. */
        PASSPHRASE_UNAVAILABLE
    }

    @Volatile
    var mode: Mode = Mode.REAL
        private set

    /** A short human reason for a non-[Mode.REAL] mode, for logs and the export/import page. */
    @Volatile
    var detail: String = ""
        private set

    val isReal: Boolean get() = mode == Mode.REAL

    /** True when ending this process while the device is unlocked would bring the real store back. */
    val restartWhenUnlockedHeals: Boolean get() = mode == Mode.PASSPHRASE_UNAVAILABLE

    fun real() {
        mode = Mode.REAL
        detail = ""
    }

    fun fallback(mode: Mode, detail: String) {
        require(mode != Mode.REAL) { "fallback() takes a stand-in mode" }
        this.mode = mode
        this.detail = detail
    }

    @VisibleForTesting
    fun resetForTesting() = real()
}

/** Thrown by the backup engine for a part whose store is the in-memory stand-in — never exported as empty. */
class DatabaseUnavailableException(detail: String) :
    IllegalStateException("word database unavailable: $detail")
