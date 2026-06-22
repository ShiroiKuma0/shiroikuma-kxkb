package com.urik.keyboard

import android.content.Context
import android.os.UserManager
import org.robolectric.Shadows

/**
 * Robolectric defaults the device to LOCKED (`isUserUnlocked == false`). The production storage guards
 * (DB / DataStore / CustomLayoutStore / ErrorLogger) bail to a safe degraded path while locked, so any test
 * that exercises REAL credential-protected storage must first mark the device unlocked.
 */
fun Context.markUserUnlocked() {
    Shadows.shadowOf(getSystemService(UserManager::class.java)).setUserUnlocked(true)
}
