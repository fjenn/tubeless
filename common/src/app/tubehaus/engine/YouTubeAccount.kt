package app.tubehaus.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.tubehaus.engine.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

// The signed-in YouTube account over InnerTube (docs/audit/MECHANICS.md §5): personal Home, Up Next,
// Subscriptions, History, Watch later, Liked, playlists, ratings, subscriptions and the bell, feedback
// ("Not interested", "Don't recommend channel"), comments. Every call rides on InnerTube.authHeaders, which
// the sign-in code sets (S3; today the JVM cookies.txt Auth); with none set, [signedIn] is false, reads
// return null and writes return false, so every caller keeps its local (signed-out) behaviour.
// The parsing lives in [AccountParse] (pure functions over a response), checked by :desktop:accountCheck.

/** One chip above Home ("All", "Music", "Recently uploaded"...): selecting it reloads Home with [token]. */
data class FeedChip(val title: String, val token: String?, val selected: Boolean)

/** What YouTube's menu offers for one item of a signed-in list: the tokens its `feedback` endpoint takes. */
data class ItemActions(
    val notInterested: String? = null,
    val dontRecommendChannel: String? = null,
    val removeFromHistory: String? = null,
    /** how much of it you watched, 0..100, from the red bar under its picture (any device) */
    val resumePercent: Int? = null,
    /** its place in a playlist (to move or remove that one entry) */
    val setVideoId: String? = null,
)

/** One page of a signed-in list. [shelves]: titled rows ("Most relevant", Shorts, a history day). */
data class AccountFeed(
    val items: List<Item>,
    val next: String? = null,
    val chips: List<FeedChip> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
    val actions: Map<String, ItemActions> = emptyMap(),
    /** YouTube answered as to a signed-out visitor (expired cookies): treat as not signed in */
    val loggedOut: Boolean = false,
) {
    val videos: List<Video> get() = items.filterIsInstance<Video>()
    /** every video on the page in order, rows included (History is all day rows) */
    val everything: List<Video> get() = (shelves.flatMap { it.items } + items).filterIsInstance<Video>().distinctBy { it.id }
}

/** History's own buttons: clear all, pause (or turn back on). */
data class HistoryControls(val clear: String? = null, val pause: String? = null, val resume: String? = null) {
    val paused: Boolean get() = pause == null && resume != null
}

enum class LikeState { LIKE, DISLIKE, NONE }
/** the bell on a subscribed channel: every upload, the personalized ones, none */
enum class Bell(val index: Int) { ALL(2), PERSONALIZED(1), NONE(3) }

/** The signed-in state of a watch page: the rating, the subscription, the bell, and the Up Next column. */
data class WatchState(
    val videoId: String,
    val like: LikeState = LikeState.NONE,
    val channelId: String = "",
    val subscribed: Boolean? = null,
    val bell: Bell? = null,
    /** the params YouTube put on each bell choice (preferred over ones written here) */
    val bellParams: Map<Bell, String> = emptyMap(),
    val likeParams: String? = null,
    val dislikeParams: String? = null,
    val removeLikeParams: String? = null,
    val upNext: List<Item> = emptyList(),
    val upNextToken: String? = null,
    val actions: Map<String, ItemActions> = emptyMap(),
    val loggedOut: Boolean = false,
)

/** What can be done with one comment when signed in. */
data class CommentActions(
    val commentId: String,
    val like: String? = null,
    val unlike: String? = null,
    val dislike: String? = null,
    val undislike: String? = null,
    val replyParams: String? = null,
    val liked: Boolean = false,
)

/** The comments' box ("Add a comment...") and each comment's actions, from a comments page. */
data class CommentSheet(val createParams: String?, val actions: Map<String, CommentActions>)

/** A playlist the account owns or saved (Library). */
data class AccountPlaylist(val id: String, val title: String, val thumb: String, val countText: String)

/** Parsers over InnerTube answers (signed in or not). No I/O here. */
object AccountParse {
    private val ITEM_KEYS = setOf(
        "videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer",
        "lockupViewModel", "shortsLockupViewModel", "reelItemRenderer", "playlistPanelVideoRenderer",
    )

