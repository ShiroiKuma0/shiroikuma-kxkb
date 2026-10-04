package com.urik.keyboard.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The one exported way into the walk-capture review, so a 自由作業盤 task can put 白い熊 on that page in a
 * single tap: `action = shiroikuma.kxkb.action.OPEN_WALK_CAPTURE`, `package = shiroikuma.kxkb`.
 *
 * A trampoline rather than exporting [SettingsActivity] or an alias of it: this door takes no extras, reads
 * nothing from the caller and can open exactly one page, so exporting it cannot be talked into opening some
 * other part of the settings. No data crosses it either — the review itself stays behind it, in our own UI,
 * where only 白い熊 can act on it.
 *
 * It adds `CLEAR_TOP` itself, so a second tap lands on the page rather than on whatever the settings stack
 * was left showing (the page is chosen in `onCreate`, so the Activity must be recreated to re-navigate).
 * The caller's own `NEW_TASK` is what keeps Back returning to where 白い熊 was.
 *
 * A plain [Activity] and not an `AppCompatActivity`: it is declared with `Theme.NoDisplay`, which AppCompat
 * refuses to inflate — and a door with no UI needs nothing AppCompat provides.
 */
class WalkCaptureEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            SettingsActivity
                .createIntent(this, SettingsActivity.PAGE_WALK_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
