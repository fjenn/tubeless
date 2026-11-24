package app.tubehaus.ios

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.tubehaus.ui.AppState
import app.tubehaus.ui.Sym
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** how far (0..1 of the gesture) the swipe must go for Back when let go */
private const val COMMIT = 0.3f

/**
 * The iPhone's swipe from the left edge to go back, as in every iOS app (and in Yattee). Compose's
 * iOS back gesture (a screen-edge pan in the view controller) drives it: while the finger moves the
 * page eases right a little and a round arrow follows it out of the edge; past the point where
 * letting go goes back, the arrow fills and the motor ticks once. Let go there: AppState.back(),
 * the same Back as everywhere (a panel closes first, the video page drops into the mini player,
 * a page goes to the one before). Not in full screen, where the edges belong to the player.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EdgeBack(app: AppState, content: @Composable BoxScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    var past by remember { mutableStateOf(false) }
    var touchY by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val enabled = !app.fullscreen && (app.canBack || app.backHooks.isNotEmpty())
    PredictiveBackHandler(enabled) { events ->
        past = false
        try {
            events.collect { e ->
                touchY = e.touchY
                progress.snapTo(e.progress)
                val now = e.progress >= COMMIT
                if (now && !past) Haptics.play(HapticFeedbackType.GestureThresholdActivate)
                past = now
            }
            // let go: past the point it goes back, before it the swipe is undone
            scope.launch { progress.animateTo(0f, tween(180)) }
            if (past) app.back()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(0f, tween(180)) }
            throw e
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = progress.value * 28.dp.toPx() }, content = content)
        val p = progress.value
        if (p > 0.001f) {
            val c = MaterialTheme.colorScheme
            val on = p >= COMMIT
            Box(
                Modifier.align(Alignment.TopStart)
                    .offset { IntOffset((-44.dp.toPx() + p.coerceAtMost(0.6f) / 0.6f * 60.dp.toPx()).roundToInt(), (touchY - 22.dp.toPx()).roundToInt()) }
                    .size(44.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                    .background(if (on) c.onSurface else c.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Sym.ArrowBack.fill, null, Modifier.size(22.dp), tint = if (on) c.surface else c.onSurface)
            }
        }
    }
}
