package com.urik.keyboard.chrome

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.urik.keyboard.R

/**
 * The colour ROLES of the app's own pages (settings, Library, editor, dialogs, menus) — never the keyboard,
 * which is themed per combo through the look knobs. Every role is settable on the kxkb UI page and none is
 * a constant in a page: the accent of a frame, the dim gold of a note, the dim yellow of a switch that is
 * off, the red of something that deletes, and the colours an icon takes from what its row does.
 *
 * Stored in DEVICE-PROTECTED preferences, like the lock-screen layout pick: the keyboard process reads the
 * roles too (its dialogs), and before first unlock nothing else is readable. Every accessor is total — an
 * unreadable store answers the defaults.
 */
object ChromeColors {
    enum class Role(val key: String, val default: Int, val labelRes: Int) {
        ACCENT("accent", 0xFFFFFF00.toInt(), R.string.chrome_role_accent),
        BACKGROUND("background", 0xFF000000.toInt(), R.string.chrome_role_background),
        TEXT("text", 0xFFFFFF00.toInt(), R.string.chrome_role_text),
        NOTE("note", 0xFFC8C800.toInt(), R.string.chrome_role_note),
        SWITCH_OFF("switch_off", 0xFF8C8C00.toInt(), R.string.chrome_role_switch_off),
        CONNECT("icon_connect", 0xFF00FF00.toInt(), R.string.chrome_role_connect),
        MESSAGE("icon_message", 0xFF4D94FF.toInt(), R.string.chrome_role_message),
        CONTACT("icon_contact", 0xFF00FFFF.toInt(), R.string.chrome_role_contact),
        SHARE("icon_share", 0xFFFF8000.toInt(), R.string.chrome_role_share),
        BLOCK("icon_block", 0xFFFF00FF.toInt(), R.string.chrome_role_block),
        DELETE("delete", 0xFFFF0000.toInt(), R.string.chrome_role_delete),
        APPLY("apply", 0xFF0000FF.toInt(), R.string.chrome_role_apply)
    }

    const val PREFS_NAME = "kxkb_chrome"
    private const val PREFIX = "role_"

    @Volatile private var values: IntArray = IntArray(Role.entries.size) { Role.entries[it].default }

    /** True once any role differs from its default — pages skip the recolouring passes while it is false. */
    @Volatile var customised: Boolean = false
        private set

    fun prefs(context: Context): SharedPreferences? = try {
        (context.applicationContext.createDeviceProtectedStorageContext() ?: context.applicationContext)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }

    /** Load the stored roles. Called once per process, and again after a role or a restore changed them. */
    fun load(context: Context) {
        val p = prefs(context)
        val next = IntArray(Role.entries.size) { i ->
            val role = Role.entries[i]
            try {
                if (p != null && p.contains(PREFIX + role.key)) p.getInt(PREFIX + role.key, role.default) else role.default
            } catch (_: Throwable) {
                role.default
            }
        }
        values = next
        customised = Role.entries.indices.any { next[it] != Role.entries[it].default }
    }

    operator fun get(role: Role): Int = values[role.ordinal]

    fun isSet(context: Context, role: Role): Boolean =
        try { prefs(context)?.contains(PREFIX + role.key) == true } catch (_: Throwable) { false }

    /** Store [color] for [role]; null puts the default back. */
    fun set(context: Context, role: Role, color: Int?) {
        try {
            val e = prefs(context)?.edit() ?: return
            if (color == null) e.remove(PREFIX + role.key) else e.putInt(PREFIX + role.key, color)
            e.commit()
        } catch (_: Throwable) {
        }
        load(context)
    }

    val accent: Int get() = this[Role.ACCENT]
    val background: Int get() = this[Role.BACKGROUND]
    val text: Int get() = this[Role.TEXT]
    val note: Int get() = this[Role.NOTE]
    val switchOff: Int get() = this[Role.SWITCH_OFF]
    val delete: Int get() = this[Role.DELETE]

    /** The clear line between groups: 70 % of the way from the background to the accent, opaque. */
    val divider: Int get() = blend(background, accent, 0.7f)

    /** [color] as a disabled control shows it (38 %). */
    fun disabled(color: Int): Int = withAlpha(color, 0.38f)

    fun ripple(color: Int): Int = withAlpha(color, 0.30f)

    fun withAlpha(color: Int, fraction: Float): Int =
        Color.argb((Color.alpha(color) * fraction).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
        Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
        Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t)
    )
}
