package dev.loki.android.data

import android.content.Context
import android.net.Uri
import dev.loki.android.api.Session
import dev.loki.android.api.SyncApi
import dev.loki.android.api.FilesApi
import org.json.JSONObject
import java.io.File

/** Where a server folder lives on this device, and how its last sync went. */
data class Mapping(
    val folderId: String,
    val treeUri: Uri,
    val lastSync: Long = 0,
    val lastResult: String = "",
    val failed: Boolean = false,
)

/** The signed-in account and folder mappings. Kept in app-private storage. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("loki", Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences("loki_files", Context.MODE_PRIVATE)
    private val indexes = File(context.filesDir, "index").apply { mkdirs() }

    init {
        val legacy = context.getSharedPreferences("loki_files", Context.MODE_PRIVATE)
        if (prefs.getString("token", null).isNullOrEmpty() && !legacy.getString("token", null).isNullOrEmpty()) {
            prefs.edit().putString("server", legacy.getString("server", null))
                .putString("token", legacy.getString("token", null))
                .putString("username", legacy.getString("username", ""))
                .putString("display_name", legacy.getString("username", ""))
                .putBoolean("can_sync", legacy.getBoolean("can_sync", true)).apply()
            legacy.edit().clear().apply()
        }
    }
    var canSync: Boolean
        get() = prefs.getBoolean("can_sync", true)
        set(value) = prefs.edit().putBoolean("can_sync", value).apply()
    val server: String? get() = prefs.getString("server", null)
    val token: String? get() = prefs.getString("token", null)
    val username: String get() = prefs.getString("username", "") ?: ""
    val displayName: String get() = prefs.getString("display_name", "") ?: ""
    val signedIn: Boolean get() = server != null && token != null

    /** Whether live sync keeps running in the background after the app closes. */
    var live: Boolean
        get() = prefs.getBoolean("live", false)
        set(value) = prefs.edit().putBoolean("live", value).apply()

    fun filesApi() = FilesApi(server ?: error("No server configured."), token)

    fun api(): SyncApi = SyncApi(server ?: error("No server configured."), token)

    fun signIn(server: String, session: Session) {
        prefs.edit()
            .putString("server", SyncApi.normalize(server))
            .putString("token", session.token)
            .putString("username", session.username)
            .putString("display_name", session.displayName)
            .putBoolean("can_sync", session.canSync)
            .apply()
    }

    fun signOut() {
        prefs.edit().clear().apply()
        legacyPrefs.edit().clear().apply()
        indexes.listFiles()?.forEach { it.delete() }
    }

    fun mappings(): List<Mapping> {
        val o = JSONObject(prefs.getString("mappings", "{}") ?: "{}")
        return o.keys().asSequence().map { id ->
            val m = o.getJSONObject(id)
            Mapping(
                id,
                Uri.parse(m.getString("tree")),
                m.optLong("last_sync"),
                m.optString("last_result"),
                m.optBoolean("failed"),
            )
        }.toList()
    }

    fun mapping(folderId: String): Mapping? = mappings().firstOrNull { it.folderId == folderId }

    @Synchronized
    fun saveMapping(mapping: Mapping) {
        val o = JSONObject(prefs.getString("mappings", "{}") ?: "{}")
        o.put(
            mapping.folderId,
            JSONObject().put("tree", mapping.treeUri.toString()).put("last_sync", mapping.lastSync)
                .put("last_result", mapping.lastResult).put("failed", mapping.failed),
        )
        prefs.edit().putString("mappings", o.toString()).apply()
    }

    @Synchronized
    fun removeMapping(folderId: String) {
        val o = JSONObject(prefs.getString("mappings", "{}") ?: "{}")
        o.remove(folderId)
        prefs.edit().putString("mappings", o.toString()).apply()
        indexFile(folderId).delete()
    }

    fun indexFile(folderId: String) = File(indexes, "${folderId.filter { it.isLetterOrDigit() || it == '-' }}.json")
}