    fun loggedOut(d: JsonElement?): Boolean =
        d.at("responseContext.mainAppWebResponseContext.loggedOut").bool

    /** A list page (Home, Subscriptions, History, a playlist), first page or a continuation. */
    fun feed(d: JsonElement?): AccountFeed {
        val shelves = shelves(d)
        val inShelves = shelves.flatMapTo(HashSet()) { s -> s.items.map { it.id } }
        val all = parseItems(d)
        // the main list leaves out what only a shelf holds ("Most relevant", Shorts rows); a list made only of shelves keeps them
        val main = all.filter { it.id !in inShelves }.ifEmpty { if (shelves.any { it.title.isEmpty() }) all else emptyList() }
        return AccountFeed(main, continuationOf(d), chips(d), shelves.filter { it.title.isNotEmpty() }, actions(d), loggedOut(d))
    }

    /** Home's chip bar (old chipCloudChipRenderer and new chipViewModel shapes). */
    fun chips(d: JsonElement?): List<FeedChip> {
        if (d == null) return emptyList()
        val old = d.findAll("chipCloudChipRenderer", skip = setOf("frameworkUpdates")).mapNotNull { c ->
            val t = c.at("text").text ?: return@mapNotNull null
            FeedChip(t, c.at("navigationEndpoint")?.findFirst("token", emptySet()).str, c.at("isSelected").bool)
        }
        val new = d.findAll("chipViewModel", skip = setOf("frameworkUpdates")).mapNotNull { c ->
            val t = c.at("text").str ?: c.at("text").text ?: return@mapNotNull null
            FeedChip(t, c.at("tapCommand")?.findFirst("token", emptySet()).str, c.at("selected").bool)
        }
        return (old + new).distinctBy { it.title }
    }

    /**
     * Titled rows: rich sections (Subscriptions' "Most relevant", Shorts) and item sections with a header
     * (History's days). A section without a title is returned with "" (the plain list).
     */
    fun shelves(d: JsonElement?): List<Shelf> {
        if (d == null) return emptyList()
        val out = ArrayList<Shelf>()
        for (s in d.findAll("richSectionRenderer")) {
            val c = s.at("content") ?: continue
            val items = parseItems(c)
            if (items.isEmpty()) continue
            val title = (c.findFirst("title", emptySet())).text?.trim() ?: ""
            out += Shelf(title, items)
        }
        for (s in d.findAll("itemSectionRenderer")) {
            val title = s.at("header.itemSectionHeaderRenderer.title").text?.trim()
                ?: s.at("header.sectionHeaderViewModel.headline.content").str?.trim()
                ?: continue
            val items = parseItems(s.at("contents"))
            if (items.isNotEmpty()) out += Shelf(title, items)
        }
        return out
    }

