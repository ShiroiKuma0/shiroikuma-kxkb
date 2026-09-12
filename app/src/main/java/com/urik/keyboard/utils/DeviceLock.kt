package com.urik.keyboard.utils

import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager

/**
 * Whether the user has unlocked the device since boot — i.e. credential-protected storage is available.
 *
 * `false` during Direct Boot / Before-First-Unlock (BFU): DataStore, the Room database and `filesDir` are all
 * LOCKED and any access throws (which, on the lock screen, could brick the keyboard). EVERY storage touch on a
 * code path that can run before unlock must be guarded by this. `isUserUnlocked` (API 24+) is reliable; the
 * catch only guards a theoretical failure, and falls back to `false` (the SAFE side — the degraded, no-storage
 * path) so a quirk can never push us into a storage access that crashes the IME on the lock screen.
 */
val Context.isUserUnlocked: Boolean
    get() = try {
        (getSystemService(Context.USER_SERVICE) as? UserManager)?.isUserUnlocked ?: false
    } catch (_: Throwable) {
        false
    }

/**
 * Whether the device is currently LOCKED (keyguard up, credential required) — distinct from
 * [isUserUnlocked], which is about the FIRST unlock since boot and stays true across later screen locks.
 * `false` on a device with no secure lock screen and on any failure to ask.
 */
val Context.isDeviceLocked: Boolean
    get() = try {
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked ?: false
    } catch (_: Throwable) {
        false
    }
