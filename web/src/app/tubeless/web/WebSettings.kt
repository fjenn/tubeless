package app.tubeless.web

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tubehaus.ui.AppState
import app.tubehaus.ui.FullModeRow
import app.tubehaus.ui.Screen
import app.tubehaus.ui.tr
import app.tubehaus.ui.SettingsSections
import app.tubehaus.ui.Sym
import kotlinx.coroutines.launch

/** Settings → Account (browser): the switch for signed-in mode through the browser's youtube.com session. */
object WebSettings {
    fun register(app: AppState) {
        SettingsSections.add(SettingsSections.Section(
            key = "web-account", icon = Sym.Person, title = "Account", sub = "Your youtube.com sign-in in this browser",
            rows = listOf(SettingsSections.Row("sign in youtube google account signed") { Account() }),
        ))
    }

    /** Instant mode (no extension): signing in needs the app or the extension; the data lives in this browser */
    fun registerInstant() {
        SettingsSections.add(SettingsSections.Section(
            key = "web-account", icon = Sym.Person, title = "Account", sub = "Instant mode: no sign-in, your data stays in this browser",
            rows = listOf(
                SettingsSections.Row("sign in youtube google account signed") { _ ->
                    FullModeRow(tr("Sign in"), tr("Needs the Tubeless app or the browser extension"))
                },
                SettingsSections.Row("data subscriptions history playlists import export browser") { app -> LocalData(app) },
            ),
        ))
    }

    @Composable
    private fun LocalData(app: AppState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(tr("Kept in this browser"), style = MaterialTheme.typography.bodyLarge)
            Text(tr("Your subscriptions, history, playlists and settings are saved in this browser only. Export them to keep a copy or to move them to the app."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { app.go(Screen.SettingsPage("data")) }) { Text(tr("Import and export")) }
        }
    }

    @Composable
    private fun Account() {
        var on by remember { mutableStateOf(WebAuth.on) }
        var browser by remember { mutableStateOf(WebAuth.browserSignedIn) }
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use my youtube.com sign-in", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (browser) "Home, subscriptions, history and likes come from your YouTube account. Videos still play without it."
                        else "Not signed in to youtube.com in this browser.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = on && browser, enabled = browser, onCheckedChange = { WebAuth.on = it; on = it })
            }
            Row {
                if (!browser) TextButton(onClick = { WebAuth.openSignIn() }) { Text("Sign in on youtube.com") }
                TextButton(onClick = { scope.launch { WebAuth.refresh(); browser = WebAuth.browserSignedIn } }) { Text("Check again") }
            }
        }
    }
}
