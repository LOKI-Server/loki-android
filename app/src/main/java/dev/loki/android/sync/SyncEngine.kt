package dev.loki.android.sync

import android.content.Context
import dev.loki.android.api.ApiException
import dev.loki.android.api.CommitResult
import dev.loki.android.api.LocalChange
import dev.loki.android.api.RemoteChange
import dev.loki.android.api.SyncApi
import dev.loki.android.data.Entry
import dev.loki.android.data.Mapping
import dev.loki.android.data.Store
import dev.loki.android.data.SyncIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

data class Outcome(
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    val removed: Int = 0,
    val conflicts: Int = 0,
    val errors: List<String> = emptyList(),
) {
    fun summary(): String {
        val parts = buildList {
            if (downloaded > 0) add("$downloaded received")
            if (uploaded > 0) add("$uploaded sent")
            if (removed > 0) add("$removed removed")
            if (conflicts > 0) add("$conflicts conflict${if (conflicts == 1) "" else "s"} kept on the server")
            if (errors.isNotEmpty()) add("${errors.size} skipped: ${errors.first()}")
        }
        return if (parts.isEmpty()) "Up to date" else parts.joinToString(" · ")
    }
}

/**
 * Two-way sync of one server folder with one local directory.
 *
 * 1. Scan: files whose size or mtime moved since the last sync are hashed; files
 *    still being written (changed in the last few seconds) wait for the next pass.
 * 2. Pull: apply the server's change feed. A path changed on both sides keeps the
 *    local file and is pushed in step 3.
 * 3. Push: upload new content and commit it against the version last seen. Edits
 *    to synced files win with `overwrite` (the server keeps the other version as a
 *    conflict); files this device never synced use `stash`, so a stale directory
 *    can never replace newer saves.
 *
 * Nothing is lost on either path: the server keeps every displaced version.
 */
class SyncEngine(private val context: Context, private val store: Store) {

    suspend fun syncAll(): Map<String, Outcome> = lock.withLock {
        withContext(Dispatchers.IO) {
            store.mappings().associate { it.folderId to syncRecording(it) }
        }
    }

    suspend fun sync(folderId: String): Outcome? = lock.withLock {
        withContext(Dispatchers.IO) { store.mapping(folderId)?.let { syncRecording(it) } }
    }

    private fun syncRecording(mapping: Mapping): Outcome {
        val outcome = try {
            syncFolder(mapping)
        } catch (e: ApiException) {
            val message = when (e.code) {
                "unknown_folder" -> "This folder was deleted on the server."
                "unauthorized" -> "This device was signed out. Pair it again."
                else -> e.message ?: "Sync failed."
            }
            store.saveMapping(mapping.copy(lastSync = System.currentTimeMillis(), lastResult = message, failed = true))
            return Outcome(errors = listOf(message))
        } catch (e: Exception) {
            val message = when (e) {
                is SecurityException -> "Access to the local folder was lost. Choose it again."
                is IOException -> "Could not reach the server: ${e.message}"
                else -> e.message ?: e.toString()
            }
            store.saveMapping(mapping.copy(lastSync = System.currentTimeMillis(), lastResult = message, failed = true))
            return Outcome(errors = listOf(message))
        }
        store.saveMapping(mapping.copy(lastSync = System.currentTimeMillis(), lastResult = outcome.summary(), failed = outcome.errors.isNotEmpty()))
        return outcome
    }

