package app.tubeless.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.tubeless.engine.Item
import app.tubeless.engine.Sys
import app.tubeless.engine.SearchOptions
import app.tubeless.engine.YouTubeAccount
import app.tubeless.engine.Video
import app.tubeless.engine.YouTube
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The computer's layout as on YouTube's website (docs/parity/CHECKLIST.md): a masthead with the search
// box in the middle, the guide on the left (full, or the mini guide of icons), Home's chip bar and a
// grid of 3 to 6 columns with a Shorts shelf. Phones, tablets and TVs keep their own layouts.

/** a text box in the masthead has the keyboard: the player's keys (K, J, L, T, space...) must not take it */
object WebTyping { var active = false }

/** true for the website-style layout: a computer with a mouse, a window wider than a phone */
@Composable
fun webDesk(app: AppState): Boolean = !app.platform.touch && !app.platform.tv && !LocalCompact.current

/** an icon from a 24-unit Material path (Apache-2.0) */
internal fun icon24(name: String, d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(d), fill = SolidColor(Color.Black)).build()

internal object WebIcons {
    val Bell by lazy { icon24("bell", "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2zm-2 1H8v-6c0-2.48 1.51-4.5 4-4.5s4 2.02 4 4.5v6z") }
    val BellFill by lazy { icon24("bellFill", "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z") }
    val Theater by lazy { icon24("theater", "M19 6H5c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 10H5V8h14v8z") }
    val Chat by lazy { icon24("chat", "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 9h12v2H6V9zm8 5H6v-2h8v2zm4-6H6V6h12v2z") }
}

// ------------------------------------------------------------------------------------------ guide

/** one row of the full guide: icon and label, the open page in a grey rounded box and bold */
@Composable
private fun GuideRow(icon: ImageVector?, label: String, selected: Boolean, avatar: String? = null, dot: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatar != null) Avatar(avatar, 24) else if (icon != null) Icon(icon, null, Modifier.size(24.dp))
        Text(label, Modifier.weight(1f).padding(start = 24.dp), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (dot) Box(Modifier.size(4.dp).clip(CircleShape).background(Color(0xFF3EA6FF)))
    }
}

