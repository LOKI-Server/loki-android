package dev.loki.android.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors

class FilesApiTest {
    private lateinit var server: HttpServer
    private lateinit var cache: File
    private lateinit var base: String
    private var bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
    private var mode = "normal"
    private var protocol = 1
    private var lastRange: String? = null
    private var lastPath = ""
    private var lastPage = ""
    private var logins = 0
    private val date = "Thu, 05 Nov 2026 00:00:00 GMT"
    private val executor = Executors.newCachedThreadPool()

    @Before fun setup() {
        cache = Files.createTempDirectory("loki-files-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try { respond(exchange) } finally { exchange.close() }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }
    @After fun teardown() { server.stop(0); executor.shutdownNow(); cache.deleteRecursively() }
    private fun api() = FilesApi(base, "download-token")
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun send(e: HttpExchange, status: Int, body: String) {
        val data = body.toByteArray()
        e.responseHeaders.add("Content-Type", "application/json")
        e.sendResponseHeaders(status, data.size.toLong())
        e.responseBody.write(data)
    }
    private fun respond(e: HttpExchange) {
        val path = e.requestURI.path
        if (path == "/files/v1") {
            send(e, 200, if (mode == "legacy") "<!DOCTYPE html><html>LOKI</html>" else """{"protocol":$protocol,"checksum":"sha256"}""")
            return
        }
        if (path == "/auth/v1/pair") {
            send(e, 200, """{"device_code":"device-code","user_code":"BCDF-GHJK","verification_uri":"$base/pair","verification_uri_complete":"$base/pair?code=BCDF-GHJK","interval":5,"expires_in":600}""")
            return
        }
        if (path == "/auth/v1/login" || path == "/auth/v1/pair/poll") {
            logins++
            val input = JSONObject(e.requestBody.readBytes().decodeToString())
            if (path.endsWith("login")) {
                assertEquals("reader", input.getString("username"))
                assertEquals("android", input.getString("platform"))
            } else assertEquals("device-code", input.getString("device_code"))
            send(e, 200, """{"token":"download-token","device":{"id":"device"},"user":{"username":"reader","display_name":"Reader"},"access":{"sync":${mode != "filesOnly"}}}""")
            return
        }
        if (e.requestHeaders.getFirst("Authorization") != "Bearer download-token") {
            send(e, 401, """{"error":"Sign in again.","code":"unauthorized"}"""); return
        }
        if (mode == "forbidden") { send(e, 403, """{"error":"Access revoked.","code":"forbidden"}"""); return }
        if (path == "/auth/v1/device") { send(e, 200, """{"ok":true}"""); return }
        if (path == "/auth/v1/me") { send(e, 200, """{"access":{"sync":${mode != "filesOnly"}}}"""); return }
        if (path == "/sync/v1/me") { send(e, 200, """{"quota":{"used":0,"limit":1024},"max_file_bytes":1024}"""); return }
        if (path == "/files/v1/shares") { send(e, 200, """{"shares":[{"id":"archive","name":"Archive"}]}"""); return }
        val query = e.requestURI.rawQuery.orEmpty().split('&').associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        lastPath = query["path"].orEmpty()
        if (path.endsWith("/entries")) {
            lastPage = query["page"].orEmpty()
            send(e, 200, JSONObject().put("entries", JSONArray().put(JSONObject().put("name", "日本語 + #.bin")
                .put("directory", false).put("size", bytes.size).put("modified", 1793836800)))
                .put("total", 101).put("page", lastPage.toInt()).toString())
        } else if (path.endsWith("/metadata")) {
            assertEquals("true", query["checksum"])
            send(e, 200, JSONObject().put("size", bytes.size).put("sha256", if (mode == "badHash") "0".repeat(64) else hash(bytes))
                .put("last_modified", date).toString())
        } else if (path.endsWith("/file")) {
            lastRange = e.requestHeaders.getFirst("Range")
            val offset = if (mode == "restart") 0 else lastRange?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val partial = offset > 0
            if (partial) {
                assertEquals(date, e.requestHeaders.getFirst("If-Range"))
                e.responseHeaders.add("Content-Range", "bytes ${if (mode == "badRange") offset + 1 else offset}-${bytes.size - 1}/${bytes.size}")
            }
            if (bytes.isEmpty()) { e.sendResponseHeaders(200, -1); return }
            e.sendResponseHeaders(if (partial) 206 else 200, (bytes.size - offset).toLong())
            if (mode == "interrupt") {
                e.responseBody.write(bytes, offset, 65536)
                e.responseBody.flush()
                // Deliberately close a truncated fixed-length response.
            } else runCatching { e.responseBody.write(bytes, offset, bytes.size - offset) }
        } else send(e, 404, """{"error":"Missing"}""")
    }

    @Test fun discoveryLoginAndBrowsingUseFilesApiAndEncodePaths() {
        api().discover()
        assertEquals("download-token", FilesApi(base).login("reader", "password", "", "Android test").token)
        assertEquals("Archive", api().shares().single().name)
        val listing = api().entries("archive", "Games/日本語 + #", 1)
        assertEquals("Games/日本語 + #", lastPath)
        assertEquals("1", lastPage)
        assertEquals(101L, listing.total)
        assertEquals("日本語 + #.bin", listing.entries.single().name)
    }
    @Test fun oneSavedLoginWorksForBothSyncAndFileBrowsing() {
        val session = SyncApi(base).login("reader", "password", "", "Android", "android")
        assertTrue(session.canSync)
        assertTrue(SyncApi(base, session.token).syncAccess())
        assertEquals(0L, SyncApi(base, session.token).quota().used)
        assertEquals("Archive", FilesApi(base, session.token).shares().single().name)
        assertEquals(1, logins)
    }
    @Test fun codePairingUsesAccountAuthenticationForBothServices() {
        val pending = SyncApi(base).pair("Android", "android")
        val session = SyncApi(base).poll(pending.deviceCode)!!
        assertEquals("Archive", FilesApi(base, session.token).shares().single().name)
        assertTrue(SyncApi(base, session.token).syncAccess())
        assertEquals(1, logins)
    }
    @Test fun fileOnlyAccountsDoNotRequireSyncAccessToSignIn() {
        mode = "filesOnly"
        val session = SyncApi(base).login("reader", "password", "", "Android", "android")
        assertFalse(session.canSync)
        assertFalse(SyncApi(base, session.token).syncAccess())
        assertEquals("Archive", FilesApi(base, session.token).shares().single().name)
    }
    @Test fun downloadsAndVerifiesUnicodeFiles() {
        val file = api().download("archive", "日本語 + #.bin", cache) { _, _ -> }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("日本語 + #.bin", lastPath)
    }
    @Test fun interruptedDownloadsResumeFromExistingPartial() {
        mode = "interrupt"
        assertThrows(IOException::class.java) { api().download("archive", "file", cache) { _, _ -> } }
        assertEquals(65536L, cache.listFiles()!!.single().length())
        mode = "normal"
        val result = api().download("archive", "file", cache) { _, _ -> }
        assertEquals("bytes=65536-", lastRange)
        assertArrayEquals(bytes, result.readBytes())
    }
    @Test fun fullResponseToResumeReplacesRatherThanAppends() {
        mode = "interrupt"
        assertThrows(IOException::class.java) { api().download("archive", "file", cache) { _, _ -> } }
        mode = "restart"
        assertArrayEquals(bytes, api().download("archive", "file", cache) { _, _ -> }.readBytes())
        assertEquals("bytes=65536-", lastRange)
    }
    @Test fun wrongRangeIsRejectedWithoutChangingPartial() {
        mode = "interrupt"
        assertThrows(IOException::class.java) { api().download("archive", "file", cache) { _, _ -> } }
        val partial = cache.listFiles()!!.single().readBytes()
        mode = "badRange"
        assertThrows(IllegalStateException::class.java) { api().download("archive", "file", cache) { _, _ -> } }
        assertArrayEquals(partial, cache.listFiles()!!.single().readBytes())
    }
    @Test fun checksumMismatchDiscardsCorruptDownload() {
        mode = "badHash"
        assertThrows(IOException::class.java) { api().download("archive", "file", cache) { _, _ -> } }
        assertTrue(cache.listFiles()!!.isEmpty())
    }
    @Test fun emptyFilesAreCreatedAndVerified() {
        bytes = byteArrayOf()
        val result = api().download("archive", "empty", cache) { _, _ -> }
        assertTrue(result.isFile)
        assertEquals(0L, result.length())
    }
    @Test fun cancellationKeepsPartialForRetry() {
        assertThrows(InterruptedIOException::class.java) {
            api().download("archive", "file", cache) { received, _ -> if (received > 0) throw InterruptedIOException("Cancelled") }
        }
        assertTrue(cache.listFiles()!!.single().length() > 0)
        assertArrayEquals(bytes, api().download("archive", "file", cache) { _, _ -> }.readBytes())
    }
    @Test fun tokenAndPermissionErrorsKeepTheirStatusAndCode() {
        val missing = assertThrows(ApiException::class.java) { FilesApi(base).shares() }
        assertEquals(401, missing.status)
        assertEquals("unauthorized", missing.code)
        mode = "forbidden"
        val denied = assertThrows(ApiException::class.java) { api().shares() }
        assertEquals(403, denied.status)
        assertEquals("forbidden", denied.code)
    }
    @Test fun unsupportedProtocolIsRejected() {
        protocol = 2
        assertThrows(IllegalArgumentException::class.java) { api().discover() }
    }
    @Test fun olderServersExplainHowToEnableFileBrowsing() {
        mode = "legacy"
        val error = assertThrows(IOException::class.java) { api().discover() }
        assertTrue(error.message!!.contains("Update LOKI Server"))
    }
}
