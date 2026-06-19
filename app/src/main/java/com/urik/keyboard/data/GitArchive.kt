package com.urik.keyboard.data

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

/**
 * In-app JGit wrapper for the Library's optional **git archive** — a real git repository at a user-set real
 * path (All-Files-Access). It is the archival mirror + curation workbench, used ONLY from the Library tab:
 * never touched on the keyboard hot path or at boot. Repo unset → the Library shows the internal store only.
 *
 * All calls do blocking filesystem/git work — invoke from a background coroutine (Dispatchers.IO).
 */
object GitArchive {

    /** True when [dir] is itself a git working tree (has a `.git`), so we open rather than init. */
    fun isRepo(dir: File): Boolean =
        try {
            File(dir, ".git").let { it.isDirectory || it.isFile } ||
                FileRepositoryBuilder().setWorkTree(dir).setMustExist(true).build().use { true }
        } catch (_: Exception) {
            false
        }

    /** Initialise a new repository at [dir] when there isn't one yet (idempotent). */
    fun initIfNeeded(dir: File) {
        if (isRepo(dir)) return
        dir.mkdirs()
        Git.init().setDirectory(dir).call().close()
    }

    /** Every `*.json` file in the working tree (the archived layouts), excluding the `.git` directory. */
    fun listLayoutFiles(dir: File): List<File> =
        dir.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .sortedBy { it.relativeTo(dir).path }
            .toList()

    /** Stage everything and commit; returns the new commit hash, or null on failure / nothing to commit. */
    fun commitAll(dir: File, message: String, authorName: String, authorEmail: String): String? =
        try {
            Git.open(dir).use { git ->
                git.add().addFilepattern(".").call()
                if (git.status().call().isClean) {
                    null
                } else {
                    git.commit()
                        .setAll(true)
                        .setMessage(message)
                        .setAuthor(authorName, authorEmail)
                        .call()
                        .name
                }
            }
        } catch (_: Exception) {
            null
        }

    /** Working-tree paths with uncommitted or untracked changes (for a Library "dirty" indicator). */
    fun pendingChanges(dir: File): Set<String> =
        try {
            Git.open(dir).use { git ->
                val s = git.status().call()
                s.uncommittedChanges + s.untracked + s.missing
            }
        } catch (_: Exception) {
            emptySet()
        }
}