    /** The menu tokens of every item below [d], by item id. */
    fun actions(d: JsonElement?): Map<String, ItemActions> {
        if (d == null) return emptyMap()
        val out = LinkedHashMap<String, ItemActions>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k in ITEM_KEYS) {
                        val id = parseItem(k, v)?.id
                        if (id != null) itemActions(v)?.let { a -> out[id] = out[id]?.let { merge(it, a) } ?: a }
                    } else if (k != "frameworkUpdates" && k != "responseContext") walk(v)
                }
                is JsonArray -> for (x in e) walk(x)
                else -> {}
            }
        }
        walk(d)
        return out
    }

    private fun merge(a: ItemActions, b: ItemActions) = ItemActions(
        a.notInterested ?: b.notInterested, a.dontRecommendChannel ?: b.dontRecommendChannel,
        a.removeFromHistory ?: b.removeFromHistory, a.resumePercent ?: b.resumePercent, a.setVideoId ?: b.setVideoId,
    )

    /** what one item's menu and overlays hold; null when nothing */
    fun itemActions(v: JsonElement): ItemActions? {
        val tokens = feedbackTokens(v)
        var notInterested: String? = null
        var channel: String? = null
        var history: String? = null
        for ((label, tok) in tokens) {
            val l = label.lowercase()
            when {
                "not_interested" in l || "not interested" in l -> notInterested = notInterested ?: tok
                "recommend channel" in l || "don't recommend" in l || "dont recommend" in l || "remove_circle" in l || l.startsWith("block|") ->
                    channel = channel ?: tok
                "history" in l || l.startsWith("delete|") || l.startsWith("close|") || l.startsWith("x|") || l.startsWith("remove|") ->
                    history = history ?: tok
            }
        }
        val pct = (v.findAll("percentDurationWatched", emptySet()).firstOrNull()
            ?: v.findAll("startPercent", emptySet()).firstOrNull()).int
        val set = v.at("setVideoId").str
        if (notInterested == null && channel == null && history == null && pct == null && set == null) {
            // a menu with tokens but no telling labels: a history page's only token is its "remove"
            if (tokens.size == 1) return ItemActions(removeFromHistory = tokens[0].second)
            return null
        }
        return ItemActions(notInterested, channel, history, pct?.coerceIn(0, 100), set)
    }

    /** Every feedbackEndpoint below [root], with the label (icon|text) of the nearest menu entry or button around it. */
    fun feedbackTokens(root: JsonElement): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun labelOf(o: JsonObject): String? {
            val icon = o.at("icon.iconType").str ?: o.at("leadingImage.sources.0.clientResource.imageName").str ?: o.at("iconName").str
            val text = o["text"].text ?: o["title"].text ?: o.at("title.content").str
            return if (icon == null && text == null) null else "${icon ?: ""}|${text ?: ""}"
        }
        fun walk(e: JsonElement, label: String) {
            when (e) {
                is JsonObject -> {
                    val here = labelOf(e) ?: label
                    e["feedbackEndpoint"]?.at("feedbackToken").str?.let { out += here to it }
                    for ((k, v) in e) if (k != "feedbackEndpoint") walk(v, here)
                }
                is JsonArray -> for (x in e) walk(x, label)
                else -> {}
            }
        }
        walk(root, "")
        return out.distinctBy { it.second }
    }

    /** History's buttons: Clear all (DELETE), Pause (PAUSE*) or Turn on (PLAY*), the watch-history ones (first of each). */
    fun historyControls(d: JsonElement?): HistoryControls {
        val buttons = d?.findAll("browseFeedActionsRenderer")?.flatMap { it.at("contents").arr } ?: return HistoryControls()
        var clear: String? = null; var pause: String? = null; var resume: String? = null
        for (b in buttons) {
            val r = b.at("buttonRenderer") ?: b.at("buttonViewModel") ?: continue
            val icon = (r.at("icon.iconType").str ?: r.at("iconName").str ?: "").uppercase()
            val text = (r.at("text").text ?: r.at("title").str ?: "").lowercase()
            if ("search" in text) continue
            val tok = r.findAll("feedbackToken", emptySet()).firstOrNull().str ?: continue
            when {
                icon.startsWith("DELETE") || "clear" in text -> clear = clear ?: tok
                icon.startsWith("PAUSE") || "pause" in text -> pause = pause ?: tok
                icon.startsWith("PLAY") || "turn on" in text -> resume = resume ?: tok
            }
        }
        return HistoryControls(clear, pause, resume)
    }

    /** The watch page's signed-in state and its Up Next column (`next`). */
    fun watch(id: String, d: JsonElement?): WatchState {
        if (d == null) return WatchState(id)
        val mutations = d.at("frameworkUpdates.entityBatchUpdate.mutations").arr
        val likeStatus = mutations.firstNotNullOfOrNull { it.at("payload.likeStatusEntity.likeStatus").str }
            ?: d.findAll("likeStatusEntity", emptySet()).firstOrNull().at("likeStatus").str
        val like = when (likeStatus) { "LIKE" -> LikeState.LIKE; "DISLIKE" -> LikeState.DISLIKE; else -> LikeState.NONE }
        val sub = d.findAll("subscribeButtonRenderer", emptySet()).firstOrNull()
        val subscribed = mutations.firstNotNullOfOrNull { m -> m.at("payload.subscriptionStateEntity")?.let { it.at("subscribed").bool } }
            ?: sub?.at("subscribed")?.let { it.bool }
        val channelId = sub.at("channelId").str
            ?: d.at("contents.twoColumnWatchNextResults.results.results.contents")?.findFirst("browseId").str ?: ""
        // the bell: its states (stateId = the bell index) and the params of each choice in its menu
        val toggle = sub.at("notificationPreferenceButton.subscriptionNotificationToggleButtonRenderer")
        // (the bell only means something on a channel you're subscribed to)
        val bell = toggle.at("currentStateId").int?.takeIf { subscribed == true }?.let { s -> Bell.entries.firstOrNull { it.index == s } }
        val bellParams = HashMap<Bell, String>()
        for (p in toggle?.findAll("modifyChannelNotificationPreferenceEndpoint", emptySet()) ?: emptyList()) {
            val params = p.at("params").str ?: continue
            val idx = prefIndex(params) ?: continue
            Bell.entries.firstOrNull { it.index == idx }?.let { if (it !in bellParams) bellParams[it] = params }
        }
        // the like buttons' own params (the signed-in ones sit straight in the toggle, the signed-out ones behind a sign-in modal)
        val ends = d.findAll("likeEndpoint", emptySet())
        val related = d.at("contents.twoColumnWatchNextResults.secondaryResults")
        return WatchState(
            videoId = id, like = like, channelId = channelId, subscribed = subscribed, bell = bell, bellParams = bellParams.entries.sortedBy { it.key.ordinal }.associate { it.key to it.value },
            likeParams = ends.firstNotNullOfOrNull { it.at("likeParams").str },
            dislikeParams = ends.firstNotNullOfOrNull { it.at("dislikeParams").str },
            removeLikeParams = ends.firstNotNullOfOrNull { it.at("removeLikeParams").str },
            upNext = parseItems(related).filter { it.id != id },
            upNextToken = continuationOf(related),
            actions = actions(related),
            loggedOut = loggedOut(d),
        )
    }

    /** the bell index written in a modify_channel_preference params (field 2 {1: index}) */
    @OptIn(ExperimentalEncodingApi::class)
    fun prefIndex(params: String): Int? = try {
        val raw = urlDecode(params).trimEnd('=').replace('-', '+').replace('_', '/')
        val b = Base64.decode(raw + "=".repeat((4 - raw.length % 4) % 4))
        val f = Proto.fields(b)
        f.firstOrNull { it.first == 2 }?.second?.let { Proto.fields(it as ByteArray) }?.firstOrNull { it.first == 1 }?.second as? Int
    } catch (e: Exception) { null }

    /** A comments page: the "Add a comment" params and each comment's like / reply actions. */
    fun comments(d: JsonElement?): CommentSheet {
        if (d == null) return CommentSheet(null, emptyMap())
        val create = d.findAll("createCommentParams", emptySet()).firstOrNull().str
        val payloads = HashMap<String, JsonElement>()
        for (m in d.at("frameworkUpdates.entityBatchUpdate.mutations").arr) {
            val p = m.at("payload") ?: continue
            (p.obj?.values?.firstOrNull())?.let { v -> v.at("key").str?.let { payloads[it] = v } }
        }
        val out = LinkedHashMap<String, CommentActions>()
        for (vm in d.findAll("commentViewModel", emptySet())) {
            val c = vm.at("commentViewModel") ?: vm
            val id = c.at("commentId").str ?: continue
            val bar = c.at("toolbarSurfaceKey").str?.let { payloads[it] }
            val state = c.at("toolbarStateKey").str?.let { payloads[it] }
            fun action(cmd: String) = bar.at(cmd)?.findAll("action", emptySet())?.firstOrNull().str
            out[id] = CommentActions(
                id, action("likeCommand"), action("unlikeCommand"), action("dislikeCommand"), action("undislikeCommand"),
                bar.at("replyCommand")?.findAll("createReplyParams", emptySet())?.firstOrNull().str,
                state.at("likeState").str == "TOOLBAR_LIKE_STATE_LIKED",
            )
        }
        // the old shape: commentRenderer with its action buttons
        for (r in d.findAll("commentRenderer", emptySet())) {
            val id = r.at("commentId").str ?: continue
            if (id in out) continue
            val acts = r.at("actionButtons")
            out[id] = CommentActions(
                id,
                like = acts?.findAll("performCommentActionEndpoint", emptySet())?.firstOrNull().at("action").str,
                replyParams = acts?.findAll("createReplyParams", emptySet())?.firstOrNull().str,
                liked = acts?.findFirst("isLiked", emptySet()).bool,
            )
        }
        return CommentSheet(create, out)
    }

    /** the id YouTube gave a new playlist (playlist/create) */
    fun createdPlaylist(d: JsonElement?): String? = d.at("playlistId").str

    /** the account's playlists (FEplaylist_aggregation, or FElibrary's Playlists shelf) */
    fun playlists(d: JsonElement?): List<AccountPlaylist> = allPlaylists(d).filter { it.id != "WL" && it.id != "LL" }

    /** the same with Watch later (WL) and Liked videos (LL), which the page lists first */
    fun allPlaylists(d: JsonElement?): List<AccountPlaylist> = parseItems(d).filterIsInstance<Playlist>()
        .map { AccountPlaylist(it.id.removePrefix("VL"), it.title, it.thumb, it.countText) }

    /** the subscribed channels (FEchannels) */
    fun channels(d: JsonElement?): List<Channel> = parseItems(d).filterIsInstance<Channel>()

    /** the stats URLs of a signed-in WEB player answer: playback (once) and watch time (every 10 s) */
    fun tracking(d: JsonElement?): Pair<String, String>? {
        val t = d.at("playbackTracking") ?: return null
        val p = t.at("videostatsPlaybackUrl.baseUrl").str ?: return null
        val w = t.at("videostatsWatchtimeUrl.baseUrl").str ?: return null
        return https(p) to https(w)
    }

    /** a write went through (no error alert, no "sign in" redirect) */
    fun writeOk(d: JsonElement?): Boolean {
        if (d == null) return false
        if (d.at("error") != null) return false
        if (d.findAll("alertRenderer", emptySet()).any { it.at("type").str == "ERROR" }) return false
        if (d.at("status").str?.contains("FAIL") == true) return false
        return d.at("feedbackResponses").arr.all { it.at("isProcessed").let { p -> p == null || p.bool } }
    }
}

