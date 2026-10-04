package com.urik.keyboard.settings

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The one exported door into our UI. It must open the walk-capture page and only ever that page: the
 * reason for a trampoline instead of exporting [SettingsActivity] is that a caller cannot name the
 * destination, so a tap from 自由作業盤 can never be aimed at another part of the settings.
 */
@RunWith(RobolectricTestRunner::class)
class WalkCaptureEntryActivityTest {
    private val action = "shiroikuma.kxkb.action.OPEN_WALK_CAPTURE"

    @Test
    fun `the door opens the walk-capture page and closes itself`() {
        val controller = Robolectric
            .buildActivity(WalkCaptureEntryActivity::class.java, Intent(action))
            .create()

        val next = shadowOf(controller.get()).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, next.component?.className)
        assertEquals(
            SettingsActivity.PAGE_WALK_CAPTURE,
            next.getStringExtra(SettingsActivity.EXTRA_OPEN_PAGE)
        )
        // CLEAR_TOP, so a second tap re-navigates instead of resuming a stale settings stack.
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `a caller cannot aim it at another page`() {
        val hostile = Intent(action)
            .putExtra(SettingsActivity.EXTRA_OPEN_PAGE, SettingsActivity.PAGE_KEYBOARD_UI)
            .putExtra("anything_else", "ignored")

        val controller = Robolectric.buildActivity(WalkCaptureEntryActivity::class.java, hostile).create()

        val next = shadowOf(controller.get()).nextStartedActivity
        assertEquals(
            SettingsActivity.PAGE_WALK_CAPTURE,
            next.getStringExtra(SettingsActivity.EXTRA_OPEN_PAGE)
        )
    }
}
