package app.tubehaus.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tubehaus.engine.Sys
import kotlinx.coroutines.delay

/**
 * The device's speech recognition, for voice search (Platform.speech): iOS' Speech framework, Android's
 * SpeechRecognizer, the browser's Web Speech API. The listening screen and when it stops are shared (VoiceSearch).
 */
interface Speech {
    /**
     * Starts listening in [lang] (e.g. "en-US"). [partial] gets "" once the microphone is open (after any
     * permission question), then the words heard so far, again and again;
     * [ended] once at the end: the final words (null when nothing was heard) or why it failed
     */
    fun start(lang: String, partial: (String) -> Unit, ended: (text: String?, error: String?) -> Unit)
    /** stops listening; [ended] still comes with what was heard */
    fun stop()
}

/** the microphone (Material Icons "mic", Apache-2.0) */
internal val MicIcon by lazy { icon24("mic", "M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z") }

/** YouTube's mic button beside the search field: a 40 dp grey circle; nothing where the device can't listen */
@Composable
fun MicButton(app: AppState, modifier: Modifier = Modifier, onText: (String) -> Unit) {
    app.platform.speech ?: return
    var open by remember { mutableStateOf(false) }
    // QA driver: "set voice 1" opens the listening screen
    if (Parity.on("voice") && !open) LaunchedEffect(Unit) { Parity.flags.remove("voice"); open = true }
    Box(modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).clickable { open = true },
        contentAlignment = Alignment.Center) {
        Icon(MicIcon, tr("Search with your voice"), Modifier.size(22.dp))
    }
    if (open) VoiceSearch(app, onClose = { open = false }) { t -> open = false; onText(t) }
}

/**
 * The listening screen, as YouTube's: "Listening..." over the live words, a pulsing red mic. It searches
 * by itself once you stop talking (about 1.4 s of no new words), and says so when it heard nothing; a tap
 * on the mic stops (or listens again), ✕ closes.
 */
@Composable
fun VoiceSearch(app: AppState, onClose: () -> Unit, onText: (String) -> Unit) {
    val speech = app.platform.speech ?: return
    val c = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var heard by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var lastWord by remember { mutableStateOf(0L) }
    // the microphone is open (a permission question may come first: no time runs out before)
    var ready by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    val lang = app.store.settings.value.lang.ifEmpty { app.platform.deviceLanguage() }.let { l ->
        if ('-' in l) l else "$l-${app.platform.deviceRegion()}"
    }
    // which listening the callbacks belong to: one closed or replaced says nothing more
    val live = remember { intArrayOf(0) }
    DisposableEffect(round) {
        heard = ""; failed = null; listening = true; ready = false; lastWord = Sys.nowMs()
        val me = ++live[0]
        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        speech.start(lang, { p -> if (live[0] == me) { if (!ready) { ready = true; lastWord = Sys.nowMs() }; if (p.isNotBlank()) { heard = p; lastWord = Sys.nowMs() } } }) { t, err ->
            if (live[0] != me) return@start
            listening = false
            val text = t?.takeIf { it.isNotBlank() } ?: heard.takeIf { it.isNotBlank() }
            if (text != null) onText(text.trim())
            else failed = err ?: tr("Didn't hear that. Try again.")
        }
        onDispose { if (live[0] == me) { live[0]++; runCatching { speech.stop() } } }
    }
    // the end of talking: a pause after words (or nothing at all for a while) stops it, and it searches
    LaunchedEffect(round) {
        while (listening) {
            delay(200)
            if (!ready) continue
            val quiet = Sys.nowMs() - lastWord
            if ((heard.isNotBlank() && quiet > 1400) || (heard.isBlank() && quiet > 7000)) { speech.stop(); break }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(c.background)) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { Icon(Sym.Close.outline, tr("Close")) }
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 96.dp, start = 28.dp, end = 28.dp)) {
                Text(
                    when { failed != null -> failed!!; heard.isNotEmpty() -> heard; listening -> tr("Listening..."); else -> "" },
                    fontSize = if (heard.isNotEmpty() && failed == null) 26.sp else 28.sp, lineHeight = 34.sp,
                    fontWeight = if (heard.isNotEmpty() && failed == null) FontWeight.Normal else FontWeight.Medium,
                    color = if (heard.isNotEmpty() || failed != null) c.onSurface else c.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 560.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val pulse = rememberInfiniteTransition()
                val grow by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
                Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                    // the halo breathes while it listens
                    if (listening) Box(Modifier.size(84.dp).graphicsLayer { scaleX = grow; scaleY = grow; alpha = 1.25f - grow * 0.75f }
                        .background(Red.copy(alpha = 0.25f), CircleShape))
                    Box(
                        Modifier.size(84.dp).clip(CircleShape).background(if (listening) Red else c.onSurface.copy(alpha = 0.08f))
                            .clickable(remember { MutableInteractionSource() }, null) { if (listening) speech.stop() else round++ },
                        contentAlignment = Alignment.Center,
                    ) { Icon(MicIcon, tr(if (listening) "Stop" else "Try again"), Modifier.size(40.dp), tint = if (listening) Color.White else c.onSurface) }
                }
                Text(tr(if (listening) "Tap to stop" else "Tap the microphone to try again"), fontSize = 14.sp, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
