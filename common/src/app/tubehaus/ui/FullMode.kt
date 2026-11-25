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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Video

/**
 * Instant mode (the website without the extension, Platform.instant): what only the app or the
 * extension can do (HD downloads, stable volume, voice boost) asks calmly for one of them
 * instead of offering a button that can't work.
 */
object FullMode {
    const val APP_URL = "https://github.com/fjenn/tubeless-publik/releases"
    const val EXTENSION_URL = "https://github.com/fjenn/tubeless-publik/releases"
    /** Tubeless on youtube.com: the bookmark that runs this same app inside youtube.com (site/youtube.html) */
    const val BOOKMARK_URL = "https://fjenn.github.io/tubeless-publik/youtube.html"

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
                tr("This needs Tubeless on youtube.com (one bookmark, nothing to install), the browser extension or the Tubeless app. This website plays videos without them, but your browser keeps it from signing in, saving videos in HD or changing the sound."),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = { app.platform.openExternal(FullMode.BOOKMARK_URL); close() }) { Text(tr("Use on youtube.com")) }
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
                    else -> tr("The website saves videos at 360p, picture and sound in one MP4, into your browser's downloads. Tubeless on youtube.com (one bookmark), the extension and the app save up to 4K.")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                if (state == "" || state == "busy") {
                    TextButton(onClick = { app.platform.openExternal(FullMode.BOOKMARK_URL); close() }) { Text(tr("HD on youtube.com")) }
                    TextButton(enabled = state == "", onClick = { state = "busy"; app.platform.webSave(v.id, v.title) { state = it } }) { Text(tr("Save 360p")) }
                } else TextButton(onClick = close) { Text(tr("Done")) }
            }
        },
        dismissButton = { if (state == "" || state == "busy") TextButton(onClick = close) { Text(tr("Cancel")) } },
    )
}
