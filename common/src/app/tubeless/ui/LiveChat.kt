package app.tubeless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubeless.engine.LiveChat
import kotlinx.coroutines.delay

/** the chat beside a live stream (it follows the stream) or a finished one (it follows the video's time) */
object LiveChatState {
    /** the viewer folded it away ("Show chat" brings it back) */
    var hidden by mutableStateOf(false)
}

/**
 * Live chat, or its replay, in a rounded box: a header ("Live chat" / "Chat replay") with ✕, the
 * messages scrolling up (Super Chats in their colour), read only. Nothing is drawn when the video
 * has no chat.
 */
@Composable
fun LiveChatPanel(app: AppState, videoId: String, height: Dp, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    var start by remember(videoId) { mutableStateOf<LiveChat.Start?>(null) }
    var none by remember(videoId) { mutableStateOf(false) }
    val msgs = remember(videoId) { mutableStateListOf<LiveChat.Message>() }
    LaunchedEffect(videoId) {
        start = runCatching { LiveChat.start(videoId) }.getOrNull()
        if (start == null) none = true
    }
    if (none) return
    val s = start
    if (LiveChatState.hidden) {
        if (s != null) Box(modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, c.outlineVariant, RoundedCornerShape(18.dp))
            .clickable { LiveChatState.hidden = false }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(if (s.replay) tr("Show chat replay") else tr("Show chat"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        return
    }
    val list = rememberLazyListState()
    if (s != null && !s.replay) LaunchedEffect(s.token) {
        var tok: String? = s.token
        while (tok != null) {
            val b = runCatching { LiveChat.live(tok!!) }.getOrNull()
            if (b == null) { delay(5000); continue }
            val have = msgs.mapTo(HashSet()) { it.id }
            msgs.addAll(b.messages.filter { it.id !in have })
            if (msgs.size > 300) repeat(msgs.size - 300) { msgs.removeAt(0) }
            tok = b.next
            delay(b.waitMs)
        }
    }
    // the replay: what was said up to the video's position, fetched about half a minute ahead
    val pos = app.player.state.collectAsState().value.positionMs
    val shown = if (s?.replay == true) msgs.filter { it.offsetMs <= pos }.takeLast(150) else msgs
    if (s != null && s.replay) LaunchedEffect(s.token) {
        var next: String? = null
        var from = -1L
        var to = -1L
        while (true) {
            val p = app.player.state.value.positionMs
            val jumped = p < from - 2000 || p > to + 30_000
            if (from < 0 || jumped || (p > to - 5000 && next != null)) {
                val useStart = from < 0 || jumped
                val b = runCatching { LiveChat.replay(if (useStart) s.token else next!!, p) }.getOrNull()
                if (b != null) {
                    if (useStart) { msgs.clear(); from = p }
                    val have = msgs.mapTo(HashSet()) { it.id }
                    msgs.addAll(b.messages.filter { it.id !in have })
                    if (msgs.size > 600) repeat(msgs.size - 600) { msgs.removeAt(0) }
                    to = msgs.maxOfOrNull { it.offsetMs } ?: p
                    if (to < p) to = p + 20_000
                    next = b.next
                }
            }
            delay(1000)
        }
    }
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.animateScrollToItem(shown.size - 1) }
    Column(modifier.fillMaxWidth().height(height).padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.outlineVariant, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s?.replay == true) tr("Chat replay") else tr("Live chat"), fontSize = 16.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { LiveChatState.hidden = true }) { Icon(Sym.Close.outline, tr("Hide chat")) }
        }
        HorizontalDivider(color = c.outlineVariant)
        if (s == null || (msgs.isEmpty() && !s.replay)) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        else LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp), state = list) {
            items(shown, key = { it.id + it.offsetMs }) { m -> ChatLine(m) }
        }
    }
}

@Composable
private fun ChatLine(m: LiveChat.Message) {
    val c = MaterialTheme.colorScheme
    if (m.system) {
        Text(m.text, fontSize = 12.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        return
    }
    val paid = m.amount.isNotEmpty()
    val bg = if (paid && m.color != 0L) Color(m.color.toInt()) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (paid) 8.dp else 0.dp, vertical = if (paid) 4.dp else 0.dp)
            .clip(RoundedCornerShape(if (paid) 8.dp else 0.dp)).background(bg).padding(horizontal = if (paid) 8.dp else 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(m.avatar, 24)
        val nameColor = when (m.badge) {
            "owner" -> Color(0xFFFFD600)
            "moderator" -> Color(0xFF5E84F1)
            "member" -> Color(0xFF2BA640)
            else -> c.onSurfaceVariant
        }
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = if (paid) Color.Black.copy(alpha = 0.7f) else nameColor, fontWeight = FontWeight.Medium)) { append(m.author) }
            if (paid) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.Black)) { append("  " + m.amount) }
            append("  ")
            withStyle(SpanStyle(color = if (paid) Color.Black else c.onSurface)) { append(m.text) }
        }, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 12.dp))
    }
}