@Composable
private fun GuideHeader(label: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (onClick != null) Icon(Sym.ChevronRight.outline, null, Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** the full guide (240 dp) or, folded, the mini guide (72 dp) */
@Composable
fun DeskGuide(app: AppState, cur: Screen, mini: Boolean, onPick: () -> Unit = {}) {
    val root = app.stack.firstOrNull()
    // Settings → Music tab: Music among the tabs at the top when on, nowhere in the guide when off
    val set by app.store.settings.state.collectAsState()
    val music = !set.hideMusicTab
    // the guide's places are top-level pages, as the tabs are (no back arrow on Downloads, History…)
    fun go(s: Screen) { app.goTop(s); onPick() }
    fun on(s: Screen) = root == s && app.stack.size == 1 || cur == s
    val c = MaterialTheme.colorScheme
    if (mini) {
        Column(Modifier.width(72.dp).fillMaxHeight().padding(top = 4.dp, start = 4.dp, end = 4.dp)) {
            @Composable fun item(icon: Symbol, label: String, s: Screen) {
                val sel = on(s)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { go(s) }.padding(top = 16.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (sel) icon.fill else icon.outline, null, Modifier.size(24.dp))
                    Text(label, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item(Sym.Home, tr("Home"), Screen.Home)
            if (!app.noShorts) item(Sym.Bolt, tr("Shorts"), Screen.Shorts)
            item(Sym.Subscriptions, tr("Subscriptions"), Screen.Subs)
            if (music) item(Sym.Music, tr("Music"), Screen.Music)
            item(Sym.VideoLibrary, tr("You"), Screen.Library)
            item(Sym.Download, tr("Downloads"), Screen.Downloads)
        }
        return
    }
    val lib by app.store.lib.state.collectAsState()
    var allSubs by remember { mutableStateOf(false) }
    Column(Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp)) {
        GuideRow(if (on(Screen.Home)) Sym.Home.fill else Sym.Home.outline, tr("Home"), on(Screen.Home)) { go(Screen.Home) }
        if (!app.noShorts) GuideRow(if (on(Screen.Shorts)) Sym.Bolt.fill else Sym.Bolt.outline, tr("Shorts"), on(Screen.Shorts)) { go(Screen.Shorts) }
        if (music) GuideRow(if (on(Screen.Music)) Sym.Music.fill else Sym.Music.outline, tr("Music"), on(Screen.Music)) { go(Screen.Music) }
        GuideDivider()
        GuideHeader(tr("Subscriptions")) { go(Screen.Subs) }
        val subs = remember(lib.subs) { lib.subs.entries.sortedBy { it.value.name.lowercase() } }
        if (subs.isEmpty()) GuideRow(Sym.Subscriptions.outline, tr("All subscriptions"), on(Screen.Subs)) { go(Screen.Subs) }
        for ((id, s) in if (allSubs) subs else subs.take(7)) {
            GuideRow(null, s.name, (cur as? Screen.Channel)?.id == id, avatar = s.thumb) { go(Screen.Channel(id, s.name)) }
        }
        if (subs.size > 7) GuideRow(if (allSubs) Sym.KeyboardArrowUp.outline else Sym.KeyboardArrowDown.outline,
            if (allSubs) tr("Show fewer") else tr("Show more"), false) { allSubs = !allSubs }
        GuideDivider()
        GuideHeader(tr("You")) { go(Screen.Library) }
        GuideRow(Sym.History.outline, tr("History"), on(Screen.History)) { go(Screen.History) }
        GuideRow(Sym.PlaylistPlay.outline, tr("Playlists"), on(Screen.Library)) { go(Screen.Library) }
        GuideRow(Sym.WatchLater.outline, tr("Watch later"), cur == Screen.Local("wl")) { go(Screen.Local("wl")) }
        GuideRow(Sym.ThumbUp.outline, tr("Liked videos"), cur == Screen.Local("likes")) { go(Screen.Local("likes")) }
        GuideRow(Sym.Download.outline, tr("Downloads"), on(Screen.Downloads)) { go(Screen.Downloads) }
        GuideDivider()
        GuideHeader(tr("Explore"), null)
        val icons = mapOf("Live" to Sym.LiveTv, "News" to Sym.News, "Sports" to Sym.Trophy, "Learning" to Sym.School,
            "Gaming" to Sym.Gaming, "Fashion & beauty" to Sym.Apparel)
        for (k in TOPICS.keys) if (k != "Music") GuideRow((icons[k] ?: Sym.Explore).outline, tr(k), cur == Screen.Topic(k)) { go(Screen.Topic(k)) }
        GuideDivider()
        GuideRow(Sym.Settings.outline, tr("Settings"), on(Screen.Settings)) { go(Screen.Settings) }
        GuideRow(Sym.BarChart.outline, tr("Watch time"), on(Screen.Stats)) { go(Screen.Stats) }
        Text(tr("Ad-free. Private. Yours."), fontSize = 12.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 16.dp))
    }
}

@Composable
private fun GuideDivider() = HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

// --------------------------------------------------------------------------------------- masthead

/** the search box in the middle of the masthead, with YouTube's suggestions dropping down under it */
@Composable
fun MastheadSearch(app: AppState, modifier: Modifier = Modifier, autoFocus: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val q0 = (app.current as? Screen.Search)?.query ?: ""
    var text by remember(q0) { mutableStateOf(TextFieldValue(q0, TextRange(q0.length))) }
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var sugg by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var pick by remember { mutableIntStateOf(-1) }
    val prefs by app.store.settings.state.collectAsState()
    val typed = text.text
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    // the QA driver types into the box (parity screenshots of the suggestions)
    Parity.flags["type"]?.let { t -> LaunchedEffect(t) { Parity.flags.remove("type"); runCatching { fr.requestFocus() }; text = TextFieldValue(t, TextRange(t.length)); open = true } }
    // another page: the suggestions close
    LaunchedEffect(app.current) { open = false }
    // opened from the narrow window's search icon: ready to type
    if (autoFocus) LaunchedEffect(Unit) { delay(50); runCatching { fr.requestFocus() } }
    LaunchedEffect(typed, focused) {
        if (!focused) { delay(180); open = false; return@LaunchedEffect }
        pick = -1
        val t = typed.trim()
        val recent = prefs.recentSearches.filter { t.isEmpty() || (it.startsWith(t, true) && !it.equals(t, true)) }.take(if (t.isEmpty()) 10 else 3)
        if (t.isEmpty()) { sugg = recent.map { it to true }; open = sugg.isNotEmpty() && q0.isEmpty(); return@LaunchedEffect }
        delay(150)
        val s = runCatching { YouTube.suggestions(t) }.getOrDefault(emptyList()).filter { s -> recent.none { it.equals(s, true) } }
        sugg = (recent.map { it to true } + s.map { it to false }).take(14)
        open = sugg.isNotEmpty() && typed != q0
    }
    fun submit(q: String) {
        open = false
        if (q.isBlank()) return
        text = TextFieldValue(q, TextRange(q.length))
        searchFor(app, q)
    }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .border(1.dp, if (focused) Color(0xFF1C62B9) else c.outlineVariant, shape).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (focused) Icon(Sym.Search.outline, null, Modifier.padding(end = 10.dp).size(18.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (typed.isEmpty()) Text(tr("Search"), color = c.onSurfaceVariant, fontSize = 16.sp, maxLines = 1)
                    BasicTextField(
                        text, { text = it; open = true },
                        Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { focused = it.isFocused; WebTyping.active = it.isFocused }.onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown || !open || sugg.isEmpty()) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> { pick = (pick + 1) % sugg.size; text = TextFieldValue(sugg[pick].first, TextRange(sugg[pick].first.length)); true }
                                Key.DirectionUp -> { pick = if (pick <= 0) sugg.size - 1 else pick - 1; text = TextFieldValue(sugg[pick].first, TextRange(sugg[pick].first.length)); true }
                                Key.Escape -> { open = false; true }
                                else -> false
                            }
                        },
                        singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = c.onSurface), cursorBrush = SolidColor(c.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(text.text) }, onDone = { submit(text.text) }),
                    )
                }
                if (typed.isNotEmpty()) IconButton(onClick = { text = TextFieldValue("") }, Modifier.size(32.dp)) { Icon(Sym.Close.outline, tr("Clear"), Modifier.size(20.dp)) }
            }
            val right = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
            Box(Modifier.width(64.dp).fillMaxHeight().clip(right).background(c.onSurface.copy(alpha = 0.05f))
                .border(1.dp, c.outlineVariant, right).clickable { submit(text.text) }, contentAlignment = Alignment.Center) {
                Icon(Sym.Search.outline, tr("Search"), Modifier.size(22.dp))
            }
            // "Search with your voice" where the browser or system can listen (youtube.com shows it in Chrome)
            MicButton(app, Modifier.padding(start = 12.dp)) { t -> submit(t) }
        }
        if (open && sugg.isNotEmpty()) Popup(offset = IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.roundToPx() }),
            properties = PopupProperties(focusable = false)) {
            Surface(Modifier.widthIn(min = 420.dp, max = 580.dp), shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp, color = c.surface) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    sugg.forEachIndexed { i, (s, recent) ->
                        Row(
                            Modifier.fillMaxWidth().background(if (i == pick) c.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable { submit(s) }.padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (recent) Sym.History.outline else Sym.Search.outline, null, Modifier.size(20.dp))
                            val t = typed.trim()
                            Text(buildAnnotatedString {
                                // the part you typed plain, the rest bold, as YouTube's
                                if (t.isNotEmpty() && s.startsWith(t, true)) {
                                    append(s.substring(0, t.length)); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(t.length)) }
                                } else append(s)
                            }, Modifier.weight(1f).padding(start = 14.dp), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (recent) Text(tr("Remove"), fontSize = 13.sp, color = c.primary, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .clickable { app.store.settings.update { st -> st.copy(recentSearches = st.recentSearches - s) }; sugg = sugg - (s to true) }
                                .padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- chips

/** one chip of a chip bar: a rounded box, the chosen one dark with light text (YouTube's look) */
@Composable
fun WebChip(label: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) c.onSurface else c.onSurface.copy(alpha = 0.06f), tween(Motion.SHORT))
    val fg by animateColorAsState(if (selected) c.surface else c.onSurface, tween(Motion.SHORT))
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.padding(end = 6.dp).size(18.dp), tint = fg)
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** chips in a row with ‹ › arrows at the ends when there are more than fit */
@Composable
fun ChipBar(modifier: Modifier = Modifier, end: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val scroll: ScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val bg = MaterialTheme.colorScheme.background
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
            if (scroll.value > 0) Box(Modifier.align(Alignment.CenterStart).background(Brush.horizontalGradient(0.6f to bg, 1f to Color.Transparent)).padding(end = 24.dp)) {
                IconButton(onClick = { scope.launch { scroll.animateScrollBy(-400f) } }, Modifier.size(40.dp)) {
                    Icon(Sym.ChevronRight.outline, tr("Previous"), Modifier.graphicsLayer { rotationZ = 180f })
                }
            }
            if (scroll.value < scroll.maxValue) Box(Modifier.align(Alignment.CenterEnd).background(Brush.horizontalGradient(0f to Color.Transparent, 0.4f to bg)).padding(start = 24.dp)) {
                IconButton(onClick = { scope.launch { scroll.animateScrollBy(400f) } }, Modifier.size(40.dp)) { Icon(Sym.ChevronRight.outline, tr("Next")) }
            }
        }
        end()
    }
}

