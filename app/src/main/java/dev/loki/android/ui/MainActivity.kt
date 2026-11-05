package dev.loki.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val files: FilesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LokiTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) {
                        if (model.signedIn) FoldersScreen(model) { files.open(model.serverUrl) }
                        else ConnectScreen(model) { files.open(model.server) }
                        if (files.visible) FilesDialog(files)
                        LaunchedEffect(files.visible) { if (!files.visible) model.accountChanged() }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.appVisible()
    }

    override fun onStop() {
        model.appHidden()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (model.signedIn) model.refresh()
    }
}

/** The dashboard's palette: a world tree at night. */
@Composable
fun LokiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7DE7A7),
            onPrimary = Color(0xFF072012),
            secondary = Color(0xFFD2BD7E),
            onSecondary = Color(0xFF221F12),
            background = Color(0xFF080E0B),
            onBackground = Color(0xFFE7EFE9),
            surface = Color(0xFF0F1A14),
            onSurface = Color(0xFFE7EFE9),
            surfaceVariant = Color(0xFF14231A),
            onSurfaceVariant = Color(0xFFBCCDC1),
            surfaceContainer = Color(0xFF0F1A14),
            surfaceContainerHigh = Color(0xFF14231A),
            surfaceContainerHighest = Color(0xFF1A2D21),
            outline = Color(0xFF335241),
            outlineVariant = Color(0xFF213528),
            error = Color(0xFFF39A92),
        ),
        content = content,
    )
}
