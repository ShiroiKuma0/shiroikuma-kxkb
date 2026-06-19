package com.urik.keyboard.data

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

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

    /**
     * Every layout `*.json` in the working tree (excluding the `.git` directory and the `registry.json`
     * manifest, which is metadata, not an importable layout).
     */
    fun listLayoutFiles(dir: File): List<File> =
        dir.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter {
                it.isFile && it.extension.equals("json", ignoreCase = true) &&
                    !it.name.equals("registry.json", ignoreCase = true)
            }
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

    // --- HTTPS remote ops (Library-tab-only, blocking; call from Dispatchers.IO) -------------------------
    // Auth uses a UsernamePasswordCredentialsProvider. Token-as-username works for many hosts (GitHub,
    // GitLab) when the username is blank, so we fall back to the token for the username field too.

    /** A small uniform outcome for the network ops: [ok] plus a human-readable [message] to flash. */
    data class GitResult(val ok: Boolean, val message: String)

    private fun credentials(user: String, token: String) =
        UsernamePasswordCredentialsProvider(user.ifBlank { token }, token)

    /** Configure/replace `origin` to point at [url], creating the remote if it doesn't exist yet. */
    fun setRemote(dir: File, url: String): GitResult =
        try {
            Git.open(dir).use { git ->
                val uri = URIish(url)
                val existing = git.repository.config.getSubsections("remote")
                if (existing.contains("origin")) {
                    git.remoteSetUrl().setRemoteName("origin").setRemoteUri(uri).call()
                } else {
                    git.remoteAdd().setName("origin").setUri(uri).call()
                }
                GitResult(true, "Remote set")
            }
        } catch (e: Exception) {
            GitResult(false, readableError(e))
        }

    /** Clone [url] into [dir] (which must be empty/non-repo). Returns ok + message. */
    fun clone(url: String, dir: File, user: String, token: String): GitResult =
        try {
            if (isRepo(dir)) {
                GitResult(false, "A repository already exists here")
            } else {
                dir.mkdirs()
                Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(dir)
                    .setCredentialsProvider(credentials(user, token))
                    .call()
                    .close()
                GitResult(true, "Cloned")
            }
        } catch (e: Exception) {
            GitResult(false, readableError(e))
        }

    /** `git pull` from `origin`; reports success vs merge conflict vs error. */
    fun pull(dir: File, user: String, token: String): GitResult =
        try {
            Git.open(dir).use { git ->
                val result = git.pull()
                    .setCredentialsProvider(credentials(user, token))
                    .call()
                when {
                    result.isSuccessful -> GitResult(true, "Pulled")
                    result.mergeResult?.mergeStatus?.isSuccessful == false ->
                        GitResult(false, "Merge conflict — resolve manually")
                    else -> GitResult(false, "Pull failed")
                }
            }
        } catch (e: Exception) {
            GitResult(false, readableError(e))
        }

    /** `git push` to `origin`; inspects every [RemoteRefUpdate] status (OK / REJECTED / …). */
    fun push(dir: File, user: String, token: String): GitResult =
        try {
            Git.open(dir).use { git ->
                val results = git.push()
                    .setCredentialsProvider(credentials(user, token))
                    .call()
                val updates = results.flatMap { it.remoteUpdates }
                if (updates.isEmpty()) {
                    GitResult(false, "Nothing to push")
                } else {
                    val bad = updates.firstOrNull { it.status != RemoteRefUpdate.Status.OK &&
                        it.status != RemoteRefUpdate.Status.UP_TO_DATE }
                    when (bad?.status) {
                        null -> GitResult(true, "Pushed")
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD,
                        RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                            GitResult(false, "Rejected — pull first")
                        else -> GitResult(false, "Push failed: ${bad.status}" +
                            (bad.message?.let { " ($it)" } ?: ""))
                    }
                }
            }
        } catch (e: Exception) {
            GitResult(false, readableError(e))
        }

    /** A short, user-facing message for the transport/git/IO exceptions we expect. */
    private fun readableError(e: Throwable): String = when (e) {
        is TransportException -> "Transport error: ${e.message ?: "check URL / credentials"}"
        is GitAPIException -> "Git error: ${e.message ?: e.javaClass.simpleName}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
