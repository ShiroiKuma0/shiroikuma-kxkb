package com.urik.keyboard.settings

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 *
 * It is also where the way BACK is decided (contract §8). The 言語島 board finishes itself when a tile
 * hands its task over, so there is no board left to reveal: the caller names the task that opens it
 * again, and that name — the one thing this door reads — must arrive at the review intact.
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
    fun `the task to come back by is carried through to the review`() {
        val from = Intent(action).putExtra("return_task", "言語島 -- [227]")

        val controller = Robolectric.buildActivity(WalkCaptureEntryActivity::class.java, from).create()

        val next = shadowOf(controller.get()).nextStartedActivity
        assertEquals("言語島 -- [227]", next.getStringExtra(SettingsActivity.EXTRA_RETURN_TASK))
    }

    @Test
    fun `a caller that names no task still returns through this door`() {
        // §8a: coming through here is itself the way back — 自由作業盤's door to the 言語島 board. The
        // space-slide menu's own entry to the page carries neither mark and simply closes.
        val controller = Robolectric
            .buildActivity(WalkCaptureEntryActivity::class.java, Intent(action))
            .create()

        val next = shadowOf(controller.get()).nextStartedActivity
        assertNull(next.getStringExtra(SettingsActivity.EXTRA_RETURN_TASK))
        assertTrue(next.getBooleanExtra(SettingsActivity.EXTRA_THROUGH_THE_DOOR, false))
    }

    @Test
    fun `a blank or oversized task name is no task name`() {
        val blank = Intent(action).putExtra("return_task", "   ")
        val huge = Intent(action).putExtra("return_task", "x".repeat(500))

        for (sent in listOf(blank, huge)) {
            val controller = Robolectric.buildActivity(WalkCaptureEntryActivity::class.java, sent).create()
            val next = shadowOf(controller.get()).nextStartedActivity
            assertNull(next.getStringExtra(SettingsActivity.EXTRA_RETURN_TASK))
        }
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
