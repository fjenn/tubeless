package app.tubehaus.ui

import androidx.compose.ui.draw.clip
import androidx.compose.material3.ModalBottomSheet
import app.tubehaus.engine.Details
import app.tubehaus.engine.Sys
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubehaus.engine.Stream
import app.tubehaus.engine.Video
import app.tubehaus.engine.YouTube
import app.tubehaus.engine.YtError
import app.tubehaus.engine.Ads
import app.tubehaus.engine.ReelSequence
import app.tubehaus.engine.WatchTime
import app.tubehaus.rec.Learn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shorts, one after another, in YouTube's own order for this session (ReelSequence: the account's when
 * signed in, the remembered visitor's when not), with the web's view and watch-time pings, so YouTube's
 * Shorts recommendations learn from what is watched here.
 *
 * The streams of the next two (and the last one) are fetched ahead, so a swipe plays at once.
 * Touch: swipe, tap to pause, double-tap to like. Mouse and keyboard: the wheel, the arrow
 * buttons or the up/down keys move between them.
 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(app: AppState) {
    // Shorts turned off while this page was open (or it was reached some other way): back to where the user was
    if (app.noShorts) {
        LaunchedEffect(Unit) { if (!app.back()) app.go(Screen.Home) }
        return
    }
    val list = remember { mutableStateListOf<Video>() }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val player = app.player
    // YouTube's own Shorts sequence for this session (signed in or the remembered visitor), as youtube.com/shorts
    var seq by remember { mutableStateOf(ReelSequence()) }
    /** a fresh run (the Shorts tab tapped again): a new pager from the first Short */
    var run by remember { mutableStateOf(0) }
    val reelParams = remember { HashMap<String, String?>() }
    suspend fun more() {
        if (loading) return
        loading = true
        try {
            val seed = list.firstOrNull()?.id
            val reels = if (list.isEmpty() || !seq.started) seq.start(seed) else seq.more()
            reels.forEach { reelParams[it.video.id] = it.playerParams }
            // never an ad (ReelSequence leaves them out; an id once seen as an ad stays out too)
            val got = reels.map { it.video }.filter { it.id !in app.store.taste.value.hiddenVideos && !Ads.isAd(it.id) }
            list.addAll(got.filter { g -> list.none { it.id == g.id } })
            error = if (list.isEmpty()) tr("No Shorts found") else null
        } catch (e: Exception) {
            if (list.isEmpty()) error = e.message
        } finally { loading = false }
    }
    LaunchedEffect(Unit) {
        app.shortFirst?.let { list.add(it); app.shortFirst = null }
        more()
    }
    val pager = androidx.compose.runtime.key(run) { rememberPagerState { list.size } }
    val streams = remember { mutableStateMapOf<String, Stream>() }
    val fetching = remember { HashMap<String, Deferred<Result<Stream>>>() }
    var current by remember { mutableStateOf<Video?>(null) }
    var watchStart by remember { mutableStateOf(0L) }

    /** one fetch per Short, shared by whoever asks first */
    suspend fun streamOf(id: String): Stream {
        streams[id]?.let { return it }
        // a failed fetch comes back as a Result: it must not cancel the screen's other work
        val d = fetching.getOrPut(id) { scope.async { runCatching { YouTube.stream(id) } } }
        return try { d.await().getOrThrow().also { streams[id] = it } } finally { fetching.remove(id) }
    }

    fun ahead(page: Int) {
        for (p in listOf(page + 1, page + 2, page - 1)) {
            val n = list.getOrNull(p) ?: continue
            if (n.id !in streams && n.id !in fetching) scope.launch { runCatching { streamOf(n.id) } }
        }
    }

    fun learnFrom(v: Video) {
        WatchTime.end(v.id)
        val s = streams[v.id] ?: return
        val st = player.state.value
        if (st.videoId != v.id) return
        // time actually spent on it, which counts the loops
        val spent = (Sys.nowMs() - watchStart) / 1000.0
        Learn.learn(
            app.store, v.copy(channelId = s.channelId, author = s.author), s.keywords, spent, s.lengthSec.toDouble().coerceAtLeast(1.0),
            liked = app.store.inList("likes", v.id), disliked = app.store.inList("dislikes", v.id), short = true,
        )
    }

    // the Shorts tab tapped again (as YouTube's app): a fresh sequence from YouTube, a spinner, the first of it
    val tick = app.reselect
    val tick0 = remember { tick }
    LaunchedEffect(tick) {
        if (tick == tick0) return@LaunchedEffect
        while (loading) delay(50)
        current?.let { learnFrom(it) }
        player.stop()
        current = null; error = null
        list.clear(); reelParams.clear()
        seq = ReelSequence()
        run++
        more()
    }

    // where a run of key presses / swipes is heading: a second press during the slide goes one further
    var target by remember { mutableStateOf(-1) }
    fun go(by: Int) {
        val from = if (target >= 0 && pager.isScrollInProgress) target else pager.currentPage
        val to = (from + by).coerceIn(0, maxOf(0, list.size - 1))
        if (to == from) return
        target = to
        scope.launch { pager.animateScrollToPage(to, animationSpec = tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    }

    LaunchedEffect(pager) {
        // the page and the Short on it: the first Short arrives after the pager exists
        snapshotFlow { pager.currentPage to list.getOrNull(pager.currentPage) }.collect { (page, got) ->
            val v = got ?: return@collect
            if (current?.id == v.id) return@collect
            current?.let { learnFrom(it) }
            current = v
            Parity.played += v.id
            E2E.log("short", "videoId" to v.id, "page" to page, "ad" to Ads.isAd(v.id))
            if (page >= list.size - 4) scope.launch { more() }
            ahead(page)
            try {
                val s = streamOf(v.id)
                if (current?.id != v.id) return@collect
                watchStart = Sys.nowMs()
                // the view and watch-time pings, as the web's Shorts player sends them (el=shortspage)
                if (!app.store.settings.value.historyOff) WatchTime.start(v.id, s.lengthSec.toDouble(), reelParams[v.id])
                player.load(s, PlayOptions(maxHeight = 720, loop = true, volume = app.store.settings.value.volume, title = s.title, artist = s.author, artwork = v.thumb))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) { if (current?.id == v.id) current = null; throw e }
                app.toast(if ((e as? YtError)?.kind == YtError.Kind.LOGIN) tr(BOT_CHECK) else e.message ?: tr("This video can't be played"))
                // one that can't be played is skipped
                if ((e as? YtError)?.kind != YtError.Kind.LOGIN) {
                    delay(1200)
                    if (current?.id == v.id) go(1)
                }
            }
        }
    }
    // the pings follow the player (position, playing or paused) for the Short on screen
    LaunchedEffect(Unit) {
        player.state.collect { st ->
            val v = current ?: return@collect
            if (st.videoId == v.id) WatchTime.tick(v.id, st.positionMs / 1000.0, st.durationMs / 1000.0, st.playing)
        }
    }
    DisposableEffect(Unit) {
        app.shortsKeys = { key ->
            when (key) {
                Key.DirectionDown, Key.PageDown -> { go(1); true }
                Key.DirectionUp, Key.PageUp -> { go(-1); true }
                else -> playerKey(app, key)
            }
        }
        onDispose {
            app.shortsKeys = null
            current?.let { learnFrom(it) }
            player.stop()
        }
    }

    // the website layout: the page's own background, the Short rounded in the middle, its buttons beside it
    val web = webDesk(app)
    Box(Modifier.fillMaxSize().background(if (web) MaterialTheme.colorScheme.background else Color.Black).wheelPages { go(it) }) {
        when {
            list.isEmpty() && error != null -> YouAreOffline(app) { scope.launch { more() } }
            list.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = if (web) MaterialTheme.colorScheme.onSurface else Color.White)
            // a mouse or trackpad never drags the pager itself: [wheelPages] turns each gesture into one Short
            else -> VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { list[it].id },
                userScrollEnabled = app.platform.touch) { page ->
                val v = list[page]
                ShortPage(app, v, streams[v.id], active = current?.id == v.id)
            }
        }
        // the desktop's way through them, beside the Short
        if (!app.platform.touch && list.isNotEmpty()) {
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundAction(Sym.KeyboardArrowUp.fill, tr("Previous"), web = web) { go(-1) }
                RoundAction(Sym.KeyboardArrowDown.fill, tr("Next"), web = web) { go(1) }
            }
        }
    }
}

