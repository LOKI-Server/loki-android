package dev.loki.android.api

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An error response from LOKI, with its stable machine-readable [code] when it has one. */
class ApiException(val status: Int, val code: String?, message: String) : IOException(message)

data class Discovery(val name: String, val protocol: Int, val maxFileBytes: Long)
data class Pairing(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val interval: Int,
    val expiresIn: Int,
)
data class Session(val token: String, val deviceId: String, val username: String, val displayName: String)
data class Folder(val id: String, val name: String, val kind: String, val keepVersions: Int, val seq: Long)
data class RemoteChange(val path: String, val version: Long, val hash: String?, val size: Long, val mtime: Long, val seq: Long)
data class Feed(val seq: Long, val more: Boolean, val reset: Boolean, val changes: List<RemoteChange>)
data class Quota(val used: Long, val limit: Long, val maxFileBytes: Long)

/** One change sent in a commit. A null [hash] deletes the path. */
data class LocalChange(val path: String, val baseVersion: Long, val hash: String?, val size: Long, val mtime: Long)

sealed interface CommitResult {
    val path: String
    data class Ok(override val path: String, val version: Long) : CommitResult
    data class Conflict(override val path: String, val current: RemoteChange?) : CommitResult
    data class Stashed(override val path: String, val current: RemoteChange?) : CommitResult
    data class Rejected(override val path: String, val code: String, val error: String) : CommitResult
}

/**
 * The LOKI `/sync/v1` client. Plain HTTP and JSON from the platform; calls block,
 * so run them off the main thread.
 */
class SyncApi(baseUrl: String, private val token: String? = null) {
    val base: String = normalize(baseUrl)

    fun discover(): Discovery {
        val o = json("GET", "/sync/v1")
        return Discovery(o.optString("name", "LOKI"), o.optInt("protocol"), o.optLong("max_file_bytes"))
    }

    fun pair(deviceName: String, platform: String): Pairing {
        val o = json("POST", "/sync/v1/pair", JSONObject().put("name", deviceName).put("platform", platform))
        return Pairing(
            o.getString("device_code"),
            o.getString("user_code"),
            o.getString("verification_uri"),
            o.getString("verification_uri_complete"),
            o.optInt("interval", 5),
            o.optInt("expires_in", 600),
        )
    }

    /** Null while the code is still waiting for approval in the dashboard. */
    fun poll(deviceCode: String): Session? = try {
        session(json("POST", "/sync/v1/pair/poll", JSONObject().put("device_code", deviceCode)))
    } catch (e: ApiException) {
        if (e.code == "authorization_pending" || e.code == "slow_down") null else throw e
    }

    fun login(username: String, password: String, code: String, deviceName: String, platform: String): Session =
        session(
            json(
                "POST", "/sync/v1/login",
                JSONObject().put("username", username).put("password", password).put("code", code)
                    .put("name", deviceName).put("platform", platform),
            ),
        )

    fun quota(): Quota {
        val o = json("GET", "/sync/v1/me")
        val q = o.getJSONObject("quota")
        return Quota(q.getLong("used"), q.getLong("limit"), o.optLong("max_file_bytes"))
    }

    fun signOut() {
        json("DELETE", "/sync/v1/device")
    }

    fun folders(): List<Folder> {
        val array = JSONArray(text("GET", "/sync/v1/folders"))
        return (0 until array.length()).map { folder(array.getJSONObject(it)) }
    }

    fun createFolder(name: String, kind: String): Folder =
        folder(json("POST", "/sync/v1/folders", JSONObject().put("name", name).put("kind", kind)))

    fun changes(folder: String, since: Long, limit: Int = 1000): Feed {
        val o = json("GET", "/sync/v1/folders/${enc(folder)}/changes?since=$since&limit=$limit")
        val list = o.getJSONArray("changes")
        return Feed(
            o.getLong("seq"),
            o.optBoolean("more"),
            o.optBoolean("reset"),
            (0 until list.length()).map { change(list.getJSONObject(it))!! },
        )
    }

