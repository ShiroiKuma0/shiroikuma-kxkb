package com.urik.keyboard.ui.keyboard.components

/** One selectable item in the space-slide menu (a label + the action to run when released on it). */
class SpaceMenuItem(val label: String, val current: Boolean, val onSelect: () -> Unit)

/** One column of the space-slide menu: a header and its items, stacked top to bottom. */
class SpaceMenuColumn(val header: String, val items: List<SpaceMenuItem>)
