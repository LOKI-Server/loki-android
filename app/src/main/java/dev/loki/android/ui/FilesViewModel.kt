package dev.loki.android.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.api.ApiException
import dev.loki.android.api.FileShare
import dev.loki.android.api.FilesApi
import dev.loki.android.api.ShareEntry
import dev.loki.android.api.ShareListing
import dev.loki.android.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private var job: Job? = null
    var visible by mutableStateOf(false)
        private set
    var signedIn by mutableStateOf(store.signedIn)
        private set
    var server by mutableStateOf(store.server ?: "")
    var username by mutableStateOf(store.username)
    var busy by mutableStateOf(false)
        private set
    var downloading by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var shares by mutableStateOf<List<FileShare>>(emptyList())
        private set
    var share by mutableStateOf<FileShare?>(null)
        private set
    var path by mutableStateOf("")
        private set
    var listing by mutableStateOf(ShareListing(emptyList(), 0, 0))
        private set
    // Survives recreation while the system's Save dialog is open.
    var choosing by mutableStateOf<ShareEntry?>(null)
        private set

    private fun api() = store.filesApi()

    fun open(suggested: String) {
        visible = true
        if (signedIn && !store.signedIn) { server = suggested; username = ""; shares = emptyList(); share = null }
        signedIn = store.signedIn
        if (signedIn) { server = store.server ?: suggested; username = store.username }
        else if (server.isBlank()) server = suggested
        if (signedIn && !busy) loadShares()
    }
    fun close() {
        visible = false
        job?.cancel()
        choosing = null
    }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        message = null
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiException && e.status == 401) { signedIn = false }
                error = e.message ?: "Could not reach the server."
            } finally { busy = false; downloading = false; progress = null }
        }
    }
    fun login(password: String, code: String) = work {
        val api = FilesApi(server)
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80).ifEmpty { "Android device" }
        val session = runInterruptible(Dispatchers.IO) { api.discover(); api.login(username.trim(), password, code.trim(), name) }
        store.signIn(api.base, session)
        server = api.base
        signedIn = true
        shares = runInterruptible(Dispatchers.IO) { api().shares() }
        share = null
        path = ""
    }
    fun loadShares() = work {
        shares = runInterruptible(Dispatchers.IO) { api().let { it.discover(); it.shares() } }
        share = null
        path = ""
        listing = ShareListing(emptyList(), 0, 0)
    }
    fun load(target: FileShare, directory: String = "", page: Int = 0) = work {
        val result = runInterruptible(Dispatchers.IO) { api().entries(target.id, directory, page) }
        share = target
        path = directory
        listing = result
    }
    fun up() {
        val target = share ?: return
        if (path.isEmpty()) loadShares() else load(target, path.substringBeforeLast('/', ""))
    }
    fun refresh() { val target = share; if (target == null) loadShares() else load(target, path, listing.page) }
    fun choose(entry: ShareEntry) { choosing = entry }
    fun saveTo(uri: Uri?) {
        val entry = choosing
        choosing = null
        val target = share
        if (uri == null || entry == null || target == null) return
        val remotePath = if (path.isEmpty()) entry.name else "$path/${entry.name}"
        work {
            downloading = true
            val api = api()
            val resolver = getApplication<Application>().contentResolver
            try {
                runInterruptible(Dispatchers.IO) {
                    val temporary = api.download(target.id, remotePath, File(getApplication<Application>().cacheDir, "downloads")) { received, total ->
                        viewModelScope.launch { progress = received to total }
                    }
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        temporary.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Download cancelled.")
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Cannot write to the selected location.")
                    temporary.delete()
                }
                message = "Downloaded ${entry.name} and verified its checksum."
            } catch (e: Exception) {
                // CreateDocument created a new destination; remove an incomplete result.
                withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
                throw e
            }
        }
    }
    fun cancelDownload() { job?.cancel(); message = "Download cancelled. Choose the file again to resume." }
}
