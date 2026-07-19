package com.urik.keyboard.ui.keyboard.components

/**
 * One selectable item in the space-slide menu (a label + the action to run when released on it).
 * An item with [overflow] is a "…" spill trigger: highlighting it swaps the middle column for the
 * overflow items (used when more layouts are active than fit the Layouts column); releasing on the
 * trigger itself does nothing.
 */
class SpaceMenuItem(
    val label: String,
    val current: Boolean,
    val overflow: List<SpaceMenuItem>? = null,
    val onSelect: () -> Unit
)

/** One column of the space-slide menu: a header and its items, stacked top to bottom. */
class SpaceMenuColumn(val header: String, val items: List<SpaceMenuItem>)
