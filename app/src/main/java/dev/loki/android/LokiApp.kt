package dev.loki.android

import android.app.Application
import dev.loki.android.data.Store
import dev.loki.android.sync.SyncWorker

class LokiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Store(this).let { it.signedIn && it.canSync }) SyncWorker.schedule(this)
    }
}
