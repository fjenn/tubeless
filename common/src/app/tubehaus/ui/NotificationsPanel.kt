package app.tubehaus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import app.tubehaus.engine.Video

/** one notification, as YouTube's bell lists them */
data class Notice(
    val id: String,
    /** the line itself ("3Blue1Brown uploaded: …") */
    val text: String,
    val avatar: String = "",
    /** the video's picture on the right, when it is about one */
    val thumb: String = "",
    /** "2 hours ago" */
    val age: String = "",
    val read: Boolean = false,
    /** YouTube's "Important" group (bell-all channels, replies, mentions); the rest is "More notifications" */
    val important: Boolean = false,
    val videoId: String? = null,
    val channelId: String? = null,
    val short: Boolean = false,
)

/**
 * Where the bell's notifications come from (S4 fills it with the signed-in inbox,
 * `notification/get_notification_menu`, and local new-upload alerts). The default has none.
 */
interface NotificationSource {
    /** false: the panel says how to get notifications instead of "nothing new" */
    val available: Boolean get() = false
    suspend fun load(): List<Notice> = emptyList()
    /** the red badge's count */
    suspend fun unread(): Int = 0
    /** the panel was opened: the badge clears */
    suspend fun seen() {}
    /** ⋮ → Hide this notification */
    suspend fun hide(n: Notice) {}
    /** ⋮ → Turn off notifications from this channel */
    suspend fun mute(n: Notice) {}
}

object NotificationCenter {
    var source: NotificationSource = AccountNotifications
    /** bumped by a source when it has something new, so the badge asks again */
    val changed = mutableIntStateOf(0)
}

/** the bell in the masthead: a red count badge; a click drops the panel down under it */
@Composable
fun NotificationBell(app: AppState) {
    var open by remember { mutableStateOf(false) }
    var unread by remember { mutableIntStateOf(0) }
    val bump = NotificationCenter.changed.intValue
    // asked again on a sign-in or out, when the panel closes, and every five minutes
    LaunchedEffect(bump, open, app.account) {
        while (true) {
            unread = runCatching { NotificationCenter.source.unread() }.getOrDefault(0)
            kotlinx.coroutines.delay(5 * 60_000L)
        }
    }
    LaunchedEffect(app.current) { open = false }
    if (Parity.on("bell") && !open) LaunchedEffect(Unit) { open = true; Parity.flags.remove("bell") }
    Box {
        IconButton(onClick = { open = !open }) {
            Icon(if (open) WebIcons.BellFill else WebIcons.Bell, tr("Notifications"), Modifier.size(24.dp))
        }
        if (unread > 0) Text(
            if (unread > 9) "9+" else "$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp).clip(RoundedCornerShape(8.dp))
                .background(Red).padding(horizontal = 4.dp),
        )
        if (open) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(with(density) { 8.dp.roundToPx() }, with(density) { 44.dp.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) { NotificationsPanel(app) { open = false } }
        }
    }
}

/** the panel: Notifications ⚙, then "Important" and "More notifications" */
@Composable
fun NotificationsPanel(app: AppState, close: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val src = NotificationCenter.source
    val list = remember { mutableStateListOf<Notice>() }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        runCatching { src.load() }.getOrNull()?.let { list.clear(); list.addAll(it) }
        loading = false
        runCatching { src.seen() }
        NotificationCenter.changed.intValue++
    }
    Surface(Modifier.width(480.dp).heightIn(max = 640.dp), shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp, color = c.surface) {
        Column {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Notifications"), fontSize = 16.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { close(); app.go(Screen.Settings) }) { Icon(Sym.Settings.outline, tr("Settings")) }
            }
            HorizontalDivider(color = c.outlineVariant)
            when {
                loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(WebIcons.Bell, null, Modifier.size(96.dp), tint = c.onSurfaceVariant.copy(alpha = 0.5f))
                    Text(tr("Your notifications live here"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        if (src.available) tr("Nothing new right now. New videos from channels you follow and replies to you show up here.")
                        else tr("Sign in to get notifications from your subscriptions, replies and mentions."),
                        fontSize = 14.sp, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                else -> LazyColumn(Modifier.heightIn(max = 580.dp)) {
                    val important = list.filter { it.important }
                    val more = list.filter { !it.important }
                    if (important.isNotEmpty()) {
                        item(key = "h:imp") { Section(tr("Important")) }
                        items(important, key = { "i:" + it.id }) { NoticeRow(app, it, close, onHide = { scope.launch { runCatching { src.hide(it) }; list.remove(it) } }, onMute = { scope.launch { runCatching { src.mute(it) } } }) }
                    }
                    if (more.isNotEmpty()) {
                        item(key = "h:more") { Section(tr("More notifications")) }
                        items(more, key = { "m:" + it.id }) { NoticeRow(app, it, close, onHide = { scope.launch { runCatching { src.hide(it) }; list.remove(it) } }, onMute = { scope.launch { runCatching { src.mute(it) } } }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun NoticeRow(app: AppState, n: Notice, close: () -> Unit, onHide: () -> Unit, onMute: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable {
            close()
            when {
                n.videoId != null -> app.watch(Video(n.videoId, "", short = n.short))
                n.channelId != null -> app.go(Screen.Channel(n.channelId))
            }
        }.padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(16.dp).padding(top = 20.dp), contentAlignment = Alignment.Center) {
            if (!n.read) Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF3EA6FF)))
        }
        Avatar(n.avatar, 48)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp)) {
            Text(n.text, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (n.age.isNotEmpty()) Text(n.age, fontSize = 12.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (n.thumb.isNotEmpty()) Img(n.thumb, Modifier.width(86.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Sym.MoreVert.fill, tr("More"), Modifier.size(18.dp)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(tr("Hide this notification")) }, onClick = { menu = false; onHide() })
                if (n.channelId != null) DropdownMenuItem(text = { Text(tr("Turn off all from this channel")) }, onClick = { menu = false; onMute() })
            }
        }
    }
}

/** the signed-in account's bell (S4's engine/Notifications.kt); signed out it has nothing and says how to get some */
object AccountNotifications : NotificationSource {
    override val available: Boolean get() = app.tubehaus.engine.YouTubeAccount.signedIn
    override suspend fun load(): List<Notice> {
        val p = app.tubehaus.engine.Notifications.inbox() ?: return emptyList()
        if (p.loggedOut) return emptyList()
        val split = p.items.any { it.section.isNotEmpty() }
        return p.items.mapIndexed { i, n ->
            Notice(n.id, n.text, n.avatar, n.thumb, n.sent, n.read,
                important = if (split) n.section.contains("mportant") else false, videoId = n.videoId, channelId = n.channelId, short = n.short)
        }
    }
    override suspend fun unread(): Int = app.tubehaus.engine.Notifications.unseen() ?: 0
}
