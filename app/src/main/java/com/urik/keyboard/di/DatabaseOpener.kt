package com.urik.keyboard.di

import android.content.Context
import com.urik.keyboard.data.database.KeyboardDatabase

/** The one seam between `DatabaseModule`'s decisions and Room's builder — swapped by the tests. */
interface DatabaseOpener {
    /** Build the Room instance on the real file; `passphrase` null means a plain (unencrypted) database. */
    fun open(context: Context, passphrase: ByteArray?): KeyboardDatabase
}
