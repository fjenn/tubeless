package app.tubeless.rec

import app.tubeless.patch.Patchable
import app.tubeless.data.Meta
import app.tubeless.data.Store
import app.tubeless.data.Taste
import app.tubeless.data.storeJson
import kotlinx.serialization.Serializable
import app.tubeless.data.meta
import app.tubeless.engine.Video
import app.tubeless.engine.YouTube
import app.tubeless.engine.FeedChip
import app.tubeless.engine.YouTubeAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import app.tubeless.engine.Sys
import app.tubeless.engine.Volatile
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

// What the app learns about taste on this device (Learn: likes, hides, "Not interested", profiles) and the
// subscriptions' RSS feeds (Feed). The feeds themselves are YouTube's own for the session (Recommender).

private const val DECAY = 0.98          // per day: old tastes fade
private const val CAP = 10.0

private fun now() = Sys.nowMs() / 1000

object Learn {
    private val STOP = setOf("shorts", "short", "youtubeshorts", "viral", "fyp", "trending", "video", "youtube", "new", "official")

    /** Words that describe a video: its tags and the #hashtags in its title. */
    @Patchable
    fun tokens(title: String, keywords: List<String> = emptyList()): List<String> {
        val tags = Regex("#([^\\s#]{2,40})").findAll(title).map { it.groupValues[1] }.toList()
        return (keywords.take(15) + tags).map { it.trim().lowercase().removePrefix("#") }
            .filter { it.length in 2..40 && it !in STOP }.distinct().take(20)
    }

    /** words every kind of title uses: never a taste of their own */
    private val GENERIC = setOf(
        "the", "and", "for", "with", "you", "your", "this", "that", "from", "are", "was", "how", "what", "why", "who", "when",
        "best", "top", "full", "part", "episode", "ep", "vs", "feat", "ft", "live", "music", "mv", "lyrics", "lyric", "audio",
        "hd", "4k", "1080p", "2024", "2025", "2026", "2027", "day", "time", "first", "last", "most", "ever", "all", "can", "will",
        "about", "just", "out", "not", "but", "one", "two", "my", "me", "we", "our", "his", "her", "they", "it", "is", "in", "on",
        "of", "to", "a", "an", "at", "by", "or", "be", "do", "so", "no", "up", "if", "as",
        "공식", "영상", "라이브", "풀버전", "하이라이트", "모음", "최신", "오늘", "이번", "그리고", "진짜", "너무", "정말", "가장",
        "하는", "있는", "없는", "되는", "하기", "보기", "합니다", "입니다", "ㅋㅋ", "ㅋㅋㅋ", "eng", "sub", "kor",
    )
    /** Korean particles: "아이유의", "아이유를" are "아이유" */
    private val PARTICLES = listOf("에서", "으로", "까지", "부터", "에게", "한테", "의", "를", "을", "이", "가", "은", "는", "에", "와", "과", "도", "로")

    /**
     * The runs of letters and digits in [s]. Not a Regex with p{L} classes: Kotlin/Native (the iPhone
     * and iPad app) has none and throws "No such character class" (#79, as #67 before it).
     */
    fun letterRuns(s: String): List<String> {
        val out = ArrayList<String>()
        val b = StringBuilder()
        for (c in s) if (c.isLetterOrDigit()) b.append(c) else if (b.isNotEmpty()) { out += b.toString(); b.clear() }
        if (b.isNotEmpty()) out += b.toString()
        return out
    }

    /** A title's own words, for the videos without tags: lower case, particles and everyday words left out. */
    fun words(title: String): List<String> =
        title.lowercase().replace(Regex("#[^\\s#]+"), " ").let(::letterRuns).asSequence()
            .map { w ->
                val hangul = w.any { it in '\uAC00'..'\uD7A3' }
                if (!hangul) w else PARTICLES.firstOrNull { w.length > it.length + 1 && w.endsWith(it) }?.let { w.dropLast(it.length) } ?: w
            }
            .filter { it.length >= 2 && it !in GENERIC && it !in STOP && !it.all(Char::isDigit) }
            .distinct().take(12).toList()