// ------------------------------------------------------------------------------------------- cards

/** columns for a grid of cards this wide: 3 to 6 on a computer, as YouTube reflows its own */
fun webCols(width: Dp, min: Int = 1): Int = (((width + 16.dp) / (300.dp + 16.dp)).toInt()).coerceIn(min, 6)

/** seconds since an upload, read from "3 days ago" (or the exact time when known); null when unknown */
fun Video.ageSec(): Long? {
    if (publishedAt > 0) return Sys.nowMs() / 1000 - publishedAt
    val m = Regex("(\\d+)\\s*(second|minute|hour|day|week|month|year)").find(publishedText.lowercase()) ?: return null
    val n = m.groupValues[1].toLong()
    return n * when (m.groupValues[2]) {
        "second" -> 1L; "minute" -> 60L; "hour" -> 3600L; "day" -> 86400L; "week" -> 7 * 86400L; "month" -> 30 * 86400L; else -> 365 * 86400L
    }
}

/**
 * A video in a website grid: the picture, the channel's round picture beside a two-line title, the
 * channel, then views and age, and the ⋮ menu. Hovered, the card gets a soft grey box and, after a
 * second, the picture turns into a preview (the video's own frames from its start, middle and end
 * with a progress line), as YouTube's inline preview but without loading the video.
 */
