package com.urik.keyboard.model

enum class KeyboardMode {
    LETTERS,
    NUMBERS,
    SYMBOLS,
    SYMBOLS_SECONDARY,

    /**
     * The Multiling-style dedicated number pad (a 6×4 calculator grid: 1-9/0 with math operators and
     * punctuation). A fourth alt page reached via the `alt3` layer / [KeyboardKey.ActionType.MODE_SWITCH_NUMPAD],
     * distinct from the legacy symbol-ish [NUMBERS] page (`alt0`). Its design is a fixed shared layout
     * supplied by the repository fallback, so layouts need not each declare a `numpad` section.
     */
    NUMPAD
}

/**
 * Current keyboard UI state.
 *
 * Drives layout rendering and key behavior (e.g., shift affects character case).
 */
data class KeyboardState(
    val currentMode: KeyboardMode = KeyboardMode.LETTERS,
    val isShiftPressed: Boolean = false,
    val isCapsLockOn: Boolean = false,
    val isAutoShift: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * Layout structure for a specific keyboard mode.
 *
 * Each row contains keys rendered left-to-right or right-to-left based on isRTL.
 */
data class KeyboardLayout(
    val mode: KeyboardMode,
    val rows: List<List<KeyboardKey>>,
    val isRTL: Boolean = false,
    val script: String = "Latn",
    /** Draw each flick key's direction labels at rest (compass layouts like GNU); off for the JP 12-key. */
    val showFlickHints: Boolean = false,
    /**
     * A hardware-keyboard keymap: when this layout is active, a physical keyboard's keys are remapped to this
     * grid by position (row/col). Each single-character key emits its char (shifted face when Shift/Caps is
     * held); multi-char / action / function keys pass through unchanged. Off for normal on-screen layouts.
     */
    val hardwareKeymap: Boolean = false,
    /**
     * The registry id this layout was loaded as (e.g. "gnu_nexdock_xl", "cs_3p2_5r9c"), set by
     * KeyboardRepository when loading. Lets the IME key the per-(app·layout·geometry) size override by the live
     * layout SYNCHRONOUSLY (no async id resolution on the show path). "" when unknown.
     */
    val id: String = "",
    /**
     * The LETTERS page's row count for this layout (set on every mode's load), so the height clamp resolves to
     * the SAME per-key height on every page — symbols/alt/numpad then normalise to the letters height instead
     * of each page being clamped to its own row count. 0 when unknown.
     */
    val referenceRows: Int = 0
)

sealed class KeyboardKey {
    /**
     * Explicit column width in "cells" (1 = a standard key). 0 = unspecified → the renderer's heuristic
     * decides. Imported layouts set this on the bottom bar so it aligns to the grid (e.g. space = 3 cells).
     */
    open val width: Float get() = 0f

    /** Per-key visual overrides (futokxkb's per-key appearance); null on a key = inherit from theme/look. */
    open val appearance: KeyAppearance? get() = null

    /** Per-key behaviour/sizing attributes (futokxkb's per-key attributes); null = inherit defaults. */
    open val attributes: KeyAttributes? get() = null

    /** @property value Can be multi-char for ligatures/emoji */
    data class Character(
        val value: String,
        val type: KeyType,
        override val width: Float = 0f,
        override val appearance: KeyAppearance? = null,
        override val attributes: KeyAttributes? = null
    ) : KeyboardKey()

    data class Action(
        val action: ActionType,
        override val width: Float = 0f,
        override val appearance: KeyAppearance? = null,
        override val attributes: KeyAttributes? = null
    ) : KeyboardKey()

    data object Spacer : KeyboardKey()

    /**
     * Kana key with directional flick variants for 12-key Japanese input.
     *
     * [center] is committed on tap. Null directions indicate no character for that flick direction.
     */
    data class FlickKey(
        val center: String,
        val up: String?,
        val right: String?,
        val down: String?,
        val left: String?,
        val type: KeyType,
        val upLeft: String? = null,
        val upRight: String? = null,
        val downLeft: String? = null,
        val downRight: String? = null,
        /**
         * Non-text bindings for compass positions, keyed by position name
         * ("center"/"up"/"down"/"left"/"right"/"upLeft"/"upRight"/"downLeft"/"downRight").
         * A position absent here commits its text field directly (the Japanese 12-key uses none).
         */
        val bindings: Map<String, FlickBinding> = emptyMap(),
        /**
         * Cluster keys: the full ordered band of main characters (e.g. "mwk", "ioaev") drawn as PRIMARY
         * glyphs spread across the key's centre line. Empty = an ordinary flick key. [center] is the tap
         * commit (the middle of the band); the neighbours also ride the left/right slides.
         */
        val clusterMains: String = "",
        /**
         * The face shown and committed when shift / caps-lock is active (a futokxkb `case` key's shifted
         * variant — e.g. uppercase, or hiragana→katakana). Null = no explicit shifted face (the renderer
         * falls back to uppercasing for bicameral scripts). For a multi-state `case` this is the generic
         * shifted face (derived from the most specific present state) and [caseFaces] holds the rest.
         */
        val shifted: FlickKey? = null,
        /**
         * A futokxkb `case` (CaseSelector) carries a distinct face per shift state, keyed by
         * "shifted" (auto-shift) / "shiftedManually" (one-shot Shift) / "shiftLocked" (caps-lock) /
         * "symbols" / "symbolsShifted". Empty for an ordinary key (then [shifted] alone is consulted).
         * The renderer ([KeyboardLayoutManager.flickFace]) selects the face matching the current state.
         */
        val caseFaces: Map<String, FlickKey> = emptyMap(),
        /**
         * A futokxkb `column` key: the [clusterMains] band is stacked VERTICALLY (instead of the cluster's
         * horizontal row), and the band ends ride the up/down slides instead of left/right. Prediction and
         * the centre-tap commit are shared with cluster keys.
         */
        val columnar: Boolean = false,
        /**
         * Tap behaviour for the futokxkb non-directional types carried on a FlickKey (jargon outward, one
         * internal type): MACRO commits [center] literally, CHORD fires its `center` chord binding, CYCLE
         * steps through [cycleTaps] on repeated taps. NORMAL = an ordinary compass/cluster/column key.
         */
        val tapKind: TapKind = TapKind.NORMAL,
        /** Ordered entries a CYCLE key steps through on repeated taps (delete previous, commit next, wrap). */
        val cycleTaps: List<String> = emptyList(),
        override val appearance: KeyAppearance? = null,
        override val attributes: KeyAttributes? = null,
        override val width: Float = 0f
    ) : KeyboardKey()

    enum class KeyType {
        LETTER,
        NUMBER,
        PUNCTUATION,
        SYMBOL
    }

    /** Tap behaviour of a FlickKey carrying a futokxkb non-directional type (macro/chord/cycle). */
    enum class TapKind {
        NORMAL,
        MACRO,
        CHORD,
        CYCLE
    }

    enum class ActionType {
        SHIFT,
        BACKSPACE,
        SPACE,
        ENTER,
        SEARCH,
        SEND,
        DONE,
        GO,
        NEXT,
        PREVIOUS,
        MODE_SWITCH_LETTERS,
        MODE_SWITCH_NUMBERS,
        MODE_SWITCH_SYMBOLS,
        MODE_SWITCH_SYMBOLS_SECONDARY,
        MODE_SWITCH_NUMPAD,
        CAPS_LOCK,
        LANGUAGE_SWITCH,
        DAKUTEN,
        SMALL_KANA,
        NEXT_CANDIDATE,
        COMMIT_CANDIDATE,
        HANDAKUTEN,
        EMOJI,
        TAB
    }

    /**
     * A non-text binding for a compass key position (used by the GNU compass layout).
     * Plain text/macro positions need no binding — their string field is committed directly.
     */
    sealed class FlickBinding {
        /** A built-in editor action resolved by name, e.g. "escape", "tab", "arrow_up", "undo". */
        data class Action(val name: String) : FlickBinding()

        /** A modifier chord sent as a key event with meta state, e.g. "C-c", "M-x", "S-TAB". */
        data class Chord(val spec: String) : FlickBinding()

        /** Switch to another layer/mode, e.g. "alt0", "alpha0". */
        data class Layer(val target: String) : FlickBinding()
    }
}

/**
 * Per-key visual overrides (futokxkb's per-key appearance), all nullable = inherit from the theme / look
 * knobs. Colours are ARGB ints; scales are multipliers on the resolved size; offsets are fractions of the
 * key dimension. [color]/[fontScale]/[backgroundColor]/[borderColor] are rendered today; the offset fields
 * are carried losslessly through the codec and rendered as the editor surfaces them.
 */
data class KeyAppearance(
    val color: Int? = null,
    val fontScale: Float? = null,
    val hintScale: Float? = null,
    val backgroundColor: Int? = null,
    val borderColor: Int? = null,
    val labelOffsetX: Float? = null,
    val labelOffsetY: Float? = null,
    val clusterLeftOffset: Float? = null,
    val clusterRightOffset: Float? = null,
    val flickTopOffset: Float? = null,
    val flickBottomOffset: Float? = null,
    val flickLeftOffset: Float? = null,
    val flickRightOffset: Float? = null
)

/**
 * Per-key behaviour/sizing attributes (futokxkb's per-key attributes), all nullable = inherit. The numeric
 * [KeyboardKey.width] is the renderer's width driver; [widthClass] just preserves the futokxkb named width
 * (Regular/FunctionalKey/Grow/Custom1–4) for the editor + round-trip (the converter resolves it to cells at
 * import). [shiftable] = false opts a cluster/column out of auto-uppercasing; the remaining FUTO-specific
 * fields are carried losslessly and surfaced by the editor even where Urik doesn't act on them yet.
 */
data class KeyAttributes(
    val widthClass: String? = null,
    val style: String? = null,
    val moreKeyMode: String? = null,
    val heightRows: Float? = null,
    val showPopup: Boolean? = null,
    val longPressEnabled: Boolean? = null,
    val repeatableEnabled: Boolean? = null,
    val anchored: Boolean? = null,
    val useKeySpecShortcut: Boolean? = null,
    val shiftable: Boolean? = null,
    val fastMoreKeys: Boolean? = null
)

sealed class KeyboardEvent {
    data class KeyPressed(val key: KeyboardKey) : KeyboardEvent()

    data class ModeChanged(val mode: KeyboardMode) : KeyboardEvent()

    data class ShiftStateChanged(val isPressed: Boolean) : KeyboardEvent()

    data object CapsLockToggled : KeyboardEvent()
}