    /** the part of the week now: weekday or weekend, morning, afternoon, evening or night */
    fun bucket(): String {
        val h = Sys.hour()
        val d = Sys.today().split('-').mapNotNull { it.toIntOrNull() }
        val dow = if (d.size == 3) {
            // Sakamoto's method: 0 Sunday .. 6 Saturday
            val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
            val y = if (d[1] < 3) d[0] - 1 else d[0]
            (y + y / 4 - y / 100 + y / 400 + t[d[1] - 1] + d[2]) % 7
        } else 1
        val part = when (h) { in 6..11 -> "m"; in 12..17 -> "a"; in 18..23 -> "e"; else -> "n" }
        return (if (dow == 0 || dow == 6) "we" else "wd") + part
    }

    /** A search: its words count a little as an interest, now and at this time of day. */
    fun searched(store: Store, q: String) {
        val ws = words(q).take(5)
        if (ws.isEmpty()) return
        val b = bucket()
        store.taste.update { t ->
            val kw = t.kw.toMutableMap()
            for (w in ws) kw[w] = minOf(4.0, maxOf(0.0, kw[w] ?: 0.0) + 0.6)
            val bt = (t.kwTime[b] ?: emptyMap()).toMutableMap()
            for (w in ws) bt[w] = minOf(4.0, (bt[w] ?: 0.0) + 0.4)
            t.copy(kw = kw, kwTime = t.kwTime + (b to bt))
        }
    }

    /** How much one viewing says you liked it: skipped < 0 < watched < finished < replayed < liked. */
    @Patchable
    fun engagement(watched: Double, length: Double, liked: Boolean = false, disliked: Boolean = false): Double {
        val len = length.coerceAtLeast(1.0)
        val ratio = watched.coerceAtLeast(0.0) / len
        var e = when {
            watched < 2 || ratio < 0.15 -> -0.8
            ratio < 0.5 -> 0.2
            ratio < 0.85 -> 0.6
            else -> 1.0 + 0.5 * minOf(3, if (ratio >= 2) ratio.toInt() - 1 else 0)
        }
        if (liked) e += 2.0
        if (disliked) e -= 3.0
        return e
    }

    private fun decayed(t: Taste): Taste {
        val days = (now() - (if (t.decayed == 0L) now() else t.decayed)) / 86400.0
        if (days < 0.25) return if (t.decayed == 0L) t.copy(decayed = now()) else t
        val f = DECAY.pow(days)
        fun m(x: Map<String, Double>) = x.mapValues { it.value * f }.filterValues { abs(it) >= 0.05 }
        return t.copy(ch = m(t.ch), kw = m(t.kw), kwTime = t.kwTime.mapValues { m(it.value) }, decayed = now())
    }

    /** Learn from one viewing. [short]: also remember it as seen so it doesn't come back. */
    fun learn(store: Store, v: Video, keywords: List<String>, watched: Double, length: Double,
              liked: Boolean = false, disliked: Boolean = false, short: Boolean = false) {
        val e = engagement(watched, length, liked, disliked)
        val toks = tokens(v.title, keywords)
        // the title's words count too, less than the tags (most videos found in lists have no tags)
        val ws = words(v.title).filter { it !in toks }
        val b = bucket()
        store.taste.update { t0 ->
            val t = decayed(t0)
            val ch = t.ch.toMutableMap()
            val names = t.names.toMutableMap()
            if (v.channelId.startsWith("UC")) {
                ch[v.channelId] = ((ch[v.channelId] ?: 0.0) + e).coerceIn(-CAP, CAP)
                names[v.channelId] = v.author.take(100)
            }
            val kw = t.kw.toMutableMap()
            for (k in toks) kw[k] = ((kw[k] ?: 0.0) + e * 0.5).coerceIn(-CAP, CAP)
            for (k in ws) kw[k] = ((kw[k] ?: 0.0) + e * 0.2).coerceIn(-CAP, CAP)
            // the same words by the time of the week: what you watch on weekday mornings, on weekend nights...
            val bt = (t.kwTime[b] ?: emptyMap()).toMutableMap()
            for (k in toks + ws) bt[k] = ((bt[k] ?: 0.0) + e * 0.4).coerceIn(-CAP, CAP)
            val seen = if (short) (t.seen + (v.id to now())).let { s ->
                if (s.size > 3000) s.entries.sortedByDescending { it.value }.take(3000).associate { it.key to it.value } else s
            } else t.seen
            fun trim(x: Map<String, Double>, keep: Int) =
                if (x.size > keep) x.entries.sortedByDescending { abs(it.value) }.take(keep).associate { it.key to it.value } else x
            val chT = trim(ch, 400)
            t.copy(ch = chT, kw = trim(kw, 900), seen = seen, names = names.filterKeys { it in chT },
                kwTime = t.kwTime + (b to trim(bt, 150)))
        }
    }

