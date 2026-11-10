package dev.loki.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.api.Discovery
import dev.loki.android.api.Folder
import dev.loki.android.api.Pairing
import dev.loki.android.api.Quota
import dev.loki.android.api.Session
import dev.loki.android.api.SyncApi
import dev.loki.android.data.Mapping
import dev.loki.android.data.Store
import dev.loki.android.data.FileStore
import dev.loki.android.sync.LiveSync
import dev.loki.android.sync.LiveSyncService
import dev.loki.android.sync.SyncEngine
import dev.loki.android.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val engine = SyncEngine(app, store)

    var signedIn by mutableStateOf(store.signedIn)
        private set
    var canSync by mutableStateOf(store.canSync)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    // Connecting.
    var server by mutableStateOf(store.server ?: "")
    var discovery by mutableStateOf<Discovery?>(null)
        private set
    var pairing by mutableStateOf<Pairing?>(null)
        private set
    private var polling: Job? = null

    // Signed in.
    val displayName get() = store.displayName.ifEmpty { store.username }
    val serverUrl get() = store.server ?: ""
    var folders by mutableStateOf<List<Folder>>(emptyList())
        private set
    var mappings by mutableStateOf<Map<String, Mapping>>(emptyMap())
        private set
    var quota by mutableStateOf<Quota?>(null)
        private set
    var syncing by mutableStateOf<String?>(null)
        private set
    var liveInBackground by mutableStateOf(store.live)
        private set
    private var live: Job? = null

    private val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        .ifEmpty { "Android device" }

    init {
        if (signedIn) refresh()
        // Show results of syncs that ran anywhere: live sync, the worker, or a button.
        viewModelScope.launch {
            SyncEngine.synced.collect { mappings = store.mappings().associateBy { it.folderId } }
        }
    }

    /**
     * While the app is on screen it syncs as soon as anything changes. With live
     * sync in the background on, the foreground service does that instead.
     */
    fun appVisible() {
        if (!signedIn || !canSync) return
        if (store.live) {
            LiveSyncService.start(getApplication())
        } else if (live == null) {
            live = LiveSync(getApplication(), store).start(viewModelScope)
        }
    }

    fun appHidden() {
        live?.cancel()
        live = null
    }

    fun changeLiveInBackground(on: Boolean) {
        store.live = on
        liveInBackground = on
        if (on) {
            appHidden()
            LiveSyncService.start(getApplication())
        } else {
            LiveSyncService.stop(getApplication())
            appVisible()
        }
    }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    fun discover() = work {
        val api = SyncApi(server)
        discovery = withContext(Dispatchers.IO) { api.discover() }
        server = api.base
    }

    fun forgetServer() {
        cancelPairing()
        discovery = null
    }

    fun startPairing() = work {
        val api = SyncApi(server)
        val started = withContext(Dispatchers.IO) { api.pair(deviceName, "android") }
        pairing = started
        polling?.cancel()
        polling = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + started.expiresIn * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(started.interval * 1000L)
                val session = try {
                    withContext(Dispatchers.IO) { api.poll(started.deviceCode) }
                } catch (e: Exception) {
                    error = e.message
                    pairing = null
                    return@launch
                }
                if (session != null) {
                    finishSignIn(session)
                    return@launch
                }
            }
            error = "The code expired. Start pairing again."
            pairing = null
        }
    }

    fun cancelPairing() {
        polling?.cancel()
        pairing = null
    }

    fun approvalIntent(): Intent? = pairing?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it.verificationUriComplete)) }

    fun login(username: String, password: String, code: String) = work {
        val session = withContext(Dispatchers.IO) { SyncApi(server).login(username.trim(), password, code.trim(), deviceName, "android") }
        finishSignIn(session)
    }

    private fun finishSignIn(session: Session) {
        store.signIn(server, session)
        pairing = null
        signedIn = true
        canSync = store.canSync
        if (canSync) SyncWorker.schedule(getApplication())
        refresh()
        appVisible()
    }

    fun refresh() = work {
        val api = store.api()
        val wasAllowed = canSync
        canSync = withContext(Dispatchers.IO) { api.syncAccess() }
        store.canSync = canSync
        if (!canSync) {
            folders = emptyList(); quota = null; appHidden(); SyncWorker.cancel(getApplication())
            LiveSyncService.stop(getApplication()); return@work
        }
        if (!wasAllowed) { SyncWorker.schedule(getApplication()); appVisible() }
        val (list, usage) = withContext(Dispatchers.IO) { api.folders() to api.quota() }
        folders = list
        quota = usage
        mappings = store.mappings().associateBy { it.folderId }
    }

    fun createFolder(name: String, kind: String) = work {
        val api = store.api()
        withContext(Dispatchers.IO) { api.createFolder(name.trim(), kind) }
        refresh()
    }

    /** Remembers the directory for a folder and syncs it straight away. */
    fun map(folderId: String, tree: Uri) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val previous = store.mapping(folderId)
        // A new place starts a fresh index, including one left by an earlier mapping.
        if (previous == null || previous.treeUri != tree) store.removeMapping(folderId)
        store.saveMapping(Mapping(folderId, tree))
        mappings = store.mappings().associateBy { it.folderId }
        syncNow(folderId)
    }

    fun unmap(folderId: String) {
        val mapping = store.mapping(folderId) ?: return
        store.removeMapping(folderId)
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                mapping.treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        mappings = store.mappings().associateBy { it.folderId }
    }

    /** Syncs one folder, or every mapped folder when [folderId] is null. */
    fun syncNow(folderId: String? = null) {
        if (syncing != null || !canSync) return
        viewModelScope.launch {
            syncing = folderId ?: "all"
            try {
                if (folderId == null) engine.syncAll() else engine.sync(folderId)
                quota = runCatching { withContext(Dispatchers.IO) { store.api().quota() } }.getOrNull() ?: quota
            } finally {
                mappings = store.mappings().associateBy { it.folderId }
                syncing = null
            }
        }
    }

    /** File browsing and sync share the same account store. */
    fun accountChanged() {
        if (signedIn == store.signedIn && (!store.signedIn || server == store.server)) return
        signedIn = store.signedIn
        canSync = store.canSync
        server = store.server ?: ""
        if (signedIn) { if (canSync) SyncWorker.schedule(getApplication()); refresh(); appVisible() }
    }

    fun signOut() = work {
        runCatching { withContext(Dispatchers.IO) { store.api().signOut() } }
        val legacy = FileStore(getApplication())
        if (legacy.signedIn && legacy.token != store.token) runCatching { withContext(Dispatchers.IO) { legacy.api().signOut() } }
        appHidden()
        LiveSyncService.stop(getApplication())
        liveInBackground = false
        for (mapping in store.mappings()) unmap(mapping.folderId)
        store.signOut()
        SyncWorker.cancel(getApplication())
        folders = emptyList()
        discovery = null
        signedIn = false
    }
}