@Composable
fun WebCard(v: Video, app: AppState, queue: List<Video> = emptyList()) {
    if (!app.kidsAllow(v.channelId)) return
    val c = MaterialTheme.colorScheme
    val calm = app.store.settings.value.calm
    val menu = remember { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val forced = Parity.flags["hover"] == v.id || (Parity.flags["hover"] == "first" && queue.firstOrNull()?.id == v.id)
    var preview by remember { mutableStateOf(false) }
    LaunchedEffect(hovered || forced) { preview = false; if (hovered || forced) { delay(900); preview = true } }
    Column(
        Modifier.fillMaxWidth().hoverable(hover).clip(RoundedCornerShape(14.dp))
            .background(if (hovered || forced) c.onSurface.copy(alpha = 0.05f) else Color.Transparent)
            .warmOnPress(app, v).clickable { app.watch(v, queue) }.padding(bottom = 18.dp),
    ) {
        if (!app.store.settings.value.hideThumbs) Box {
            Thumb(v, app, Modifier.fillMaxWidth(), rounded = 12)
            if (preview && !v.live && !v.short && app.store.settings.value.hoverPreview) HoverPreview(v)
        }
        Row(Modifier.padding(top = 12.dp, start = 2.dp)) {
            if (v.channelId.isNotEmpty()) {
                Avatar(channelAvatar(app, v.channelId, v.avatar), 36, Modifier.clickable { app.go(Screen.Channel(v.channelId, v.author)) })
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(titleOf(v, app), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (v.author.isNotEmpty()) Text(v.author, fontSize = 14.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).clickable { if (v.channelId.isNotEmpty()) app.go(Screen.Channel(v.channelId, v.author)) })
                val meta = v.subline(calm, withAuthor = false)
                if (meta.isNotEmpty()) Text(meta, fontSize = 14.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            VideoMenu(v, app, openState = menu)
        }
    }
}

/** the card's preview: its three auto frames in turn, slowly zooming, a red line filling under them */
@Composable
private fun HoverPreview(v: Video) {
    var i by remember(v.id) { mutableIntStateOf(1) }
    var t by remember(v.id) { mutableStateOf(0f) }
    LaunchedEffect(v.id) {
        val start = Sys.nowMs()
        while (true) {
            delay(50)
            val el = (Sys.nowMs() - start) / 1000f
            t = (el / 6f) % 1f
            i = 1 + ((el / 2f).toInt() % 3)
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        Crossfade(i, animationSpec = tween(400)) { n ->
            Img("https://i.ytimg.com/vi/${v.id}/hq$n.jpg", Modifier.fillMaxSize().graphicsLayer {
                val s = 1f + 0.06f * ((t * 3f) % 1f); scaleX = s; scaleY = s
            })
        }
        if (v.lengthSec > 0) Text(duration(v.lengthSec), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(start = 8.dp, end = 8.dp, bottom = 10.dp).background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(t).background(Red))
        }
    }
}

/** a Short in a shelf: tall picture, title and views under it */
@Composable
fun WebShortCard(v: Video, app: AppState, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable { app.watch(v) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant)) {
            Img(v.thumb, Modifier.fillMaxSize())
        }
        Row(Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(titleOf(v, app), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val views = v.viewsText.ifEmpty { if (v.views > 0) tr("{n} views").replace("{n}", compact(v.views)) else "" }
                if (views.isNotEmpty()) Text(views, fontSize = 14.sp, color = c.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** the Shorts shelf between rows of the grid: as many as fit across, ⋮ to hide it or see them all */
@Composable
fun WebShortsShelf(app: AppState, width: Dp, given: List<Video>? = null) {
    val s = app.store.settings.value
    if (s.hideShorts || s.calm || s.kids || app.noShorts) return
    val list = remember { mutableStateListOf<Video>() }
    var hidden by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    LaunchedEffect(given) {
        if (given != null) { list.clear(); list.addAll(given) }
        else if (list.isEmpty()) runCatching { app.rec.shorts(14) }.getOrNull()?.let { list.addAll(it) }
    }
    if (list.isEmpty() || hidden) return
    val n = ((width + 16.dp) / (190.dp + 16.dp)).toInt().coerceIn(2, 8)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Sym.Bolt.fill, null, Modifier.size(26.dp), tint = Red)
            Text(tr("Shorts"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Sym.MoreVert.fill, tr("More")) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("See all")) }, onClick = { menu = false; app.go(Screen.Shorts) })
                    DropdownMenuItem(text = { Text(tr("Not interested")) }, onClick = { menu = false; hidden = true })
                }
            }
        }
        val rows = if (more) list.chunked(n).take(2) else listOf(list.take(n))
        for (r in rows) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (v in r) WebShortCard(v, app, Modifier.weight(1f))
            repeat(n - r.size) { Spacer(Modifier.weight(1f)) }
        }
        if (list.size > n) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(Modifier.width(320.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .clickable { more = !more }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(if (more) tr("Show less") else tr("Show more"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Icon(if (more) Sym.KeyboardArrowUp.outline else Sym.KeyboardArrowDown.outline, null, Modifier.padding(start = 4.dp))
            }
        }
    }
}