/** Just enough protobuf for the bell's params (varints and length-delimited fields). */
internal object Proto {
    fun varint(out: ArrayList<Byte>, v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { out += x.toByte(); return }
            out += ((x and 0x7F) or 0x80).toByte(); x = x ushr 7
        }
    }
    fun bytes(out: ArrayList<Byte>, field: Int, b: ByteArray) { varint(out, (field shl 3 or 2).toLong()); varint(out, b.size.toLong()); b.forEach { out += it } }
    fun int(out: ArrayList<Byte>, field: Int, v: Int) { varint(out, (field shl 3).toLong()); varint(out, v.toLong()) }

    /** top-level fields: varints as Int, length-delimited as ByteArray */
    fun fields(b: ByteArray): List<Pair<Int, Any>> {
        val out = ArrayList<Pair<Int, Any>>()
        var i = 0
        fun read(): Long { var r = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl s); if (c < 0x80) return r; s += 7 } }
        while (i < b.size) {
            val key = read().toInt()
            when (key and 7) {
                0 -> out += (key ushr 3) to read().toInt()
                2 -> { val n = read().toInt(); out += (key ushr 3) to b.copyOfRange(i, i + n); i += n }
                1 -> i += 8
                5 -> i += 4
                else -> return out
            }
        }
        return out
    }
}

