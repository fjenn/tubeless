package app.tubeless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Settings → Player perks (through the M0 SettingsSections hook): the perks' switches and SponsorBlock's whitelist */
internal object PerkSettings {
    fun register() {
        val rows = listOf(
            SettingsSections.Row("stable volume loudness normalize") { _ ->
                if (Perks.fx?.canStable == true) PerkToggle(tr("Stable volume"), tr("Evens out loud and quiet parts"), Perks.prefs.stableVolume) { b -> Perks.update { it.copy(stableVolume = b) } }
            },
            SettingsSections.Row("voice boost speech dialogue") { _ ->
                if (Perks.fx?.canVoice == true) PerkToggle(tr("Voice boost"), tr("Speech stands out from music and noise"), Perks.prefs.voiceBoost) { b -> Perks.update { it.copy(voiceBoost = b) } }
            },
            SettingsSections.Row("stable volume voice boost sound app extension") { app ->
                if (app.platform.instant) FullModeRow(tr("Stable volume and voice boost"), tr("Comes with Tubeless in your browser"))
            },
            SettingsSections.Row("auto speed words") { app ->
                PerkToggle(tr("Auto speed"), tr("The speed follows how fast people speak (videos with captions)"), Perks.prefs.autoSpeed) { b -> AutoSpeed.set(app, b) }
            },
            SettingsSections.Row("skip silence") { _ ->
                if (Perks.fx?.canSkipSilence == true) PerkToggle(tr("Skip silence"), tr("Silent stretches are skipped"), Perks.prefs.skipSilence) { b -> Perks.update { it.copy(skipSilence = b) } }
            },
            SettingsSections.Row("jump ahead most replayed") { _ ->
                PerkToggle(tr("Jump ahead"), tr("After skipping forward, offers to jump to where most viewers skip to"), Perks.prefs.jumpAhead) { b -> Perks.update { it.copy(jumpAhead = b) } }
            },
            SettingsSections.Row("hold speed press 2x") { _ -> HoldSpeed() },
            SettingsSections.Row("sponsorblock whitelist channels allow") { _ -> Whitelist() },
        )
        SettingsSections.add(SettingsSections.Section("perks", Sym.Speed, "Player perks", "Stable volume, voice boost, auto speed, jump ahead", rows))
    }
}

@Composable
private fun PerkToggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).toggleable(on, role = Role.Switch) { set(it) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, null, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = accent, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
            uncheckedBorderColor = Color.Transparent,
        ))
    }
}

@Composable
private fun HoldSpeed() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(tr("Press and hold the video"), style = MaterialTheme.typography.bodyLarge)
        Text(tr("It plays at this speed until you let go"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (x in listOf(1.5f, 2f, 2.5f, 3f, 4f)) {
                val on = Perks.prefs.holdSpeed == x
                val cs = MaterialTheme.colorScheme
                Text(speedText(x), Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.onSurface else cs.onSurface.copy(alpha = 0.1f))
                    .clickable { Perks.update { it.copy(holdSpeed = x) } }.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = if (on) cs.surface else cs.onSurface, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Whitelist() {
    val list = Perks.prefs.sponsorAllow
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(tr("SponsorBlock: channels never skipped"), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (list.isEmpty()) tr("None. Turn off \"Skip sponsors on this channel\" in the player's menu to add one") else tr("Their sponsor parts play; the button still offers to skip each"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for ((id, name) in list) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name.ifEmpty { id }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { Perks.update { it.copy(sponsorAllow = it.sponsorAllow - id) } }) { Icon(Sym.Close.outline, tr("Remove")) }
        }
    }
}
