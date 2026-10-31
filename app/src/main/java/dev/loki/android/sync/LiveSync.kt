package dev.loki.android.sync

import android.content.Context
import dev.loki.android.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Syncs as soon as something changes, for as long as [start]'s scope lives:
 * one long-poll on the server's watch endpoint reports other devices' changes,
 * and a light directory check every few seconds reports settled local saves
 * (the Storage Access Framework offers no reliable change notifications).
 * Requests arriving during a sync collapse into one more pass.
 */
class LiveSync(private val context: Context, private val store: Store) {
    private val engine = SyncEngine(context, store)

    fun start(scope: CoroutineScope): Job = scope.launch {
        val wanted = Channel<Unit>(Channel.CONFLATED)
        launch { watchServer { wanted.trySend(Unit) } }
        launch { watchLocal { wanted.trySend(Unit) } }
        wanted.trySend(Unit)
        for (request in wanted) {
            if (!store.signedIn) break
            engine.syncAll()
        }
    }

    private suspend fun watchServer(changed: () -> Unit) {
        var state = ""
        while (currentCoroutineContext().isActive && store.signedIn) {
            try {
                val first = state.isEmpty()
                val result = withContext(Dispatchers.IO) { store.api().watch(state, 60) }
                state = result.state
                if (result.changed && !first) changed()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Offline or signed out: try again shortly.
                delay(15_000)
            }
        }
    }

    private suspend fun watchLocal(changed: () -> Unit) {
        while (currentCoroutineContext().isActive && store.signedIn) {
            delay(LOCAL_CHECK_MS)
            if (engine.localChanges()) changed()
        }
    }

    companion object {
        const val LOCAL_CHECK_MS = 4_000L
    }
}