/**
 * One gesture is one Short, as on youtube.com: a wheel notch, or a two-finger swipe on a trackpad however
 * long its momentum runs. The deltas of a gesture add up until they pass a notch's worth, then that gesture
 * moves one Short and everything after it (the rest of the swipe, its inertia tail) is ignored until the
 * events stop for a moment or a new swipe clearly starts (the deltas grow again after dying down).
 */
private fun Modifier.wheelPages(step: (Int) -> Unit): Modifier = pointerInput(Unit) {
    val gap = 160L          // ms without scroll events: the gesture (and its inertia) is over
    val notch = 1.0         // a mouse wheel notch; a deliberate swipe passes it within its first few events
    var acc = 0.0
    var fired = false
    var lastAt = 0L
    var low = Double.MAX_VALUE   // the smallest delta since the gesture fired (inertia decays toward 0)
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.type != PointerEventType.Scroll) continue
            val dy = e.changes.sumOf { it.scrollDelta.y.toDouble() }
            e.changes.forEach { it.consume() }
            val now = Sys.nowMs()
            val mag = kotlin.math.abs(dy)
            if (now - lastAt > gap) { acc = 0.0; fired = false; low = Double.MAX_VALUE }
            lastAt = now
            if (dy == 0.0) continue
            if (fired) {
                // a new swipe during the old one's inertia: the deltas jump back up after dying down
                if (mag < low) low = mag
                if (low < 0.25 && mag > 4 * low + 0.5 && mag >= notch * 0.5) { fired = false; acc = 0.0; low = Double.MAX_VALUE } else continue
            }
            // a turn of direction starts over
            if (acc != 0.0 && (acc > 0) != (dy > 0)) acc = 0.0
            acc += dy
            if (kotlin.math.abs(acc) >= notch) {
                fired = true
                low = mag
                step(if (acc > 0) 1 else -1)
                acc = 0.0
            }
        }
    }
}