    /** Explicit signals from buttons. */
    fun nudgeChannel(store: Store, channelId: String, name: String, by: Double) = store.taste.update { t ->
        t.copy(ch = t.ch + (channelId to ((t.ch[channelId] ?: 0.0) + by).coerceIn(-CAP, CAP)), names = t.names + (channelId to name))
    }

    fun notInterested(store: Store, v: Video, channel: Boolean) {
        // signed in: YouTube is told too (its menu token for this video, when one was seen)
        store.mirror?.notInterested(v, channel)
        hideHere(store, v, channel)
    }

    private fun hideHere(store: Store, v: Video, channel: Boolean) = store.taste.update { t ->
        if (channel) t.copy(hiddenChannels = (t.hiddenChannels + v.channelId).distinct().takeLast(500),
            ch = t.ch + (v.channelId to -CAP))
        else t.copy(hiddenVideos = (t.hiddenVideos + v.id).distinct().takeLast(2000),
            ch = if (v.channelId.isEmpty()) t.ch else t.ch + (v.channelId to ((t.ch[v.channelId] ?: 0.0) - 1.5).coerceIn(-CAP, CAP)))
    }

    fun reset(store: Store) = store.taste.update { Taste() }
}

/**
 * Taste profiles: separate sets of what was learned, say one for machining videos, one for
 * documentaries and one for music, switched from Home. The one in use is store.taste, so learning
 * and ranking work unchanged; the others wait in store.profiles. What you hid and the Shorts
 * already shown count for every profile.
 */
object Profiles {
    fun active(store: Store): String = store.settings.value.profile