    private fun syncFolder(mapping: Mapping): Outcome {
        val api = store.api()
        val maxFile = runCatching { api.quota().maxFileBytes }.getOrDefault(Long.MAX_VALUE)
        val tree = LocalTree(context.contentResolver, mapping.treeUri)
        val indexFile = store.indexFile(mapping.folderId)
        val index = SyncIndex.load(indexFile)
        val run = Run(api, tree, index, context.cacheDir)

        // 1. Scan.
        var local = tree.scan()
        val now = System.currentTimeMillis()
        val hashes = mutableMapOf<String, String>()
        val dirty = mutableSetOf<String>()
        val unsettled = mutableSetOf<String>()
        for ((path, file) in local) {
            val known = index.entries[path]
            if (known?.hash != null && known.size == file.size && known.mtime == file.mtime) continue
            if (now - file.mtime < SETTLE_MS) {
                unsettled += path
                continue
            }
            val hash = tree.hash(file)
            hashes[path] = hash
            if (hash == known?.hash) {
                index.entries[path] = known.copy(size = file.size, mtime = file.mtime)
            } else {
                dirty += path
            }
        }
        val deleted = index.entries.filter { (path, e) -> e.hash != null && path !in local }.keys.toMutableSet()

        // 2. Pull.
        var since = index.cursor
        do {
            val feed = api.changes(mapping.folderId, since)
            if (feed.reset) {
                // The server was restored: versions are unknown, contents still compare.
                index.entries.replaceAll { _, e -> e.copy(version = 0) }
            }
            for (change in feed.changes) {
                val path = change.path
                val known = index.entries[path]
                if ((known != null && known.version == change.version) || path in unsettled) continue
                when (path) {
                    in dirty -> if (hashes[path] == change.hash) {
                        val file = local.getValue(path)
                        index.entries[path] = Entry(change.version, change.hash, file.size, file.mtime)
                        dirty -= path
                    }
                    in deleted -> {
                        deleted -= path
                        if (change.hash == null) index.entries[path] = Entry(change.version, null, 0, 0) else run.download(change)
                    }
                    else -> when {
                        change.hash == null -> {
                            if (path in local) {
                                tree.delete(path)
                                run.removed++
                            }
                            index.entries[path] = Entry(change.version, null, 0, 0)
                        }
                        path in local && known?.hash == change.hash -> index.entries[path] = known.copy(version = change.version)
                        else -> run.download(change)
                    }
                }
            }
            since = feed.seq
            index.cursor = since
            index.save(indexFile)
        } while (feed.more)
        local = tree.scan()

        // 3. Push.
        val overwrite = mutableListOf<LocalChange>()
        val stash = mutableListOf<LocalChange>()
        val deletes = mutableListOf<LocalChange>()
        for (path in dirty) {
            val file = local[path] ?: continue
            val hash = hashes.getValue(path)
            if (file.size > maxFile) {
                run.errors += "$path is larger than this server allows"
                continue
            }
            try {
                if (!api.hasBlob(hash)) api.uploadBlob(hash, file.size) { tree.open(file) }
            } catch (e: ApiException) {
                // Changed while uploading, too large, or over quota: try again next pass.
                run.errors += "$path: ${e.message}"
                continue
            }
            val known = index.entries[path]
            val change = LocalChange(path, known?.version ?: 0, hash, file.size, file.mtime / 1000)
            if (known?.hash != null) overwrite += change else stash += change
        }
        for (path in deleted) {
            deletes += LocalChange(path, index.entries.getValue(path).version, null, 0, now / 1000)
        }
        for ((changes, mode) in listOf(overwrite to "overwrite", stash to "stash", deletes to "reject")) {
            for (batch in changes.chunked(500)) {
                val sent = batch.associateBy { it.path }
                for (result in api.commit(mapping.folderId, batch, mode)) {
                    val change = sent.getValue(result.path)
                    when (result) {
                        is CommitResult.Ok -> {
                            index.entries[result.path] = if (change.hash == null) {
                                Entry(result.version, null, 0, 0)
                            } else {
                                val file = local.getValue(result.path)
                                Entry(result.version, change.hash, file.size, file.mtime)
                            }
                            run.uploaded++
                        }
                        is CommitResult.Stashed -> {
                            run.conflicts++
                            result.current?.takeIf { it.hash != null }?.let { run.download(it) }
                        }
                        // A deletion raced a newer edit; the next pull brings the edit back.
                        is CommitResult.Conflict -> Unit
                        is CommitResult.Rejected -> run.errors += "${result.path}: ${result.error}"
                    }
                }
                index.save(indexFile)
            }
        }
        // Overwrites that displaced a newer server version count as conflicts too.
        run.conflicts += overwrite.count { index.entries[it.path]?.version?.let { v -> v > it.baseVersion + 1 } == true }
        index.save(indexFile)
        return Outcome(run.downloaded, run.uploaded, run.removed, run.conflicts, run.errors)
    }

    private class Run(val api: SyncApi, val tree: LocalTree, val index: SyncIndex, val cache: File) {
        var downloaded = 0
        var uploaded = 0
        var removed = 0
        var conflicts = 0
        val errors = mutableListOf<String>()

        /**
         * Writes a server version locally. It is received into the app's cache and
         * checked against its hash first, so a damaged transfer never touches a save.
         */
        fun download(change: RemoteChange) {
            val hash = change.hash ?: return
            val staged = File.createTempFile("blob", null, cache)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                api.downloadBlob(hash) { input -> staged.outputStream().use { DigestInputStream(input, digest).copyTo(it, 64 * 1024) } }
                val received = digest.digest().joinToString("") { "%02x".format(it) }
                if (received != hash) throw IOException("${change.path} arrived damaged; it will be fetched again.")
                val file = tree.write(change.path) { out -> staged.inputStream().use { it.copyTo(out, 64 * 1024) } }
                index.entries[change.path] = Entry(change.version, hash, file.size, file.mtime)
                downloaded++
            } finally {
                staged.delete()
            }
        }
    }

    companion object {
        /** Emulators write saves in bursts; wait until a file has been still this long. */
        const val SETTLE_MS = 3_000L
        private val lock = Mutex()
    }
}