@Composable
private fun ShortPage(app: AppState, v: Video, s: Stream?, active: Boolean) {
    val player = app.player
    val st by player.state.collectAsState()
    val lib by app.store.lib.state.collectAsState()
    val full = s?.asVideo() ?: v
    val liked = lib.likes.any { it.id == v.id }
    val disliked = v.id in lib.dislikes
    // a double tap's hearts, each where the finger was (a quick run of taps sends one each)
    val hearts = remember { mutableStateListOf<Pair<Int, Offset>>() }
    var heartNo by remember { mutableIntStateOf(0) }
    // held down: twice the speed until let go
    var fast by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val mine = active && st.videoId == v.id
    // the comments come with the watch page's details, asked for when the button is pressed
    var comments by remember { mutableStateOf(false) }
    var info by remember(v.id) { mutableStateOf<Details?>(null) }
    // the like and comment counts and the channel's picture come with the details: asked for as the Short comes up
    LaunchedEffect(comments, active) { if ((comments || active) && info == null) info = runCatching { YouTube.details(v.id) }.getOrNull() ?: Details() }
    var menu by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }

    val web = webDesk(app)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        Box(
            // a phone: the whole screen, as YouTube (9:16 by the height was wider than a phone and cut off
            // the buttons and the title); wider screens: 9:16 in the middle
            (if (LocalCompact.current) Modifier.fillMaxSize() else if (web) Modifier.fillMaxHeight().padding(vertical = 16.dp).aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Color.Black)
                else Modifier.fillMaxHeight().aspectRatio(9f / 16f, matchHeightConstraintsFirst = true))
                .pointerInput(v.id) {
                    // YouTube's double-tap like: it only ever likes, never unlikes; taps right after
                    // it send more hearts
                    var runUntil = 0L
                    fun like(at: Offset, t: Long) {
                        if (!app.store.inList("likes", v.id)) app.store.setInList("likes", full, true)
                        hearts.add(heartNo++ to at)
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                        runUntil = t + 600
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (down.uptimeMillis < runUntil) {
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            up.consume(); like(up.position, up.uptimeMillis)
                            return@awaitEachGesture
                        }
                        var moved = false
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation().also { if (it == null) moved = true }
                        }
                        if (moved) return@awaitEachGesture
                        if (up == null) {
                            val before = player.state.value.speed
                            if (!player.state.value.playing || before >= 2f) return@awaitEachGesture
                            player.setSpeed(2f); fast = true
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            try {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    e.changes.forEach { if (it.pressed) it.consume() }
                                    if (e.changes.none { it.pressed }) break
                                }
                            } finally { player.setSpeed(before); fast = false }
                            return@awaitEachGesture
                        }
                        up.consume()
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                        if (second == null) { if (player.state.value.playing) player.pause() else player.play(); return@awaitEachGesture }
                        like(second.position, second.uptimeMillis)
                        waitForUpOrCancellation()?.consume()
                    }
                },
        ) {
            if (!app.store.settings.value.hideThumbs) Img(v.thumb, Modifier.fillMaxSize())
            if (mine) player.View(Modifier.fillMaxSize())
            // paused: a play sign that pops in (and shrinks away when it plays again)
            androidx.compose.animation.AnimatedVisibility(mine && !st.playing && !st.buffering, Modifier.align(Alignment.Center),
                enter = fadeIn(tween(Motion.SHORT)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 600f), initialScale = 1.4f),
                exit = fadeOut(tween(Motion.SHORT)) + scaleOut(tween(Motion.SHORT), targetScale = 0.8f)) {
                Box(Modifier.size(84.dp).background(Color(0x55000000), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Sym.PlayArrow.fill, null, tint = Color.White, modifier = Modifier.size(56.dp))
                }
            }
            if (active && st.buffering) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 3.dp)
            }
            for ((n, at) in hearts.toList()) androidx.compose.runtime.key(n) { Heart(at, n) { hearts.removeAll { it.first == n } } }
            androidx.compose.animation.AnimatedVisibility(fast, Modifier.align(Alignment.TopCenter).padding(top = 56.dp), enter = fadeIn(tween(Motion.SHORT)), exit = fadeOut(tween(Motion.SHORT))) {
                Text("2x  ▸▸", color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
            // the bottom: channel, title, and a seek bar you can drag
            // a soft shade up from the bottom, as YouTube's, so the white words read on any picture
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                .padding(start = 14.dp, end = 14.dp, top = 40.dp, bottom = 6.dp)) {
                val author = s?.author ?: v.author
                val cid = s?.channelId ?: v.channelId
                // as YouTube's: the channel's picture, @name and a white Subscribe pill on one line, the title under it
                if (author.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 64.dp)) {
                    val pic = info?.avatar?.ifEmpty { null } ?: v.avatar.ifEmpty { null } ?: lib.subs[cid]?.thumb
                    if (pic != null) Avatar(pic, 32, Modifier.clickable { if (cid.isNotEmpty()) app.go(Screen.Channel(cid, author)) })
                    Text(
                        "@" + author.replace(" ", ""), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false).clickable { if (cid.isNotEmpty()) app.go(Screen.Channel(cid, author)) },
                    )
                    if (cid.isNotEmpty()) {
                        val subbed = cid in lib.subs
                        Text(tr(if (subbed) "Subscribed" else "Subscribe"), color = if (subbed) Color.White else Color.Black, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            modifier = Modifier.padding(start = 10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                .background(if (subbed) Color(0x33FFFFFF) else Color.White)
                                .clickable { app.store.subscribe(cid, author, pic ?: "", !subbed) }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Text(s?.title ?: v.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, end = 64.dp))
                if (mine && st.durationMs > 0) {
                    Track(
                        value = (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f), color = Red,
                        onDrag = {}, onDone = { f -> player.seekTo((f * st.durationMs).toLong()) },
                        modifier = Modifier.fillMaxWidth(),
                        label = tr("Seek bar"), state = tr("{a} of {b}").replace("{a}", durationMs(st.positionMs)).replace("{b}", durationMs(st.durationMs)),
                    )
                }
            }
            // the buttons down the right, near the bottom, each with its word under it, as on YouTube
            if (!web) Column(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                // the counts under the buttons, as YouTube's (the word where there is no count)
                val calm = app.store.settings.value.calm
                val likes = info?.likesText?.takeIf { !calm && it.any(Char::isDigit) }
                val talk = info?.commentsCount?.takeIf { !calm && it.isNotEmpty() }
                RoundAction(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, likes ?: tr("Like"), on = liked) { app.store.setInList("likes", full, !liked) }
                RoundAction(if (disliked) Sym.ThumbDown.fill else Sym.ThumbDown.outline, tr("Dislike"), on = disliked) { app.store.setInList("dislikes", full, !disliked) }
                if (!app.store.settings.value.hideComments) RoundAction(Sym.Comment.outline, talk ?: tr("Comments")) { comments = true }
                RoundAction(Sym.Share.outline, tr("Share")) { app.share(v.title, "https://youtube.com/shorts/${v.id}") }
                // the channel's picture in a rounded square at the foot of the rail (YouTube's sound disc)
                val pic = info?.avatar?.ifEmpty { null } ?: v.avatar.ifEmpty { null }
                val cid = s?.channelId ?: v.channelId
                if (pic != null) Img(pic, Modifier.padding(top = 4.dp).size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .border(2.dp, Color.White, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .clickable { if (cid.isNotEmpty()) app.go(Screen.Channel(cid, s?.author ?: v.author)) })
            }
            // the top right, as YouTube's: search and ⋮ (Save, Share)
            if (!web) Row(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
                IconButton(onClick = { app.go(Screen.Search()) }) { Icon(Sym.Search.outline, tr("Search"), tint = Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Sym.MoreVert.fill, tr("More"), tint = Color.White) }
                    androidx.compose.material3.DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Save to playlist")) }, leadingIcon = { Icon(Sym.LibraryAdd.outline, null) },
                            onClick = { menu = false; pick = true })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Share")) }, leadingIcon = { Icon(Sym.Share.outline, null) },
                            onClick = { menu = false; app.share(v.title, "https://youtube.com/shorts/${v.id}") })
                    }
                }
            }
        }
        // the website: the action rail outside the Short, on its right
        if (web) Column(Modifier.padding(start = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            RoundAction(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, tr("Like"), on = liked, web = true) { app.store.setInList("likes", full, !liked) }
            RoundAction(if (disliked) Sym.ThumbDown.fill else Sym.ThumbDown.outline, tr("Dislike"), on = disliked, web = true) { app.store.setInList("dislikes", full, !disliked) }
            if (!app.store.settings.value.hideComments) RoundAction(Sym.Comment.outline, tr("Comments"), web = true) { comments = true }
            RoundAction(Sym.Share.outline, tr("Share"), web = true) { app.share(v.title, "https://youtube.com/shorts/${v.id}") }
        }
    }
    if (pick) PlaylistPicker(full, app) { pick = false }
    if (comments) ModalBottomSheet(onDismissRequest = { comments = false }) {
        val d = info
        val token = d?.commentsToken
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.7f)) {
            when {
                d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                token == null -> Text(tr("No comments"), Modifier.align(Alignment.Center))
                else -> CommentsPanel(app, token, d) { comments = false }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, on: Boolean = false, web: Boolean = false, onClick: () -> Unit) {
    // turned on: the button gives a little bounce, as YouTube's like does
    val pop = remember { Animatable(1f) }
    var was by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (on && !was) { pop.snapTo(0.7f); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
        was = on
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { haptic.performHapticFeedback(if (on) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn); onClick() }, modifier = Modifier.size(48.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.background(if (web) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else Color(0x40FFFFFF), CircleShape)) {
            Icon(icon, label, tint = if (web) MaterialTheme.colorScheme.onSurface else Color.White, modifier = Modifier.size(26.dp))
        }
        Text(label, color = if (web) MaterialTheme.colorScheme.onSurface else Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

/** one heart of a double-tap like: pops up where the finger was, tilted a little, then floats up and fades */
@Composable
private fun Heart(at: Offset, n: Int, done: () -> Unit) {
    val t = remember { Animatable(0f) }
    val tilt = remember { listOf(-18f, 12f, -8f, 20f, -14f, 6f)[n % 6] }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(800, easing = androidx.compose.animation.core.LinearEasing)); done() }
    val d = LocalDensity.current
    val half = with(d) { 48.dp.toPx() }
    Icon(Sym.Favorite.fill, null, tint = Red, modifier = Modifier.size(96.dp).graphicsLayer {
        val p = t.value
        // in: a spring-like overshoot in the first quarter; out: up and away
        val grow = if (p < 0.25f) { val q = p / 0.25f; 0.4f + 0.8f * q - 0.2f * q * q } else 1f + (p - 0.25f) * 0.25f
        scaleX = grow; scaleY = grow
        rotationZ = tilt
        translationX = at.x - half
        translationY = at.y - half - (if (p > 0.35f) (p - 0.35f) * 260f else 0f)
        alpha = if (p < 0.6f) 1f else (1f - (p - 0.6f) / 0.4f).coerceIn(0f, 1f)
    })
}
