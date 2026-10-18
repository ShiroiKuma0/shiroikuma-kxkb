package com.urik.keyboard.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The one exported way into the walk-capture review, so a 自由作業盤 task can put 白い熊 on that page in a
 * single tap: `action = shiroikuma.kxkb.action.OPEN_WALK_CAPTURE`, `package = shiroikuma.kxkb`.
 *
 * A trampoline rather than exporting [SettingsActivity] or an alias of it: this door can open exactly one
 * page, so exporting it cannot be talked into opening some other part of the settings. No data crosses it
 * either — the review itself stays behind it, in our own UI, where only 白い熊 can act on it.
 *
 * It adds `CLEAR_TOP` itself, so a second tap lands on the page rather than on whatever the settings stack
 * was left showing (the page is chosen in `onCreate`, so the Activity must be recreated to re-navigate).
 *
 * A plain [Activity] and not an `AppCompatActivity`: it is declared with `Theme.NoDisplay`, which AppCompat
 * refuses to inflate — and a door with no UI needs nothing AppCompat provides.
 */
class WalkCaptureEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val open = SettingsActivity
            .createIntent(this, SettingsActivity.PAGE_WALK_CAPTURE)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Coming through THIS door is itself the way back: it is 自由作業盤's door, and that app has
            // one of its own to the 言語島 board (contract §8a). A named task still wins over it.
            .putExtra(SettingsActivity.EXTRA_THROUGH_THE_DOOR, true)
        returnTask()?.let { open.putExtra(SettingsActivity.EXTRA_RETURN_TASK, it) }
        startActivity(open)
        finish()
    }

    /**
     * The ONE thing this door reads from its caller (contract §8): the name of a 自由作業盤 task to run
     * when 白い熊 leaves the review — the task that opens the 言語島 board again.
     *
     * Optional, because 自由作業盤 now has a door to the board itself and that is the default; a task
     * named here is for a caller that wants somewhere else. The board
     * (`com.opentasker.ui.gengoshima.GengoshimaBoardActivity`) **finishes itself** the moment a tile hands
     * its task over — "two stacked windows is one too many", which is right for a tile that opens a window
     * of 自由作業盤's own — so by the time this review appears there is no board left anywhere to return
     * to. No flag on either side can bring back an activity that no longer exists: it must be opened
     * again, and it is not exported, so only 自由作業盤 itself can do it.
     *
     * Reading this changes nothing about what the door can be aimed at — the page is still fixed, and the
     * string is never acted on here. It is handed to 自由作業盤's own exported run-a-task trampoline, which
     * every app on the phone may already call with any task name it likes.
     */
    private fun returnTask(): String? = runCatching {
        intent?.getStringExtra(EXTRA_RETURN_TASK)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
    }.getOrNull()

    private companion object {
        /** Contract §8 — the caller's extra. Deliberately short: 白い熊 types it into a task by hand. */
        private const val EXTRA_RETURN_TASK = "return_task"

        /** A task name, not a payload. */
        private const val MAX_NAME = 200
    }
}