    fun hasBlob(hash: String): Boolean {
        val c = open("HEAD", "/sync/v1/blobs/$hash")
        try {
            return when (c.responseCode) {
                200 -> true
                404 -> false
                else -> throw failure(c)
            }
        } finally {
            c.disconnect()
        }
    }

    fun uploadBlob(hash: String, size: Long, body: () -> InputStream) {
        val c = open("PUT", "/sync/v1/blobs/$hash")
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            body().use { input -> c.outputStream.use { input.copyTo(it, BUFFER) } }
            if (c.responseCode !in 200..299) throw failure(c)
        } finally {
            c.disconnect()
        }
    }

    fun downloadBlob(hash: String, sink: (InputStream) -> Unit) {
        val c = open("GET", "/sync/v1/blobs/$hash")
        try {
            if (c.responseCode != 200) throw failure(c)
            c.inputStream.use(sink)
        } finally {
            c.disconnect()
        }
    }

    fun commit(folder: String, changes: List<LocalChange>, onConflict: String): List<CommitResult> {
        val array = JSONArray()
        for (ch in changes) {
            array.put(
                JSONObject().put("path", ch.path).put("base_version", ch.baseVersion)
                    .put("hash", ch.hash ?: JSONObject.NULL).put("mtime", ch.mtime)
                    .apply { if (ch.hash != null) put("size", ch.size) },
            )
        }
        val o = json(
            "POST", "/sync/v1/folders/${enc(folder)}/commit",
            JSONObject().put("changes", array).put("on_conflict", onConflict),
        )
        val results = o.getJSONArray("results")
        return (0 until results.length()).map {
            val r = results.getJSONObject(it)
            val path = r.getString("path")
            when (r.getString("status")) {
                "ok" -> CommitResult.Ok(path, r.getLong("version"))
                "stashed" -> CommitResult.Stashed(path, change(r.optJSONObject("current"), path))
                "rejected" -> CommitResult.Rejected(path, r.optString("code"), r.optString("error"))
                else -> CommitResult.Conflict(path, change(r.optJSONObject("current"), path))
            }
        }
    }

    private fun session(o: JSONObject): Session {
        val user = o.getJSONObject("user")
        return Session(
            o.getString("token"),
            o.getJSONObject("device").getString("id"),
            user.getString("username"),
            user.optString("display_name", user.getString("username")),
        )
    }

    private fun folder(o: JSONObject) = Folder(
        o.getString("id"), o.getString("name"), o.optString("kind", "generic"),
        o.optInt("keep_versions", 10), o.optLong("seq"),
    )

    private fun change(o: JSONObject?, path: String? = null): RemoteChange? = o?.let {
        RemoteChange(
            path ?: it.getString("path"),
            it.getLong("version"),
            if (it.isNull("hash")) null else it.getString("hash"),
            it.optLong("size"),
            it.optLong("mtime"),
            it.optLong("seq"),
        )
    }

    private fun json(method: String, path: String, body: JSONObject? = null): JSONObject {
        val t = text(method, path, body)
        return if (t.isBlank()) JSONObject() else JSONObject(t)
    }

    private fun text(method: String, path: String, body: JSONObject? = null): String {
        val c = open(method, path)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw failure(c)
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 90_000
        c.setRequestProperty("Accept", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun failure(c: HttpURLConnection): ApiException {
        val body = runCatching { c.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull()
        val o = runCatching { JSONObject(body ?: "") }.getOrNull()
        val message = o?.optString("error")?.takeIf { it.isNotEmpty() }
            ?: "The server answered ${c.responseCode}."
        return ApiException(c.responseCode, o?.optString("code")?.takeIf { it.isNotEmpty() }, message)
    }

    companion object {
        private const val BUFFER = 64 * 1024

        /** Accepts `loki.example.com`, `https://loki.example.com/`, and similar. */
        fun normalize(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        }

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
