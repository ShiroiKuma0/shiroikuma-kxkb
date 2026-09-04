package com.urik.keyboard.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Which layout the keyboard shows BEFORE FIRST UNLOCK (Direct Boot / BFU), stored in DEVICE-PROTECTED
 * storage — the one store readable on the lock screen (DataStore, Room and `filesDir` are all locked there,
 * which is why the BFU layout used to be a hardcoded constant). Read synchronously on the show path, exactly
 * like the cold-start window-height cache in `UrikInputMethodService`.
 *
 * Every accessor is total: a missing store, a garbled value or an id that no longer resolves to a bundled
 * asset all fall back to [DEFAULT_LAYOUT_ID]. A throw here would leave the lock screen without a keyboard,
 * so nothing in this file may propagate one.
 *
 * Only BUNDLED (asset) layouts can be picked — the user's custom copies live in credential-protected
 * `filesDir` and are unreadable while locked (see [CustomLayoutStore]).
 */
object BfuLayoutPrefs {
    /** The hard fallback: the no-prediction GNU QWERTY 10c board, always present in the APK assets. */
    const val DEFAULT_LAYOUT_ID = "gnu_qwerty_5r10c"

    private const val PREFS_NAME = "kb_bfu"
    private const val KEY_LAYOUT_ID = "bfu_layout_id"

    /** Registry ids are `[a-z0-9_]` — anything else is garbage (or a path escape) and is ignored. */
    private val VALID_ID = Regex("[a-z0-9_]{1,64}")

    /** The picked layout id, or [DEFAULT_LAYOUT_ID] when unset/unreadable/malformed. */
    fun layoutId(context: Context): String = try {
        prefs(context)?.getString(KEY_LAYOUT_ID, null)?.takeIf { VALID_ID.matches(it) } ?: DEFAULT_LAYOUT_ID
    } catch (_: Throwable) {
        DEFAULT_LAYOUT_ID
    }

    /**
     * Store the pick (written from the settings UI, and from a backup restore). A blank/invalid id clears it.
     *
     * **`commit()`, not `apply()` — deliberately.** This is the one lazily-flushed write on the restore path:
     * every other store a restore touches is durable by the time its call returns (DataStore commits through a
     * temp file and rename, Room commits its transaction, the layout stores write real files). `apply()` only
     * schedules the disk write, and 応用管理 **force-stops this app with a SIGKILL the instant an import
     * reports success** — which is correct on its side, because a process shutting down orderly would write
     * its cached preferences back out and silently undo the import. A SIGKILL bypasses the `QueuedWork` flush
     * that would otherwise save us, so an `apply()` in flight is simply lost and the restore reports success
     * over a lock-screen keyboard that quietly fell back to the default. It is one short string; the
     * synchronous write costs nothing worth having.
     */
    fun setLayoutId(context: Context, id: String) {
        try {
            val editor = prefs(context)?.edit() ?: return
            if (VALID_ID.matches(id)) editor.putString(KEY_LAYOUT_ID, id) else editor.remove(KEY_LAYOUT_ID)
            editor.commit()
        } catch (_: Throwable) {
            // A failed write just leaves the previous (or default) layout in place — never fatal.
        }
    }

    private fun prefs(context: Context): SharedPreferences? = try {
        (context.createDeviceProtectedStorageContext() ?: context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }
}
