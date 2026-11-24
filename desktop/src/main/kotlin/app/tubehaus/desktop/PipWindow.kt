package app.tubehaus.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.tubehaus.ui.AppState
import app.tubehaus.ui.E2E
import app.tubehaus.ui.Perks
import app.tubehaus.ui.Sym
import app.tubehaus.ui.tr
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The desktop's picture in picture: a small borderless window that stays on top of every other one and
 * shows the same mpv picture (the player's own View, so nothing is decoded twice). Drag it anywhere,
 * drag its corner to resize (it keeps 16:9), click to play or pause, double-click or ⤢ to go back to
 * the app, ✕ to close it. The app's page shows a note where the video was meanwhile.
 */
object PipWindow {
    lateinit var app: AppState
    var mainFrame: () -> java.awt.Frame? = { null }
    private var win: ComposeWindow? = null

    val open get() = win != null

    fun toggle() { if (win != null) close() else show() }

    fun show() {
        if (win != null || app.playback.video == null) return
        val w = ComposeWindow()
        w.isUndecorated = true
        w.isAlwaysOnTop = true
        w.title = "Tubeless"
        w.type = java.awt.Window.Type.UTILITY
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val pw = 480; val ph = 270
        w.setBounds(screen.x + screen.width - pw - 24, screen.y + screen.height - ph - 24, pw, ph)
        w.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { close() }
        })
        w.setContent { PipContent(app, w) }
        win = w
        Perks.pipOpen = true
        w.isVisible = true
        E2E.log("pip", "on" to true, "videoId" to app.playback.video?.id, "window" to "desktop")
    }

    fun close(toApp: Boolean = false) {
        val w = win ?: return
        win = null
        Perks.pipOpen = false
        w.isVisible = false
        w.dispose()
        E2E.log("pip", "on" to false)
        if (toApp) mainFrame()?.let { f -> if (f.state == java.awt.Frame.ICONIFIED) f.state = java.awt.Frame.NORMAL; f.toFront(); f.requestFocus() }
    }
}

@Composable
internal fun PipContent(app: AppState, w: java.awt.Window) {
    val st by app.player.state.collectAsState()
    var hover by remember { mutableStateOf(false) }
    // the video was closed in the app: nothing to show
    LaunchedEffect(app.playback.video == null) { if (app.playback.video == null) PipWindow.close() }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter, PointerEventType.Move -> hover = true
                            PointerEventType.Exit -> hover = false
                        }
                    }
                }
            }
            // drag: the window follows the pointer on the screen (absolute, so it never jitters); a click
            // plays or pauses, a double click goes back to the app
            .pointerInput(Unit) {
                var lastClick = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val m0 = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                    val w0 = w.location
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val m = MouseInfo.getPointerInfo()?.location ?: continue
                        val dx = m.x - m0.x; val dy = m.y - m0.y
                        if (moved || kotlin.math.abs(dx) + kotlin.math.abs(dy) > 3) {
                            moved = true
                            w.setLocation(w0.x + dx, w0.y + dy)
                            c.consume()
                        }
                    }
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        if (now - lastClick < 350) { PipWindow.close(toApp = true); return@awaitEachGesture }
                        lastClick = now
                        if (app.player.state.value.playing) app.player.pause() else app.player.play()
                    }
                }
            },
    ) {
        app.player.View(Modifier.fillMaxSize())
        AnimatedVisibility(hover || !st.playing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0x55000000))) {
                Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(onClick = { PipWindow.close(toApp = true) }) { Icon(Sym.Fullscreen.fill, tr("Back to the app"), tint = Color.White) }
                    IconButton(onClick = { PipWindow.close() }) { Icon(Sym.Close.fill, tr("Close"), tint = Color.White) }
                }
                IconButton(
                    onClick = { if (st.playing) app.player.pause() else app.player.play() },
                    modifier = Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color(0x66000000)),
                ) { Icon(if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr(if (st.playing) "Pause" else "Play"), tint = Color.White, modifier = Modifier.size(36.dp)) }
                // the corner: drag to resize, keeping 16:9
                Box(
                    Modifier.align(Alignment.BottomEnd).size(18.dp).padding(4.dp).background(Color(0x99FFFFFF), CircleShape)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(); down.consume()
                                val m0 = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                                val s0 = w.size
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    val m = MouseInfo.getPointerInfo()?.location ?: continue
                                    val nw = (s0.width + (m.x - m0.x)).coerceIn(240, 1280)
                                    w.setSize(nw, nw * 9 / 16)
                                }
                            }
                        },
                )
            }
        }
        // a thin progress line along the bottom
        if (st.durationMs > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x44FFFFFF))) {
            Box(Modifier.fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).height(3.dp).background(Color(0xFFFF0033)))
        }
    }
}