    /** the main profile ("") first, then the others by name */
    fun names(store: Store): List<String> =
        listOf("") + (store.profiles.value.keys + active(store)).filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }

    fun switch(store: Store, name: String) {
        val from = active(store)
        if (name == from) return
        val cur = store.taste.value
        val next = store.profiles.value[name] ?: Taste()
        store.profiles.update { it - name + (from to cur.copy(seen = emptyMap(), hiddenVideos = emptyList(), hiddenChannels = emptyList())) }
        store.taste.update { next.copy(seen = cur.seen, hiddenVideos = cur.hiddenVideos, hiddenChannels = cur.hiddenChannels) }
        store.settings.update { it.copy(profile = name) }
    }

    /** false when the name is taken or empty */
    fun rename(store: Store, old: String, new: String): Boolean {
        val n = new.trim()
        if (old.isEmpty() || n.isEmpty() || n in names(store)) return false
        if (old == active(store)) store.settings.update { it.copy(profile = n) }
        else store.profiles.update { m -> m[old]?.let { (m - old) + (n to it) } ?: m }
        return true
    }

    fun delete(store: Store, name: String) {
        if (name.isEmpty()) return
        if (name == active(store)) switch(store, "")
        store.profiles.update { it - name }
    }

    @Serializable
    private class ProfileFile(val tubeless: String, val version: Int, val name: String, val taste: Taste)

    /**
     * A profile as a file to keep or give to someone: only the channel and topic scores and the
     * channel names. No history, no related videos, nothing you hid.
     */
    fun export(store: Store, name: String): Pair<String, ByteArray> {
        val t = if (name == active(store)) store.taste.value else store.profiles.value[name] ?: Taste()
        val clean = Taste(ch = t.ch, kw = t.kw, names = t.names.filterKeys { it in t.ch })
        val slug = buildString {
            for (c in name.ifEmpty { "main" }) if (c.isLetterOrDigit() || c == '_' || c == '-') append(c) else if (lastOrNull() != '-') append('-')
        }.trim('-').ifEmpty { "profile" }
        val json = storeJson.encodeToString(ProfileFile.serializer(), ProfileFile("profile", 1, name, clean))
        return "tubeless-profile-$slug.json" to json.encodeToByteArray()
    }

    /** Adds the profile in [text] next to the others (not switched to); its name, or null when it isn't a profile file. */
    fun import(store: Store, text: String, mainName: String): String? {
        val f = runCatching { storeJson.decodeFromString(ProfileFile.serializer(), text) }.getOrNull() ?: return null
        if (f.tubeless != "profile" || f.taste.ch.isEmpty() && f.taste.kw.isEmpty()) return null
        val base = f.name.trim().take(40).ifEmpty { mainName }
        val taken = names(store)
        var n = base
        var i = 2
        while (n in taken) n = "$base (${i++})"
        val t = f.taste
        store.profiles.update {
            it + (n to Taste(
                ch = t.ch.mapValues { (_, v) -> v.coerceIn(-CAP, CAP) },
                kw = t.kw.entries.take(2000).associate { (k, v) -> k to v.coerceIn(-CAP, CAP) },
                names = t.names.filterKeys { it in t.ch },
            ))
        }
        return n
    }
}

/** New uploads of every subscription, from their RSS feeds (fast, no API quota, no account). */
class Feed(private val store: Store) {
    private val cache = Sys.concurrentMap<String, Pair<Long, List<Video>>>()
    @Volatile var updated = 0L
        private set
    /** the last [all]: not one channel's feed came back and nothing was kept from before (YouTube out of reach) */
    @Volatile var unreachable = false
        private set

    suspend fun channel(cid: String, maxAge: Long = 900): List<Video> = fetch(cid, maxAge) ?: emptyList()

    /** null: it failed and nothing is kept from before */
    private suspend fun fetch(cid: String, maxAge: Long): List<Video>? {
        cache[cid]?.let { (t, v) -> if (now() - t < maxAge) return v }
        return try {
            YouTube.feed(cid).also { cache[cid] = now() to it }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            cache[cid]?.second
        }
    }

    suspend fun all(force: Boolean = false): List<Video> = withContext(Sys.IO) {
        val subs = store.lib.value.subs
        val gate = Semaphore(8)
        val lists = coroutineScope {
            subs.keys.map { cid -> async { gate.withPermit { fetch(cid, if (force) 60 else 900) } } }.awaitAll()
        }
        updated = now()
        unreachable = lists.isNotEmpty() && lists.all { it == null }
        lists.filterNotNull().flatten().map { v ->
            // the feed has no avatar; the subscription remembers one
            if (v.avatar.isEmpty()) v.copy(avatar = subs[v.channelId]?.thumb ?: "") else v
        }.sortedByDescending { it.publishedAt }
    }

    fun cached(): List<Video> = cache.values.flatMap { it.second }.sortedByDescending { it.publishedAt }
}

/**
 * What Home and the Shorts rows show: YouTube's own answers for the one YouTube session (signed in: the
 * account's, signed out: the remembered visitor's), exactly what youtube.com would show this viewer. Nothing
 * is ranked or made up here. When YouTube can't be reached the screens say so (offline: Downloads).
 */
