package com.urik.keyboard.data

import android.content.Context
import java.io.File

/**
 * Shared "snapshot the library into the git archive" step, used by BOTH the Library tab's commit pill and
 * the Keyboard editor's Commit action. Commits are always explicit (never automatic on edit — edits already
 * auto-save to the runtime store); this just mirrors the current effective set into the repo and commits it.
 *
 * Blocking (filesystem + JGit) — call from a background coroutine (Dispatchers.IO).
 */
object LibraryArchive {

    /**
     * Mirror the FULL effective layout set into `<dir>/layouts/<id>.json` + `layouts/registry.json`, rebuild
     * that directory from scratch (so deletions/renames propagate), then commit everything with [message].
     * A shadow layout writes under its stock id (`derivedFrom`), not a `_copy` name, so the archive carries
     * clean canonical ids. Returns the new commit hash, or null on failure / nothing to commit.
     */
    fun mirrorAndCommit(context: Context, dir: File, message: String): String? {
        val registry = LayoutRegistry.load(context)
        // Effective id → JSON: a shadow uses derivedFrom (the stock id); bundled / stand-alone keep theirs.
        val toWrite = registry.entries
            .mapNotNull { e -> CustomLayoutStore.rawJson(context, e.id)?.let { (e.derivedFrom ?: e.id) to it } }
            .distinctBy { it.first }
        val registryJson = try {
            context.assets.open("layouts/registry.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
        return try {
            val layoutsDir = File(dir, "layouts").apply { mkdirs() }
            layoutsDir.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { it.delete() }
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { it.delete() }
            toWrite.forEach { (id, json) -> File(layoutsDir, "$id.json").writeText(json.toString(2)) }
            registryJson?.let { File(layoutsDir, "registry.json").writeText(it) }
            GitArchive.commitAll(dir, message, "白い熊 kxkb", "kxkb@localhost")
        } catch (_: Exception) {
            null
        }
    }
}