/**
 * Writes are paced so a burst of taps can't look like a bot: one at a time, at least 1.2 s apart (with
 * jitter), at most 20 a minute and 300 an hour. Over the limit a write is refused (returns false).
 */
object WriteLimit {
    private val lock = Mutex()
    private val times = ArrayList<Long>()
    @Volatile private var last = 0L
    const val GAP_MS = 1200L
    const val PER_MINUTE = 20
    const val PER_HOUR = 300

    /** told when a write is refused for going over the limit (S7: the app says so in a toast) */
    @Volatile var onLimited: (() -> Unit)? = null

    /** for tests: forget the past writes */
    fun reset() { times.clear(); last = 0L }

    suspend fun <T> run(block: suspend () -> T): T? = lock.withLock {
        val now = Sys.nowMs()
        times.removeAll { now - it > 3_600_000L }
        if (times.size >= PER_HOUR || times.count { now - it < 60_000L } >= PER_MINUTE) { onLimited?.invoke(); return@withLock null }
        val wait = last + GAP_MS + Random.nextLong(400) - now
        if (wait > 0) delay(wait)
        last = Sys.nowMs()
        times += last
        block()
    }
}

object YouTubeAccount {
    /** signed in = the sign-in code set the auth headers */
    val signedIn: Boolean get() = InnerTube.authHeaders != null