/**
 * Cards in rows of [webCols] with full-width pieces between them (a Shorts shelf after the first row),
 * loading more near the end.
 */
@Composable
fun WebGrid(
    videos: List<Video>, app: AppState, loading: Boolean, onEnd: (suspend () -> Unit)?,
    top: @Composable () -> Unit = {}, shortsAfterRow: Int = 1, shorts: Boolean = true, empty: String? = null,
    refresh: (suspend () -> Unit)? = null,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    if (onEnd != null) LaunchedEffect(list, videos.size) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= list.layoutInfo.totalItemsCount - 3) scope.launch { onEnd() }
        }
    }
    Refreshable(app, refresh, atTop = { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 }, toTop = {
        if (list.firstVisibleItemIndex > 6) list.scrollToItem(6)
        list.animateScrollToItem(0)
    }) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val edge = 24.dp
        val inner = maxWidth - edge * 2
        val cols = webCols(inner)
        val long = videos.filter { !it.short }
        val shortOnes = videos.filter { it.short }
        val rows = long.chunked(cols)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = edge, end = edge, bottom = 32.dp)) {
            item(key = "top") { top() }
            if (rows.isEmpty() && !loading && empty != null) item(key = "empty") { Empty(empty) }
            if (rows.isEmpty() && loading) item(key = "bones") { WebBones(cols) }
            for ((r, row) in rows.withIndex()) {
                item(key = "row:" + row.first().id) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (v in row) Box(Modifier.weight(1f)) { WebCard(v, app, long) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (shorts && r == shortsAfterRow - 1) item(key = "shorts") { WebShortsShelf(app, inner, shortOnes.takeIf { it.size >= 8 }) }
            }
            if (loading && rows.isNotEmpty()) item(key = "busy") { Busy() }
        }
    }
    }
}

/** the round "Refresh" button at the end of a chip bar or a page's title row (a computer's way to reload) */
@Composable
fun WebRefreshButton(onClick: () -> Unit) {
    Box(Modifier.padding(start = 8.dp)) {
        IconButton(onClick = onClick, Modifier.size(40.dp)) { Icon(Sym.Replay.outline, tr("Refresh")) }
    }
}

