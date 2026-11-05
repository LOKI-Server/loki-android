package dev.loki.android.api

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class FileShare(val id: String, val name: String)
data class ShareEntry(val name: String, val directory: Boolean, val size: Long, val modified: Long)
data class ShareListing(val entries: List<ShareEntry>, val total: Long, val page: Int)
data class SharedFileMetadata(val size: Long, val sha256: String, val lastModified: String?)

/** Read-only file-share requests. Run blocking calls off the main thread. */
class FilesApi(server: String, private val token: String? = null) {
    val base = SyncApi.normalize(server)

    fun discover() {
        val unsupported = "This server does not support this version of file browsing. Update LOKI Server."
        try {
            val o = json("GET", "/files/v1")
            require(o.optInt("protocol") == 1 && o.optString("checksum") == "sha256") { unsupported }
        } catch (e: org.json.JSONException) { throw IOException(unsupported, e) }
        catch (e: ApiException) { if (e.status == 404) throw IOException(unsupported, e); throw e }
    }

    fun login(username: String, password: String, code: String, name: String): Session {
        val o = json("POST", "/auth/v1/login", JSONObject().put("username", username)
            .put("password", password).put("code", code).put("name", name).put("platform", "android"))
        val user = o.getJSONObject("user")
        return Session(o.getString("token"), o.getJSONObject("device").getString("id"),
            user.getString("username"), user.optString("display_name", user.getString("username")), o.optJSONObject("access")?.optBoolean("sync", true) ?: true)
    }

    fun signOut() { json("DELETE", "/auth/v1/device") }

    fun shares(): List<FileShare> {
        val list = json("GET", "/files/v1/shares").getJSONArray("shares")
        return (0 until list.length()).map { list.getJSONObject(it).let { s -> FileShare(s.getString("id"), s.getString("name")) } }
    }

    fun entries(share: String, path: String, page: Int = 0): ShareListing {
        val o = json("GET", route(share, "entries", path) + "&page=$page")
        val list = o.getJSONArray("entries")
        return ShareListing((0 until list.length()).map {
            list.getJSONObject(it).let { e -> ShareEntry(e.getString("name"), e.getBoolean("directory"), e.getLong("size"), e.getLong("modified")) }
        }, o.getLong("total"), o.getInt("page"))
    }

    fun metadata(share: String, path: String): SharedFileMetadata {
        val o = json("GET", route(share, "metadata", path) + "&checksum=true")
        val result = SharedFileMetadata(o.getLong("size"), o.getString("sha256"),
            if (o.isNull("last_modified")) null else o.getString("last_modified"))
        require(result.size >= 0 && result.sha256.matches(Regex("[a-f0-9]{64}"))) { "The server returned invalid file metadata." }
        return result
    }

    /** A partial is tied to the exact content hash. On 200, replace it; on 206,
     * validate the range before appending. Never publish unverified bytes. */
    fun download(share: String, path: String, cache: File, progress: (Long, Long) -> Unit): File {
        interrupted()
        progress(0, 0)
        val meta = metadata(share, path)
        interrupted()
        check(cache.isDirectory || cache.mkdirs()) { "Cannot create the download cache." }
        val key = digest("$base\u0000$share\u0000$path\u0000${meta.sha256}".toByteArray())
        val partial = File(cache, "$key.part")
        var offset = if (partial.exists()) partial.length() else 0L
        if (offset > meta.size || (partial.exists() && offset == meta.size && hash(partial) != meta.sha256)) {
            partial.outputStream().close()
            offset = 0
        }
        if (offset != meta.size || !partial.exists()) {
            val c = open("GET", route(share, "file", path))
            try {
                c.setRequestProperty("Accept", "application/octet-stream")
                if (offset > 0) {
                    c.setRequestProperty("Range", "bytes=$offset-")
                    meta.lastModified?.let { c.setRequestProperty("If-Range", it) }
                }
                val status = c.responseCode
                if (status != 200 && status != 206) throw failure(c)
                if (status == 206) {
                    check(offset > 0 && c.getHeaderField("Content-Range") == "bytes $offset-${meta.size - 1}/${meta.size}") {
                        "The server returned an unexpected download range. Retry the download."
                    }
                } else offset = 0
                var received = offset
                var reported = 0L
                c.inputStream.use { input ->
                    java.io.FileOutputStream(partial, status == 206).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            interrupted()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(count <= meta.size - received) { "The file changed during the download. Retry it." }
                            out.write(buffer, 0, count)
                            received += count
                            val now = System.nanoTime()
                            if (now - reported > 150_000_000L) { progress(received, meta.size); reported = now }
                        }
                    }
                }
                if (received != meta.size) throw IOException("Download interrupted. Choose the file again to resume.")
            } finally { c.disconnect() }
        }
        interrupted()
        progress(meta.size, meta.size)
        if (partial.length() != meta.size || hash(partial) != meta.sha256) {
            partial.delete()
            throw IOException("The downloaded file failed verification. Retry to download a fresh copy.")
        }
        return partial
    }

    private fun json(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = open(method, path)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw failure(c)
            val text = c.inputStream.use { it.readBytes().decodeToString() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { c.disconnect() }
    }

    private fun open(method: String, path: String): HttpURLConnection = (URL(base + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 90_000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Accept-Encoding", "identity")
        token?.let { setRequestProperty("Authorization", "Bearer $it") }
    }

    private fun failure(c: HttpURLConnection): ApiException {
        val text = runCatching { c.errorStream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() < 65536) {
                val count = input.read(buffer, 0, minOf(buffer.size, 65536 - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toString("UTF-8")
        } }.getOrNull()
        val o = runCatching { JSONObject(text ?: "") }.getOrNull()
        return ApiException(c.responseCode, o?.optString("code")?.takeIf { it.isNotBlank() },
            o?.optString("error")?.takeIf { it.isNotBlank() } ?: "The server answered ${c.responseCode}.")
    }

    companion object {
        private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
        private fun route(share: String, endpoint: String, path: String) = "/files/v1/shares/${enc(share)}/$endpoint?path=${enc(path)}"
        private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Download cancelled.") }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    interrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