    /** set when an answer came back signed out although headers were sent (cookies expired or rotated away) */
    @Volatile var expired = false
        private set

    /** the last Home's chips, for the chip bar */
    @Volatile var homeChips: List<FeedChip> = emptyList()
        private set

    /** menu tokens seen in the lists loaded so far, by video id (for "Not interested" from any screen) */
    private val seen = Sys.concurrentMap<String, ItemActions>()
    fun actionsFor(videoId: String): ItemActions? = seen[videoId]

    /** "how much of it was watched" from the account's lists, by video id */
    fun resumePercent(videoId: String): Int? = seen[videoId]?.resumePercent

    private fun remember(f: AccountFeed): AccountFeed {
        if (f.loggedOut && signedIn) expired = true else if (!f.loggedOut) expired = false
        for ((k, v) in f.actions) seen[k] = v
        if (seen.size > 5000) seen.keys.take(1000).forEach { seen.remove(it) }
        return f
    }

    private suspend fun read(endpoint: String, body: Map<String, Any?>): JsonElement? {
        if (!signedIn) return null
        return InnerTube.call(endpoint, body)
    }

    /** Home and its pages also signed out: the visitor session's own Home (YouTube's signed-out suggestions) */
    private suspend fun readHome(body: Map<String, Any?>): AccountFeed? {
        if (!signedIn && !Accounts.visitorSession) return null
        return remember(AccountParse.feed(InnerTube.call("browse", body)))
    }

    private suspend fun browse(browseId: String, extra: Map<String, Any?> = emptyMap()): AccountFeed? =
        read("browse", mapOf("browseId" to browseId) + extra)?.let { remember(AccountParse.feed(it)) }

    /** a further page of any list here (Home, Subscriptions, History, WL, Liked, a playlist), or a chip's reload */
    suspend fun more(token: String): AccountFeed? =
        if (signedIn) read("browse", mapOf("continuation" to token))?.let { remember(AccountParse.feed(it)) }
        else readHome(mapOf("continuation" to token))

    // ------------------------------------------------------------------ reads

    /** personal Home (FEwhat_to_watch); null signed out. An empty list = history paused or a new account */
    suspend fun home(): AccountFeed? = readHome(mapOf("browseId" to "FEwhat_to_watch"))?.also { if (it.chips.isNotEmpty()) homeChips = it.chips }

    /** Home filtered by one of its chips */
    suspend fun homeChip(chip: FeedChip): AccountFeed? = chip.token?.let { more(it) }?.also { f ->
        homeChips = homeChips.map { it.copy(selected = it.title == chip.title) }
        if (f.chips.isNotEmpty()) homeChips = f.chips
    }

    /** Subscriptions: the newest uploads, with the "Most relevant" row in [AccountFeed.shelves] */
    suspend fun subscriptions(): AccountFeed? = browse("FEsubscriptions")

    /** Watch history, newest first, in day rows ([AccountFeed.shelves]); [query] searches it */
    suspend fun history(query: String? = null): AccountFeed? =
        browse("FEhistory", if (query.isNullOrBlank()) emptyMap() else mapOf("query" to query.trim()))

    suspend fun historyControls(): HistoryControls? = read("browse", mapOf("browseId" to "FEhistory"))?.let(AccountParse::historyControls)

