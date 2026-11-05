package dev.loki.android.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A native in-app popup, reached from the application menu. */
@Composable
fun FilesDialog(model: FilesViewModel) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*"), model::saveTo)
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Dialog(
        onDismissRequest = { if (model.share != null && !model.busy) model.up() else model.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.padding(12.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.9f).imePadding(),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Browse files", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = model::close) { Text("Close") }
                }
                if (!model.signedIn) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Sign in to browse shared files", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(model.server, { model.server = it }, label = { Text("Server address") },
                            singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(model.username, { model.username = it }, label = { Text("Username or email") },
                            singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true,
                            enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(code, { code = it }, label = { Text("Authentication code (if enabled)") },
                            singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { model.login(password, code); password = ""; code = "" },
                            enabled = !model.busy && model.server.isNotBlank() && model.username.isNotBlank() && password.isNotEmpty()) {
                            Text(if (model.busy) "Signing in…" else "Sign in")
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(model.server, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(model.server + "/account"))) }, enabled = !model.busy) { Text("Account settings") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = model::up, enabled = !model.busy && model.share != null) { Text("Up") }
                        Text(model.share?.let { "${it.name} / ${model.path}" } ?: "Shares", Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = model::refresh, enabled = !model.busy) { Text("Refresh") }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        if (model.share == null) {
                            if (model.shares.isEmpty() && !model.busy) item {
                                Text("No shares are available for this account.", Modifier.padding(vertical = 24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(model.shares, key = { it.id }) { share ->
                                ListItem(headlineContent = { Text(share.name) }, supportingContent = { Text("Shared folder") },
                                    trailingContent = { Text("›") }, modifier = Modifier.clickable(enabled = !model.busy) { model.load(share) })
                            }
                        } else {
                            if (model.listing.entries.isEmpty() && !model.busy) item {
                                Text("This folder is empty.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(model.listing.entries, key = { it.name }) { entry ->
                                val open = {
                                    if (entry.directory) model.share?.let { target ->
                                        model.load(target, if (model.path.isEmpty()) entry.name else "${model.path}/${entry.name}")
                                    } else {
                                        model.choose(entry)
                                        val clean = entry.name.replace(Regex("[\\\\/:\\p{Cntrl}]"), "_").take(180)
                                            .let { if (it.isBlank() || it == "." || it == "..") "download" else it }
                                        save.launch(clean)
                                    }
                                }
                                ListItem(headlineContent = { Text(entry.name) },
                                    supportingContent = { Text(if (entry.directory) "Folder" else Formatter.formatShortFileSize(context, entry.size)) },
                                    trailingContent = {
                                        if (entry.directory) Text("›") else TextButton(onClick = { open() }, enabled = !model.busy) { Text("Download") }
                                    }, modifier = Modifier.clickable(enabled = !model.busy) { open() })
                            }
                        }
                    }
                    if (model.share != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { model.share?.let { model.load(it, model.path, model.listing.page - 1) } },
                                enabled = !model.busy && model.listing.page > 0) { Text("Previous") }
                            Text("Page ${model.listing.page + 1}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { model.share?.let { model.load(it, model.path, model.listing.page + 1) } },
                                enabled = !model.busy && model.listing.page < 999 && (model.listing.page + 1) * 100L < model.listing.total) { Text("Next") }
                        }
                        if (model.listing.page == 999 && model.listing.total > 100000) Text("This folder exceeds the server's browsing limit.")
                    }
                }
                if (model.busy) {
                    val progress = model.progress
                    if (progress != null && progress.second > 0) {
                        LinearProgressIndicator(progress = { (progress.first.toFloat() / progress.second).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${Formatter.formatShortFileSize(context, progress.first)} of ${Formatter.formatShortFileSize(context, progress.second)}",
                            style = MaterialTheme.typography.bodySmall)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (model.downloading) TextButton(onClick = model::cancelDownload) { Text("Cancel download") }
                }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                model.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
