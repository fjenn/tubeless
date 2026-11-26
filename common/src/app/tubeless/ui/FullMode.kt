package app.tubeless.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tubeless.engine.Video

/**
 * Instant mode (the website without the extension, Platform.instant): what only the app or the
 * extension can do (HD downloads, stable volume, voice boost) asks calmly for one of them
 * instead of offering a button that can't work.
 */
object FullMode {
    const val APP_URL = "https://opena.tech/tubeless"
    /**
     * The install page (site/youtube.html, served at opena.tech/tubeless/web/install): it leads with the browser extension on Chrome, Edge, Brave and Firefox
     * ("Add Tubeless to Chrome") and with the bookmark on Safari and phones
     */
    const val BOOKMARK_URL = "https://opena.tech/tubeless/web/install"
    const val EXTENSION_URL = BOOKMARK_URL

    /** the feature someone reached for ("Downloads"); null: no prompt */
    var asking by mutableStateOf<String?>(null)

    fun ask(feature: String) { asking = feature }
}

/** the prompt itself; shown from App while [FullMode.asking] is set. One clear way on (the install page), the app small. */
@Composable
fun FullModeDialog(app: AppState, feature: String, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(feature) },
        text = {
            Column {
                Text(
                    tr("Get this and everything else: add Tubeless to your browser. Your own YouTube account, HD downloads, stable volume and voice boost."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    tr("Or use the Tubeless app."),
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp).clickable { app.platform.openExternal(FullMode.APP_URL); close() },
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = { app.platform.openExternal(FullMode.BOOKMARK_URL); close() }) { Text(tr("Add Tubeless")) }
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

/**
 * Instant mode's Download: the website can't read YouTube's video bytes, so it can't join HD picture and sound; it
 * hands YouTube's one-file 360p MP4 to the browser's own downloads instead (Platform.webSave), and points to the
 * app or the extension for HD.
 */
@Composable
fun WebSaveDialog(app: AppState, v: Video, close: () -> Unit) {
    var state by remember(v.id) { mutableStateOf("") }   // "", "busy", then webSave's answer
    LaunchedEffect(v.id) { app.platform.webSavePrepare(v.id) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(tr("Download")) },
        text = {
            Text(
                when {
                    state == "saved" -> tr("Saving to your browser's downloads (360p MP4, picture and sound).")
                    state == "tab" -> tr("The video opened in a new tab. Save it from there: in Chrome with ⋮ → Download, in Firefox with right-click → Save Video As.")
                    state.startsWith("error:") -> tr("This video can't be saved from the website.") + " " + state.removePrefix("error:")
                    else -> tr("This website saves videos at 360p. For HD and 4K, add Tubeless to your browser.")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                if (state == "" || state == "busy") {
                    TextButton(onClick = { app.platform.openExternal(FullMode.BOOKMARK_URL); close() }) { Text(tr("Get HD")) }
                    TextButton(enabled = state == "", onClick = { state = "busy"; app.platform.webSave(v.id, v.title) { state = it } }) { Text(tr("Save 360p")) }
                } else TextButton(onClick = close) { Text(tr("Done")) }
            }
        },
        dismissButton = { if (state == "" || state == "busy") TextButton(onClick = close) { Text(tr("Cancel")) } },
    )
}
