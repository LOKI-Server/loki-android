package dev.loki.android.data

import android.content.Context
import dev.loki.android.api.FilesApi
import dev.loki.android.api.Session
import dev.loki.android.api.SyncApi

/** Legacy file-only credentials, used only to migrate or revoke old sessions. */
class FileStore(context: Context) {
    private val prefs = context.getSharedPreferences("loki_files", Context.MODE_PRIVATE)
    val server: String get() = prefs.getString("server", "") ?: ""
    val token: String get() = prefs.getString("token", "") ?: ""
    val username: String get() = prefs.getString("username", "") ?: ""
    val signedIn: Boolean get() = server.isNotEmpty() && token.isNotEmpty()
    fun api() = FilesApi(server, token)
    fun save(server: String, session: Session) {
        prefs.edit().putString("server", SyncApi.normalize(server)).putString("token", session.token)
            .putString("username", session.username).apply()
    }
    fun clear() { prefs.edit().clear().apply() }
}