/** grey cards while the first page loads */
@Composable
fun WebBones(cols: Int) {
    Column {
        repeat(2) {
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(cols) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).bone(RoundedCornerShape(12.dp)))
                        Row(Modifier.padding(top = 12.dp)) {
                            Box(Modifier.size(36.dp).bone(CircleShape))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Box(Modifier.fillMaxWidth(0.9f).height(16.dp).bone())
                                Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.6f).height(14.dp).bone())
                            }
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------------------- home

/** what Home's chip bar has chosen; kept while the app runs */
object WebHome {
    var chip by mutableStateOf("all")
}

/**
 * Home as on the website: YouTube's own chip bar for the session when its Home has one (All, New to you,
 * topics…, each reloading Home with it), else All, topics, Recently uploaded and Watched; then the grid of
 * YouTube's Home. A topic loads its own list in place.
 */
@Composable
fun WebHomeFeed(items: List<Item>, app: AppState, loading: Boolean, onEnd: suspend () -> Unit, header: @Composable () -> Unit, refresh: (suspend () -> Unit)? = null) {
    // the driver (parity screenshots) can pick a chip by its label
    val asked = Parity.chip
    LaunchedEffect(asked) {
        // signed in, the account's own chip of that label (as a click on it), else the local chips
        val own = app.rec.chips.withIndex().firstOrNull { it.value.title.equals(asked, true) }
        if (asked != null && own != null) {
            app.rec.pickChip(if (own.index == 0) null else own.value); WebHome.chip = "all"; HomeCtl.changed(); Parity.chip = null
        } else if (asked != null) {
            WebHome.chip = when (asked.lowercase()) {
                "all" -> "all"; "recently uploaded" -> "recent"; "watched" -> "watched"
                else -> if (TOPICS.containsKey(asked)) "topic:$asked" else "kw:$asked"
            }
            Parity.chip = null
        }
    }
    var chip by remember { mutableStateOf(WebHome.chip) }
    LaunchedEffect(WebHome.chip) { chip = WebHome.chip }
    fun choose(k: String) {
        WebHome.chip = k
        if (k == "all" && chip == "all") HomeCtl.fresh()
    }
    // signed in: the account's own chips (YouTube's Home chips), each reloading Home with it
    val acct = app.rec.chips
    // YouTube's Home has no button for it; a computer has no pull-down either, so one sits at the chips' end
    val again: @Composable () -> Unit = { if (refresh != null) WebRefreshButton { HomeCtl.fresh() } }
    val bar: @Composable () -> Unit = if (acct.isNotEmpty()) ({
        ChipBar(end = again) {
            val on = app.rec.accountChip?.title ?: acct.firstOrNull { it.selected }?.title ?: acct.first().title
            acct.forEachIndexed { i, c ->
                WebChip(c.title, c.title == on) { app.rec.pickChip(if (i == 0) null else c); WebHome.chip = "all"; HomeCtl.changed() }
            }
        }
    }) else ({
        ChipBar(end = again) {
            WebChip(tr("All"), chip == "all") { choose("all") }
            for (k in listOf("Music", "Gaming", "News", "Live", "Sports", "Learning")) WebChip(tr(k), chip == "topic:$k") { choose("topic:$k") }
            WebChip(tr("Recently uploaded"), chip == "recent") { choose("recent") }
            WebChip(tr("Watched"), chip == "watched") { choose("watched") }
        }
    })
    val videos = items.filterIsInstance<Video>().filter { app.kidsAllow(it.channelId) }.distinctBy { it.id }
    when {
        chip == "all" -> WebGrid(videos, app, loading, onEnd, top = { bar(); header() }, refresh = refresh)
        chip == "recent" -> {
            val recent = videos.filter { (it.ageSec() ?: Long.MAX_VALUE) <= 14 * 86400L }.sortedBy { it.ageSec() ?: Long.MAX_VALUE }
            WebGrid(recent, app, loading, onEnd, top = bar, shorts = false, empty = tr("Nothing uploaded in the last two weeks here yet"), refresh = refresh)
        }
        chip == "watched" -> {
            val lib by app.store.lib.state.collectAsState()
            val seen = remember(lib.hist) { lib.hist.map { it.video() }.filter { !it.short }.distinctBy { it.id }.take(120) }
            WebGrid(seen, app, false, null, top = bar, shorts = false, empty = tr("Videos you watch show up here"))
        }
        chip.startsWith("topic:") -> {
            val key = chip.removePrefix("topic:")
            val id = TOPICS[key] ?: ""
            val pager = rememberPager("webtopic:$key", { YouTube.channel(id, null as String?).items }, { YouTube.browseMore(it) })
            WebGrid(pager.items.filterIsInstance<Video>().filter { !it.members }, app, pager.loading || !pager.started, { pager.loadMore() }, top = bar,
                empty = if (pager.error != null) pager.error else tr("YouTube shows nothing here for your region"))
        }
        else -> {
            val q = chip.removePrefix("kw:")
            val pager = rememberPager("webkw:$q", { YouTube.search(q, SearchOptions(type = 1)) }, { YouTube.searchMore(it) })
            WebGrid(pager.items.filterIsInstance<Video>(), app, pager.loading || !pager.started, { pager.loadMore() }, top = bar,
                empty = pager.error ?: tr("No results"))
        }
    }
}