    suspend fun watchLater(): AccountFeed? = browse("VLWL")
    suspend fun liked(): AccountFeed? = browse("VLLL")
    suspend fun playlist(id: String): AccountFeed? = browse("VL$id")

    /** the account's playlists */
    suspend fun playlists(): List<AccountPlaylist>? {
        val agg = try { read("browse", mapOf("browseId" to "FEplaylist_aggregation")) } catch (e: YtError) { null }
        val all = AccountParse.allPlaylists(agg).takeIf { l -> l.any { it.id != "WL" && it.id != "LL" } }
            ?: read("browse", mapOf("browseId" to "FElibrary"))?.let(AccountParse::allPlaylists) ?: return null
        systemLists = all.filter { it.id == "WL" || it.id == "LL" }.associateBy { it.id }
        return all.filter { it.id != "WL" && it.id != "LL" }
    }

    /** Watch later and Liked videos as the playlists page last showed them (count, picture), by "WL" / "LL" */
    @Volatile var systemLists: Map<String, AccountPlaylist> = emptyMap()
        private set

    /** the subscribed channels, up to [pages] pages */
    suspend fun channels(pages: Int = 20): List<Channel>? {
        var d = read("browse", mapOf("browseId" to "FEchannels")) ?: return null
        if (AccountParse.loggedOut(d)) return null
        val out = LinkedHashMap<String, Channel>()
        repeat(pages) {
            AccountParse.channels(d).forEach { c -> out.getOrPut(c.id) { c } }
            val t = continuationOf(d) ?: return out.values.toList()
            d = read("browse", mapOf("continuation" to t)) ?: return out.values.toList()
        }
        return out.values.toList()
    }

    /** the watch page signed in: rating, subscription, bell, Up Next */
    suspend fun watch(videoId: String): WatchState? =
        read("next", mapOf("videoId" to videoId))?.let { AccountParse.watch(videoId, it) }?.also { w ->
            for ((k, v) in w.actions) seen[k] = v
        }

    /** a comments page's signed-in actions (the same token YouTube.comments takes) */
    suspend fun comments(token: String): CommentSheet? = read("next", mapOf("continuation" to token))?.let(AccountParse::comments)

    // ------------------------------------------------------------------ writes (paced by WriteLimit)

    private suspend fun write(endpoint: String, body: Map<String, Any?>): Boolean {
        if (!signedIn) return false
        return WriteLimit.run {
            try { AccountParse.writeOk(InnerTube.call(endpoint, body)) } catch (e: Exception) { false }
        } ?: false
    }

    private fun obj(vararg kv: Pair<String, Any?>) = buildJsonObject {
        for ((k, v) in kv) when (v) {
            null -> {}
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is JsonElement -> put(k, v)
            else -> put(k, v.toString())
        }
    }
    private fun arr(vararg e: Any) = buildJsonArray { for (x in e) add(if (x is JsonElement) x else JsonPrimitive(x.toString())) }

    suspend fun like(videoId: String, params: String? = null) = write("like/like", mapOf("target" to obj("videoId" to videoId), "params" to params))
    suspend fun dislike(videoId: String, params: String? = null) = write("like/dislike", mapOf("target" to obj("videoId" to videoId), "params" to params))
    suspend fun removeLike(videoId: String, params: String? = null) = write("like/removelike", mapOf("target" to obj("videoId" to videoId), "params" to params))

    suspend fun subscribe(channelId: String) = write("subscription/subscribe", mapOf("channelIds" to arr(channelId), "params" to "EgIIAhgA"))
    suspend fun unsubscribe(channelId: String) = write("subscription/unsubscribe", mapOf("channelIds" to arr(channelId), "params" to "CgIIAhgA"))

    /** the bell: every upload, personalized or none ([params] from [WatchState.bellParams] when known) */
    suspend fun setBell(channelId: String, bell: Bell, params: String? = null) =
        write("notification/modify_channel_preference", mapOf("params" to (params ?: bellParams(channelId, bell))))

