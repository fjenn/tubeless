package app.tubehaus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Instant mode (the website without the extension, Platform.instant): what only the app or the
 * extension can do (sign-in, downloads, stable volume, voice boost) asks calmly for one of them
 * instead of offering a button that can't work.
 */
object FullMode {
    const val APP_URL = "https://github.com/fjenn/tubeless-publik/releases"
    const val EXTENSION_URL = "https://github.com/fjenn/tubeless-publik/releases"

    /** the feature someone reached for ("Downloads"); null: no prompt */
    var asking by mutableStateOf<String?>(null)

    fun ask(feature: String) { asking = feature }
}

/** the prompt itself; shown from App while [FullMode.asking] is set */
@Composable
fun FullModeDialog(app: AppState, feature: String, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(feature) },
        text = {
            Text(
                tr("This needs the Tubeless app or the browser extension. The website plays videos without either, but your browser keeps it from signing in, saving videos or changing the sound."),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = { app.platform.openExternal(FullMode.EXTENSION_URL); close() }) { Text(tr("Add the extension")) }
                TextButton(onClick = { app.platform.openExternal(FullMode.APP_URL); close() }) { Text(tr("Get the app")) }
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Not now")) } },
    )
}

/** a settings line in Instant mode for a feature that needs the app or the extension; opens the prompt */
@Composable
fun FullModeRow(title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().clickable { FullMode.ask(title) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Sym.Extension.outline, null, Modifier.padding(start = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
