package dev.loki.android.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.loki.android.api.Folder
import dev.loki.android.data.Mapping

private val kinds = linkedMapOf(
    "retroarch-saves" to "RetroArch saves",
    "retroarch-states" to "RetroArch save states",
    "emulator-saves" to "Other emulator saves",
    "pc-game-saves" to "PC game saves",
    "documents" to "Documents",
    "generic" to "General files",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(model: AppViewModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    // The folder whose local directory is being chosen, kept across the picker.
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    // The live-sync notification needs permission on Android 13+; live sync works either way.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.changeLiveInBackground(true)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val folder = choosing
        choosing = null
        if (uri != null && folder != null) model.map(folder, uri)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Save sync")
                        Text("${model.displayName} · ${Uri.parse(model.serverUrl).host ?: model.serverUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { model.syncNow() }, enabled = model.syncing == null) {
                        if (model.syncing == "all") CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, "Sync everything now")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New server folder") }, onClick = { menu = false; creating = true })
                        DropdownMenuItem(
                            text = { Text("Live sync in background") },
                            trailingIcon = { Checkbox(checked = model.liveInBackground, onCheckedChange = null) },
                            onClick = {
                                menu = false
                                when {
                                    model.liveInBackground -> model.changeLiveInBackground(false)
                                    Build.VERSION.SDK_INT >= 33 -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else -> model.changeLiveInBackground(true)
                                }
                            },
                        )
                        DropdownMenuItem(text = { Text("Sign out this device") }, onClick = { menu = false; model.signOut() })
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            model.quota?.let { q ->
                item {
                    val context = LocalContext.current
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { if (q.limit > 0) (q.used.toFloat() / q.limit).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${Formatter.formatShortFileSize(context, q.used)} of ${Formatter.formatShortFileSize(context, q.limit)} used on the server, including history" +
                                if (model.liveInBackground) " · live sync on" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            model.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (model.folders.isEmpty() && !model.busy) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No sync folders yet", style = MaterialTheme.typography.titleMedium)
                        Text("Create one here or in the LOKI dashboard, then choose where it lives on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { creating = true }) { Text("New server folder") }
                    }
                }
            }
            items(model.folders, key = { it.id }) { folder ->
                FolderCard(
                    folder,
                    model.mappings[folder.id],
                    syncing = model.syncing == folder.id || model.syncing == "all",
                    onChoose = {
                        choosing = folder.id
                        picker.launch(null)
                    },
                    onSync = { model.syncNow(folder.id) },
                    onStop = { model.unmap(folder.id) },
                )
            }
            item {
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("New server folder")
                }
            }
        }
    }
    if (creating) NewFolderDialog(onDismiss = { creating = false }) { name, kind ->
        creating = false
        model.createFolder(name, kind)
    }
}

@Composable
private fun FolderCard(
    folder: Folder,
    mapping: Mapping?,
    syncing: Boolean,
    onChoose: () -> Unit,
    onSync: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(folder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(kinds[folder.kind] ?: folder.kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (mapping == null) {
                Text("Not synced on this device yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onChoose) { Text("Choose a folder on this device") }
            } else {
                Text(describe(mapping.treeUri), style = MaterialTheme.typography.bodyMedium)
                if (mapping.lastSync > 0) {
                    Text(
                        "${DateUtils.getRelativeTimeSpanString(mapping.lastSync)} · ${mapping.lastResult}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mapping.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSync, enabled = !syncing) { Text("Sync now") }
                    TextButton(onClick = onChoose) { Text("Change") }
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}

/** `primary:RetroArch/saves` reads better as `Internal storage › RetroArch/saves`. */
private fun describe(tree: Uri): String {
    val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    val place = if (volume == "primary") "Internal storage" else "SD card $volume"
    return if (path.isEmpty()) place else "$place › $path"
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("retroarch-saves") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New server folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("GBA saves") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("What it holds", style = MaterialTheme.typography.labelLarge)
                Column {
                    for ((value, label) in kinds) {
                        FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(name, kind) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
