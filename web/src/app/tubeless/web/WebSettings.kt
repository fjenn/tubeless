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
