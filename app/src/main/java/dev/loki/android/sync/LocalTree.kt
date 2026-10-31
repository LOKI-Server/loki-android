package dev.loki.android.sync

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.Normalizer

/** A file found in a synced directory. [path] is the logical, NFC, `/`-separated path. */
data class LocalFile(val path: String, val uri: Uri, val size: Long, val mtime: Long)

/**
 * A directory the person granted through the Storage Access Framework. Paths are
 * relative to that directory. Queries go straight to the documents provider, which
 * is much faster than DocumentFile for directories full of saves.
 */
class LocalTree(private val resolver: ContentResolver, private val tree: Uri) {
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val directories = mutableMapOf("" to rootId)
    private val files = mutableMapOf<String, LocalFile>()

    /** Every regular file under the root, keyed by logical path. */
    fun scan(): Map<String, LocalFile> {
        directories.clear()
        directories[""] = rootId
        files.clear()
        walk("", rootId)
        return files.toMap()
    }

    private fun walk(prefix: String, documentId: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val found = mutableListOf<Pair<String, String>>()
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = Normalizer.normalize(c.getString(1) ?: continue, Normalizer.Form.NFC)
                if (ignored(name)) continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (c.getString(2) == Document.MIME_TYPE_DIR) {
                    directories[path] = id
                    found += path to id
                } else {
                    files[path] = LocalFile(path, DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getLong(3), c.getLong(4))
                }
            }
        }
        for ((path, id) in found) walk(path, id)
    }

    fun open(file: LocalFile): InputStream =
        resolver.openInputStream(file.uri) ?: error("Cannot read ${file.path}.")

    fun hash(file: LocalFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Replaces (or creates) [path] with what [write] produces, creating parent
     * directories as needed, and returns the file as the provider now reports it.
     */
    fun write(path: String, write: (OutputStream) -> Unit): LocalFile {
        val existing = files[path]
        val uri = existing?.uri ?: run {
            val parent = directory(path.substringBeforeLast('/', ""))
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", path.substringAfterLast('/'))
                ?: error("Cannot create $path.")
        }
        // "wt" truncates, so a shorter save never keeps the tail of the old one.
        (resolver.openOutputStream(uri, "wt") ?: error("Cannot write $path.")).use(write)
        val written = stat(path, uri)
        files[path] = written
        return written
    }

    fun delete(path: String) {
        val file = files.remove(path) ?: return
        DocumentsContract.deleteDocument(resolver, file.uri)
    }

    private fun stat(path: String, uri: Uri): LocalFile {
        resolver.query(uri, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) return LocalFile(path, uri, c.getLong(0), c.getLong(1))
        }
        return LocalFile(path, uri, 0, System.currentTimeMillis())
    }

    private fun directory(path: String): Uri {
        if (path.isEmpty()) return DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        directories[path]?.let { return DocumentsContract.buildDocumentUriUsingTree(tree, it) }
        val parent = directory(path.substringBeforeLast('/', ""))
        val created = DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, path.substringAfterLast('/'))
            ?: error("Cannot create folder $path.")
        directories[path] = DocumentsContract.getDocumentId(created)
        return created
    }

    companion object {
        /** Scratch files that editors and this app leave behind are never synced. */
        fun ignored(name: String) = name.startsWith(".loki") || name == ".nomedia" || name == "desktop.ini" || name == ".DS_Store" || name == "Thumbs.db"
    }
}
