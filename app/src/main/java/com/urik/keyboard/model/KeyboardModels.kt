package com.urik.keyboard.model

enum class KeyboardMode {
    LETTERS,
    NUMBERS,
    SYMBOLS,
    SYMBOLS_SECONDARY
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
    val showFlickHints: Boolean = false
)

sealed class KeyboardKey {
    /** @property value Can be multi-char for ligatures/emoji */
    data class Character(val value: String, val type: KeyType) : KeyboardKey()

    data class Action(val action: ActionType) : KeyboardKey()

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
         * The face shown and committed when shift / caps-lock is active (a FUTO CaseSelector's shifted
         * variant — e.g. uppercase, or hiragana→katakana). Null = no explicit shifted face (the renderer
         * falls back to uppercasing for bicameral scripts).
         */
        val shifted: FlickKey? = null
    ) : KeyboardKey()

    enum class KeyType {
        LETTER,
        NUMBER,
        PUNCTUATION,
        SYMBOL
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

sealed class KeyboardEvent {
    data class KeyPressed(val key: KeyboardKey) : KeyboardEvent()

    data class ModeChanged(val mode: KeyboardMode) : KeyboardEvent()

    data class ShiftStateChanged(val isPressed: Boolean) : KeyboardEvent()

    data object CapsLockToggled : KeyboardEvent()
}