class Recommender(private val store: Store) {
    @Volatile private var accountNext: String? = null
    /** YouTube's Home answered with videos: its further pages come from it too */
    @Volatile private var accountOn = false
    /** the chip picked above Home (null = All) */
    @Volatile var accountChip: FeedChip? = null
        private set
    /** the Shorts row of YouTube's last Home answer (for the Shorts shelf) */
    @Volatile var homeShorts: List<Video> = emptyList()
        private set

    fun clearCache() { accountNext = null; accountOn = false; homeShorts = emptyList() }

    /** YouTube's Home chips ("All", topics, "New to you"…), from its last Home answer */
    val chips: List<FeedChip> get() = YouTubeAccount.homeChips

    /** picks a chip (null = All); Home then loads again (clearCache is called here) */
    fun pickChip(chip: FeedChip?) { accountChip = chip?.takeIf { it.token != null }; clearCache() }

    /**
     * Home, page by page ([skip]: what is shown already; empty = the first page). An empty list on the first
     * page is YouTube's own empty Home (a new visitor, history paused). Throws when YouTube can't be reached.
     */
    suspend fun home(@Suppress("UNUSED_PARAMETER") n: Int = 60, skip: Set<String> = emptySet()): List<Video> = withContext(Dispatchers.Default) {
        val first = skip.isEmpty() || !accountOn
        val f = if (first) accountChip?.let { YouTubeAccount.homeChip(it) } ?: YouTubeAccount.home()
            else accountNext?.let { YouTubeAccount.more(it) }
        if (f == null) return@withContext emptyList()
        if (YouTubeAccount.signedIn && f.loggedOut) {
            if (first) throw app.tubeless.engine.YtError("YouTube didn't accept your sign-in just now. Try again in a moment.", app.tubeless.engine.YtError.Kind.NETWORK)
            return@withContext emptyList()
        }
        val t = store.taste.value
        val hidden = t.hiddenVideos.toHashSet()
        val list = f.videos.filter { it.id !in skip && it.id !in hidden && it.channelId !in t.hiddenChannels }
        shortsOf(f)?.let { homeShorts = it }
        accountOn = true
        accountNext = f.next
        list
    }

    /**
     * What Home shows while YouTube's own Home is empty (a new visitor, history paused): popular videos from
     * YouTube's destination channels for the region. Its Shorts go to the Shorts row ([homeShorts]) when
     * [shorts] is on; none at all when off. Hidden videos and channels stay out. Throws when YouTube can't be reached.
     */
    suspend fun popular(shorts: Boolean): List<Video> = withContext(Dispatchers.Default) {
        val t = store.taste.value
        val hidden = t.hiddenVideos.toHashSet()
        val all = app.tubeless.engine.YouTube.popular(shorts)
            .filter { it.id !in hidden && it.channelId !in t.hiddenChannels }
        if (shorts && homeShorts.isEmpty()) all.filter { it.short }.takeIf { it.isNotEmpty() }?.let { homeShorts = it }
        all.filter { !it.short }
    }

    private fun shortsOf(f: app.tubeless.engine.AccountFeed): List<Video>? =
        f.shelves.map { sh -> sh.items.filterIsInstance<Video>().filter { it.short } }.firstOrNull { it.isNotEmpty() }

    /**
     * Shorts for the rows (Home's shelf, smart downloads): Home's own Shorts row, else the start of YouTube's
     * Shorts sequence for this session. Throws when YouTube can't be reached.
     */
    suspend fun shorts(n: Int = 10, skip: Set<String> = emptySet()): List<Video> = withContext(Dispatchers.Default) {
        if (homeShorts.isEmpty()) runCatching { YouTubeAccount.home() }.getOrNull()?.let { f -> shortsOf(f)?.let { homeShorts = it } }
        homeShorts.filter { it.id !in skip }.takeIf { it.isNotEmpty() }?.let { return@withContext it.take(n) }
        val seq = app.tubeless.engine.ReelSequence()
        (seq.start(null) + seq.more()).map { it.video }.filter { it.id !in skip }.take(n)
    }
}
