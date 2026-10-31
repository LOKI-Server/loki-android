package dev.loki.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConnectScreen(model: AppViewModel) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("LOKI · Save sync", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
            Text("Connect to LOKI", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Keep emulator saves and other folders the same on every device, with every earlier version kept on your server.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val discovery = model.discovery
            val pairing = model.pairing
            when {
                discovery == null -> ServerStep(model)
                pairing != null -> PairingStep(model, pairing.userCode)
                else -> SignInStep(model, discovery.name)
            }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ServerStep(model: AppViewModel) {
    OutlinedTextField(
        value = model.server,
        onValueChange = { model.server = it },
        label = { Text("Server address") },
        placeholder = { Text("loki.example.com") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = model::discover,
        enabled = !model.busy && model.server.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (model.busy) "Connecting…" else "Continue") }
}

@Composable
private fun SignInStep(model: AppViewModel, serverName: String) {
    var password by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(serverName, fontWeight = FontWeight.SemiBold)
                Text(model.server, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = model::forgetServer) { Text("Change") }
        }
    }
    if (!password) {
        Text("Pair this device by approving a short code in the LOKI dashboard. Your password never touches this device.")
        Button(onClick = model::startPairing, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Pair with a code") }
        TextButton(onClick = { password = true }, modifier = Modifier.fillMaxWidth()) { Text("Sign in with a password instead") }
    } else {
        var username by rememberSaveable { mutableStateOf("") }
        var secret by rememberSaveable { mutableStateOf("") }
        var code by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(username, { username = it }, label = { Text("Username or email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            secret, { secret = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            code, { code = it }, label = { Text("Authentication code (if enabled)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { model.login(username, secret, code) },
            enabled = !model.busy && username.isNotBlank() && secret.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in") }
        TextButton(onClick = { password = false }, modifier = Modifier.fillMaxWidth()) { Text("Pair with a code instead") }
    }
}

@Composable
private fun PairingStep(model: AppViewModel, code: String) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Approve this code in LOKI", style = MaterialTheme.typography.titleMedium)
            Text(code, fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Open Save sync → Pair a device in the dashboard on any signed-in browser, or open the approval page here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Waiting for approval…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Button(onClick = { model.approvalIntent()?.let(context::startActivity) }, modifier = Modifier.fillMaxWidth()) { Text("Open approval page") }
    OutlinedButton(onClick = model::cancelPairing, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}
