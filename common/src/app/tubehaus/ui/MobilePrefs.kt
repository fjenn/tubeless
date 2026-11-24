package app.tubehaus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tubehaus.data.JsonFile
import kotlinx.serialization.Serializable

/**
 * Phone settings of S10 (docs/PLAN.md): background play as YouTube offers it (Always, Headphones or
 * speakers on the go, Off) and picture-in-picture when leaving the app. Kept in a file of their own
 * (mobile.json), next to settings.json, so the shared Settings data class stays untouched; "Off" also
 * clears the older `Settings.backgroundPlay` switch so code that still reads it agrees.
 */
@Serializable
data class MobileSettings(
    /** 0 always, 1 only with headphones or an external speaker (Bluetooth, AirPlay, car), 2 off */
    val background: Int = 0,
    /** leaving the app while a video plays moves it into a small window (Android 12+, iOS) */
    val autoPip: Boolean = true,
)

enum class BackgroundPlay { ALWAYS, HEADPHONES, OFF }

object MobilePrefs {
    private var file: JsonFile<MobileSettings>? = null
    val value: MobileSettings get() = file?.value ?: MobileSettings()
    val mode: BackgroundPlay get() = BackgroundPlay.entries.getOrElse(value.background) { BackgroundPlay.ALWAYS }

    /**
     * Whether a video may keep playing (as sound) once the app is out of sight. [externalAudio]: the
     * sound goes to headphones or another speaker now. Music always may, as in a music app.
     */
    fun mayPlayInBackground(app: AppState, externalAudio: Boolean): Boolean = when {
        app.playback.music && !app.playback.withVideo -> true
        else -> when (mode) {
            BackgroundPlay.ALWAYS -> true
            BackgroundPlay.HEADPHONES -> externalAudio
            BackgroundPlay.OFF -> false
        }
    }

    /** reads mobile.json and adds the "Phone" card to Settings (touch platforms; call once at start) */
    fun register(app: AppState) {
        if (file == null) {
            val f = JsonFile(app.platform.dataDir / "mobile.json", MobileSettings.serializer(), MobileSettings(), app.scope)
            // an older install with background play switched off starts as "Off"
            if (!app.store.settings.value.backgroundPlay && f.value.background == 0) f.update { it.copy(background = 2) }
            file = f
        }
        if (!app.platform.touch || app.platform.tv) return
        SettingsSections.add(SettingsSections.Section(
            key = "mobile", icon = Sym.PlayCircle, title = tr("Background & picture-in-picture"),
            sub = tr("Keep playing with the screen off or in another app"),
            rows = listOf(
                SettingsSections.Row(tr("Background play") + " screen off lock headphones speaker audio") { a -> BackgroundRow(a) },
                SettingsSections.Row(tr("Picture-in-picture") + " pip small window home") { a -> PipRow(a) },
            ),
        ))
    }

    fun set(app: AppState, f: (MobileSettings) -> MobileSettings) {
        val file = file ?: return
        file.update(f)
        val on = file.value.background != 2
        if (app.store.settings.value.backgroundPlay != on) app.store.settings.update { it.copy(backgroundPlay = on) }
    }

    internal val state get() = file?.state
}

@Composable
private fun BackgroundRow(app: AppState) {
    val s by (MobilePrefs.state ?: return).collectAsState()
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(tr("Background play"), style = MaterialTheme.typography.titleSmall)
        Text(
            when (s.background) {
                1 -> tr("Keeps playing with the screen off or in another app only while headphones or an external speaker are connected")
                2 -> tr("Videos pause when you leave the app or lock the screen. Music keeps playing")
                else -> tr("Videos keep playing as sound with the screen off or in another app")
            },
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
        )
        // a segmented choice, as the system's own (three short words fit a phone's width)
        val shape = RoundedCornerShape(12.dp)
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(40.dp).clip(shape).background(c.surfaceContainerHigh).border(1.dp, c.outline, shape).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((i, label) in listOf(tr("Always"), tr("Headphones"), tr("Off")).withIndex()) {
                val on = s.background == i
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(if (on) c.background else c.surfaceContainerHigh)
                    .clickable(role = Role.RadioButton) { MobilePrefs.set(app) { it.copy(background = i) } },
                    contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) c.onSurface else c.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PipRow(app: AppState) {
    val s by (MobilePrefs.state ?: return).collectAsState()
    Row(Modifier.fillMaxWidth().clickable { MobilePrefs.set(app) { it.copy(autoPip = !it.autoPip) } }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(tr("Picture-in-picture"), style = MaterialTheme.typography.titleSmall)
            Text(tr("Going home while a video plays keeps it in a small window over other apps"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(s.autoPip, { b -> MobilePrefs.set(app) { it.copy(autoPip = b) } })
    }
}
