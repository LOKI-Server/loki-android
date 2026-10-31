package dev.loki.android.data

import org.json.JSONObject
import java.io.File

/**
 * What a path looked like the last time it was in step with the server: the server
 * [version] and [hash] (null for a known deletion), plus the local [size] and
 * [mtime] that let unchanged files skip rehashing.
 */
data class Entry(val version: Long, val hash: String?, val size: Long, val mtime: Long)

/** The per-folder record of the last agreed state and the change-feed cursor. */
class SyncIndex(var cursor: Long = 0, val entries: MutableMap<String, Entry> = mutableMapOf()) {
    fun save(file: File) {
        val files = JSONObject()
        for ((path, e) in entries) {
            files.put(
                path,
                JSONObject().put("v", e.version).put("h", e.hash ?: JSONObject.NULL).put("s", e.size).put("m", e.mtime),
            )
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(JSONObject().put("cursor", cursor).put("files", files).toString())
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    companion object {
        fun load(file: File): SyncIndex {
            if (!file.exists()) return SyncIndex()
            return runCatching {
                val o = JSONObject(file.readText())
                val files = o.getJSONObject("files")
                val entries = mutableMapOf<String, Entry>()
                for (path in files.keys()) {
                    val e = files.getJSONObject(path)
                    entries[path] = Entry(e.getLong("v"), if (e.isNull("h")) null else e.getString("h"), e.getLong("s"), e.getLong("m"))
                }
                SyncIndex(o.getLong("cursor"), entries)
            }.getOrElse { SyncIndex() }
        }
    }
}
