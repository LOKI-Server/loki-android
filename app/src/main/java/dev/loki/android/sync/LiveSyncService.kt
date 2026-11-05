package dev.loki.android.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.loki.android.R
import dev.loki.android.data.Store
import dev.loki.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Live sync while the app is closed: a foreground service (with its required
 * notification) that keeps [LiveSync] running, so a save made on another device
 * arrives before you pick up this one. Off unless the person turns it on.
 */
class LiveSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        running = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while LOKI Client keeps your folders in sync in the background."
            },
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        val store = Store(this)
        if (!store.signedIn) {
            stopSelf()
            return
        }
        LiveSync(this, store).start(scope).invokeOnCompletion { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store(this).live = false
            stopSelf()
        }
        return START_STICKY
    }

    /** Android limits background data-sync services; stop cleanly when told to. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveSyncService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Live sync is on")
            .setContentText("Saves are sent and received as soon as they change.")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "live"
        private const val NOTIFICATION = 1
        private const val ACTION_STOP = "dev.loki.android.STOP_LIVE"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LiveSyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveSyncService::class.java))
        }
    }
}