// ------------------------------------------------------------------------------------ subscriptions

/**
 * Subscriptions as on the website: "Latest" with an "All subscriptions" button, a "Most relevant"
 * shelf first (the newest unwatched video of the channels you like most), then every new video,
 * newest first, with a Shorts shelf. Groups, a switched-off feed and no subscriptions keep the
 * classic page.
 */
@Composable
fun WebSubsScreen(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    val set by app.store.settings.state.collectAsState()
    val signedIn = app.feedSource.signedIn
    if ((lib.subs.isEmpty() && !signedIn) || set.subsFeedOff || lib.groups.isNotEmpty() || set.kids) { SubsScreen(app); return }
    // signed in: the account's own Subscriptions page (its "Most relevant" row and newest uploads), page by page
    val acctVideos = remember(app.account) { mutableStateListOf<Video>() }
    var acctRelevant by remember(app.account) { mutableStateOf<List<Video>>(emptyList()) }
    var acctNext by remember(app.account) { mutableStateOf<String?>(null) }
    var acctOn by remember(app.account) { mutableStateOf(false) }
    /** the account's feed didn't come at all (no answer from YouTube), as opposed to a sign-in it won't take */
    var acctFailed by remember(app.account) { mutableStateOf(false) }
    suspend fun loadAcct() {
        if (!signedIn) return
        val r = runCatching { YouTubeAccount.subscriptions() }
        acctFailed = r.isFailure && r.exceptionOrNull() !is kotlinx.coroutines.CancellationException
        val f = r.getOrNull()?.takeIf { !it.loggedOut } ?: return
        acctVideos.clear(); acctVideos.addAll(f.videos)
        acctRelevant = f.shelves.firstOrNull { it.title.contains("relevant", true) }?.items?.filterIsInstance<Video>().orEmpty()
        acctNext = f.next; acctOn = true
    }
    LaunchedEffect(app.account) { loadAcct() }
    val taste by app.store.taste.state.collectAsState()
    var items by remember { mutableStateOf(app.feed.cached().filter { it.channelId in lib.subs }) }
    var loading by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf(false) }
    suspend fun loadLocal(force: Boolean) {
        loading = true
        try { items = app.feed.all(force); unreachable = app.feed.unreachable } catch (_: Exception) {} finally { loading = false }
    }
    LaunchedEffect(lib.subs.size) { loadLocal(false) }
    val scope = rememberCoroutineScope()
    val feed = items.filter { it.channelId in lib.subs && (!it.short || !app.noShorts) }.distinctBy { it.id }
    // YouTube out of reach: say so (Retry, Go to downloads), not "No new videos from your subscriptions yet"
    if (!loading && !acctOn && feed.isEmpty() && (if (signedIn) acctFailed && (lib.subs.isEmpty() || unreachable) else unreachable)) {
        YouAreOffline(app) { scope.launch { loadAcct(); loadLocal(true) } }
        return
    }
    val watched = remember(lib.hist) { lib.hist.mapTo(HashSet()) { it.id } }
    // the channels you watch and like most, each with its newest video of the last week you haven't seen
    val localRelevant = remember(feed, taste.ch, watched) {
        val weekAgo = 8 * 86400L
        feed.asSequence().filter { !it.short && it.id !in watched && (it.ageSec() ?: Long.MAX_VALUE) <= weekAgo }
            .groupBy { it.channelId }.map { (cid, vs) -> (taste.ch[cid] ?: 0.0) to vs.first() }
            .sortedByDescending { it.first }.map { it.second }.take(12).toList()
    }
    var more by remember { mutableStateOf(false) }
    val relevant = if (acctOn && acctRelevant.isNotEmpty()) acctRelevant else localRelevant
    // the grid leaves out what the shelf above already shows (its first row was the same videos twice)
    var shelfCols by remember { mutableStateOf(3) }
    val shown = if (relevant.size >= 2) relevant.take(if (more) shelfCols * 3 else shelfCols).mapTo(HashSet()) { it.id } else emptySet()
    val rest = (if (acctOn) acctVideos.toList() else feed).filter { v -> v.id !in shown }
    var paging by remember { mutableStateOf(false) }
    WebGrid(rest, app, loading || paging, if (acctOn && acctNext != null) ({
        if (!paging) {
            paging = true
            try {
                runCatching { YouTubeAccount.more(acctNext!!) }.getOrNull()?.let { f ->
                    val have = acctVideos.mapTo(HashSet()) { it.id }
                    acctVideos.addAll(f.videos.filter { it.id !in have }); acctNext = f.next
                }
            } finally { paging = false }
        }
    }) else null, shortsAfterRow = 2, refresh = { if (signedIn) loadAcct() else loadLocal(true) }, top = {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = webCols(maxWidth)
            if (shelfCols != cols) androidx.compose.runtime.SideEffect { shelfCols = cols }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Latest"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(tr("All subscriptions"), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).clickable { app.go(Screen.ManageSubs) }.padding(horizontal = 16.dp, vertical = 9.dp))
                    WebRefreshButton { app.refreshPage() }
                }
                if (relevant.size >= 2) {
                    Text(tr("Most relevant"), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                    for (row in relevant.take(if (more) cols * 3 else cols).chunked(cols)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (v in row) Box(Modifier.weight(1f)) { WebCard(v, app, relevant) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    if (relevant.size > cols) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(Modifier.width(320.dp).height(36.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                            .clickable { more = !more }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (more) tr("Show less") else tr("Show more"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Icon(if (more) Sym.KeyboardArrowUp.outline else Sym.KeyboardArrowDown.outline, null, Modifier.padding(start = 4.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }, empty = tr("No new videos from your subscriptions yet"))
}

/** a playlist in a website grid: the picture over two stacked edges, "N videos", the title, "View full playlist" */
@Composable
fun WebPlaylistCard(p: app.tubeless.engine.Playlist, app: AppState) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { app.go(Screen.Playlist(p.id, p.title)) }.padding(bottom = 18.dp)) {
        Box(Modifier.fillMaxWidth()) {
            // the stack behind: two thinner edges above the picture
            Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 14.dp).fillMaxWidth().height(10.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(c.onSurface.copy(alpha = 0.18f)))
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant)) {
                Img(p.thumb, Modifier.fillMaxSize())
                if (p.countText.isNotEmpty()) Row(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Sym.PlaylistPlay.outline, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(p.countText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 2.dp))
                }
            }
        }
        Text(p.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        if (p.author.isNotEmpty()) Text(p.author + " · " + tr("Playlist"), fontSize = 14.sp, color = c.onSurfaceVariant, maxLines = 1)
        Text(tr("View full playlist"), fontSize = 14.sp, color = c.onSurfaceVariant, fontWeight = FontWeight.Medium)
    }
}

/** a channel's tabs as on the website: words in a row, the open one underlined, 🔍 opening a box beside them */
@Composable
fun WebChannelTabs(
    tabs: List<app.tubeless.engine.ChannelTab>, open: Int, pick: (app.tubeless.engine.ChannelTab) -> Unit,
    searchable: Boolean, searching: Boolean, draft: String, onDraft: (String) -> Unit, onSearch: () -> Unit, onClose: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                tabs.forEachIndexed { i, t ->
                    Column(Modifier.clickable { pick(t) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (i == open) c.onSurface else c.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp))
                        Box(Modifier.height(2.dp).width(if (i == open) 40.dp else 0.dp).background(c.onSurface))
                    }
                }
            }
            if (searchable) Row(Modifier.padding(start = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (searching) onSearch() else onSearch() }, Modifier.size(40.dp)) { Icon(Sym.Search.outline, tr("Search this channel")) }
                if (searching) {
                    Column(Modifier.width(220.dp)) {
                        BasicTextField(draft, onDraft, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = c.onSurface), cursorBrush = SolidColor(c.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), decorationBox = { inner ->
                                Box { if (draft.isEmpty()) Text(tr("Search"), fontSize = 15.sp, color = c.onSurfaceVariant); inner() }
                            })
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.onSurface))
                    }
                    IconButton(onClick = onClose, Modifier.size(36.dp)) { Icon(Sym.Close.outline, tr("Close"), Modifier.size(20.dp)) }
                }
            }
        }
        HorizontalDivider(color = c.outlineVariant)
    }
}