    /** NotificationPreferences { channel_id = 1; pref { index = 1 } = 2; 3 = 0; 4 = 4 }, as YouTube.js writes it */
    @OptIn(ExperimentalEncodingApi::class)
    fun bellParams(channelId: String, bell: Bell): String {
        val out = ArrayList<Byte>()
        Proto.bytes(out, 1, channelId.encodeToByteArray())
        Proto.bytes(out, 2, ArrayList<Byte>().also { Proto.int(it, 1, bell.index) }.toByteArray())
        Proto.int(out, 3, 0)
        Proto.int(out, 4, 4)
        return urlEncode(Base64.encode(out.toByteArray()))
    }

    /** sends one or more feedback tokens ("Not interested", "Don't recommend channel", remove from / clear / pause history) */
    suspend fun feedback(vararg tokens: String): Boolean = tokens.isNotEmpty() &&
        write("feedback", mapOf("feedbackTokens" to arr(*tokens), "isFeedbackTokenUnencrypted" to false, "shouldMerge" to false))

    /** "Not interested" ([channel] = "Don't recommend channel") on a video seen in a signed-in list; false when no token is known */
    suspend fun notInterested(videoId: String, channel: Boolean): Boolean {
        val a = seen[videoId] ?: return false
        val t = (if (channel) a.dontRecommendChannel else a.notInterested) ?: return false
        return feedback(t)
    }

    /** removes a video from watch history (its token comes from a history page) */
    suspend fun removeFromHistory(videoId: String): Boolean {
        val t = seen[videoId]?.removeFromHistory ?: history()?.actions?.get(videoId)?.removeFromHistory ?: return false
        return feedback(t).also { if (it) seen.remove(videoId) }
    }

    suspend fun clearHistory(): Boolean = historyControls()?.clear?.let { feedback(it) } ?: false
    /** pauses ([on] false) or turns watch history back on */
    suspend fun setHistoryOn(on: Boolean): Boolean = historyControls()?.let { c -> (if (on) c.resume else c.pause)?.let { feedback(it) } } ?: false

    /** adds or removes videos in a playlist ("WL" = Watch later) */
    suspend fun editPlaylist(playlistId: String, add: List<String> = emptyList(), remove: List<String> = emptyList()): Boolean {
        val actions = buildJsonArray {
            for (v in add) add(obj("action" to "ACTION_ADD_VIDEO", "addedVideoId" to v))
            for (v in remove) add(obj("action" to "ACTION_REMOVE_VIDEO_BY_VIDEO_ID", "removedVideoId" to v))
        }
        if (actions.isEmpty()) return true
        return write("browse/edit_playlist", mapOf("playlistId" to playlistId, "actions" to actions))
    }

    suspend fun renamePlaylist(playlistId: String, title: String) = write(
        "browse/edit_playlist",
        mapOf("playlistId" to playlistId, "actions" to buildJsonArray { add(obj("action" to "ACTION_SET_PLAYLIST_NAME", "playlistName" to title)) }),
    )

    /** makes a private playlist; its id, or null */
    suspend fun createPlaylist(title: String, videoIds: List<String> = emptyList(), privacy: String = "PRIVATE"): String? {
        if (!signedIn) return null
        return WriteLimit.run {
            try {
                AccountParse.createdPlaylist(InnerTube.call("playlist/create",
                    mapOf("title" to title, "privacyStatus" to privacy, "videoIds" to arr(*videoIds.toTypedArray()))))
            } catch (e: Exception) { null }
        }
    }

    suspend fun deletePlaylist(playlistId: String) = write("playlist/delete", mapOf("playlistId" to playlistId))

    /** posts a comment ([CommentSheet.createParams]) */
    suspend fun postComment(createParams: String, text: String) =
        write("comment/create_comment", mapOf("createCommentParams" to createParams, "commentText" to text))

    /** answers a comment ([CommentActions.replyParams]) */
    suspend fun reply(replyParams: String, text: String) =
        write("comment/create_comment_reply", mapOf("createReplyParams" to replyParams, "commentText" to text))

    /** likes, unlikes... a comment (one of [CommentActions]' actions) */
    suspend fun commentAction(action: String) = write("comment/perform_comment_action", mapOf("actions" to arr(action)))
}
