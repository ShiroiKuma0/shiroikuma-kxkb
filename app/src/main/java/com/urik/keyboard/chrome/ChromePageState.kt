package com.urik.keyboard.chrome

import android.content.Context

/**
 * The arrangement of a UI page: the order of its sections and which of them are open. Kept beside the
 * colour roles in the chrome preferences, so both travel in a backup. Sections are FOLDED by default — a
 * section is open only while its key is in the open set — and a section a later build adds appears at its
 * built place without disturbing a saved order.
 */
object ChromePageState {
    private fun orderKey(page: String) = "order_$page"
    private fun openKey(page: String) = "open_$page"

    fun order(context: Context, page: String, built: List<String>): List<String> {
        val saved = try { ChromeColors.prefs(context)?.getString(orderKey(page), null) } catch (_: Throwable) { null }
        return merge(saved?.split(",").orEmpty(), built)
    }

    /** Stored keys still present, in their saved order; every other built key goes in at its built place. */
    fun merge(saved: List<String>, built: List<String>): List<String> {
        val kept = saved.filter { it in built }.distinct()
        if (kept.isEmpty()) return built
        val result = kept.toMutableList()
        built.forEachIndexed { i, k ->
            if (k !in result) {
                // Put a new section after the nearest built predecessor that is already placed.
                val before = built.take(i).lastOrNull { it in result }
                result.add(if (before == null) 0 else result.indexOf(before) + 1, k)
            }
        }
        return result
    }

    fun setOrder(context: Context, page: String, order: List<String>) {
        try { ChromeColors.prefs(context)?.edit()?.putString(orderKey(page), order.joinToString(","))?.commit() } catch (_: Throwable) {}
    }

    fun resetOrder(context: Context, page: String) {
        try { ChromeColors.prefs(context)?.edit()?.remove(orderKey(page))?.commit() } catch (_: Throwable) {}
    }

    private fun openSet(context: Context, page: String): Set<String> = try {
        ChromeColors.prefs(context)?.getString(openKey(page), null)?.split(",")?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    } catch (_: Throwable) {
        emptySet()
    }

    fun isOpen(context: Context, page: String, key: String): Boolean = key in openSet(context, page)

    fun setOpen(context: Context, page: String, key: String, open: Boolean) {
        val set = openSet(context, page).toMutableSet()
        if (open) set.add(key) else set.remove(key)
        try { ChromeColors.prefs(context)?.edit()?.putString(openKey(page), set.joinToString(","))?.commit() } catch (_: Throwable) {}
    }
}
