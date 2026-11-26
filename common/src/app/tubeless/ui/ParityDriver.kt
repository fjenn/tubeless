package app.tubeless.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.tubeless.engine.readTextOrNull
import app.tubeless.rec.Learn
import kotlinx.coroutines.delay
import app.tubeless.engine.Sys
import okio.FileSystem
import app.tubeless.engine.YouTube
import app.tubeless.engine.Ads
import app.tubeless.engine.SearchOptions
import app.tubeless.engine.Channel
import app.tubeless.engine.Video
import app.tubeless.engine.YouTubeAccount
import app.tubeless.engine.at
import app.tubeless.engine.InnerTube
import app.tubeless.engine.WatchTime
import app.tubeless.engine.findAll

/**
 * A QA driver for side-by-side parity screenshots (docs/parity/CHECKLIST.md) when nobody can click:
 * it does nothing unless a file `parity-cmd.txt` appears in the app's data folder, then runs its
 * lines once and deletes it. Lines: `setup`, `go home|subs|shorts|library|settings|downloads|history`,
 * `search <q>`, `link <youtube url>`, `back`, `theme 0|1|2`, `refresh` (the page in front reloads), `chip <label>`, `set <flag> <value>` (UI flags read
 * by screens: filters, bell, hover, theater, menu...), `clear`; for the M3 E2E run: `watch <id>`, `seek <s>`,
 * `speed <x>`, `jump`, `pip`, `window min|restore`.
 */
object Parity {
    /** UI states a screen opens when asked (e.g. "filters" → the search filter modal) */
    val flags = mutableStateMapOf<String, String>()
    fun on(k: String) = flags[k] != null && flags[k] != "0"
    /** the desktop's window, for `window min|restore` (set by the platform) */
    var window: ((String) -> Unit)? = null
    /** a key pressed on the window as the keyboard would (desktop), for `key escape` */
    var key: ((String) -> Unit)? = null
    /** the desktop window's sizes and placement, for `report-window` */
    var windowInfo: (() -> String)? = null
    /** the desktop's normal way out (as closing the window), for `quit` */
    var quit: (() -> Unit)? = null
    /** the player menu's sub-page to open with it ("q", "s", "t"...) */
    var menuPage = ""
    /** the Shorts the Shorts page has played, in order (the `shorts` command's report) */
    val played = ArrayList<String>()
    /** a chip asked for by label on the page in front */
    var chip by androidx.compose.runtime.mutableStateOf<String?>(null)
}

@Composable
fun ParityDriver(app: AppState) {
    if (app.platform.tv) return
    LaunchedEffect(Unit) {
        val f = app.platform.dataDir / "parity-cmd.txt"
        while (true) {
            delay(600)
            val text = runCatching { f.readTextOrNull() }.getOrNull() ?: continue
            runCatching { Sys.fs.delete(f) }
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val cmd = line.substringBefore(' ')
                val arg = line.substringAfter(' ', "")
                runCatching {
                    when (cmd) {
                        "setup" -> {
                            app.store.settings.update { it.copy(lang = "en", welcomed = true, winW = 1329, winH = 924, winMax = false, winContent = false) }
                        }
                        // the first-start page's answers (Welcome): "shorts off|on" as its two cards, "welcome done" as Continue
                        "shorts-choice" -> app.store.settings.update { it.copy(hideShorts = arg == "off") }
                        "welcome" -> app.store.settings.update { it.copy(welcomed = true) }
                        // as a click in the guide / tabs: top-level places start the stack over (goTop)
                        "go" -> app.goTop(when (arg) {
                            "subs" -> Screen.Subs; "shorts" -> Screen.Shorts; "library" -> Screen.Library
                            "settings" -> Screen.Settings; "downloads" -> Screen.Downloads; "history" -> Screen.History
                            "wl" -> Screen.Local("wl"); "likes" -> Screen.Local("likes")
                            else -> Screen.Home
                        })
                        "search" -> app.go(Screen.Search(arg))
                        "page" -> { app.go(Screen.Settings); app.go(Screen.SettingsPage(arg)) }
                        "link" -> app.openLink(arg)
                        "back" -> app.back()
                        "theme" -> app.store.settings.update { it.copy(dark = arg.toIntOrNull() ?: 0) }
                        "refresh" -> if (app.current == Screen.Home) HomeCtl.fresh() else app.refreshPage()
                        "chip" -> Parity.chip = arg
                        "set" -> Parity.flags[arg.substringBefore(' ')] = arg.substringAfter(' ', "1")
                        "unset" -> Parity.flags.remove(arg)
                        "clear" -> Parity.flags.clear()
                        "wait" -> delay(arg.toLongOrNull() ?: 1000)
                        // subscribe to channels found by a search (for the Subscriptions page): "subs 3blue1brown|veritasium"
                        "subs" -> for (q in arg.split('|')) {
                            val c = YouTube.search(q, SearchOptions(type = 2)).items.filterIsInstance<Channel>().firstOrNull() ?: continue
                            app.store.subscribe(c.id, c.name, c.avatar, true)
                        }
                        // opens the first video a search finds: "play lofi live" (live: only live streams)
                        "play" -> {
                            val live = arg.endsWith(" live")
                            val q = arg.removeSuffix(" live")
                            val v = YouTube.search(q, SearchOptions(type = 1, live = live)).items.filterIsInstance<Video>().firstOrNull { !it.short }
                            if (v != null) app.watch(v)
                        }
                        // FIX2: the next Short, n times (as the ↓ key), then shorts-report.txt: every Short played and whether
                        // any was an ad (Ads: the ones YouTube marked, left out of the sequence)
                        "shorts" -> {
                            repeat(arg.toIntOrNull() ?: 10) {
                                app.shortsKeys?.invoke(androidx.compose.ui.input.key.Key.DirectionDown)
                                delay(1400)
                            }
                            val ads = Parity.played.filter { Ads.isAd(it) }
                            Sys.fs.write(app.platform.dataDir / "shorts-report.txt") {
                                writeUtf8("played ${Parity.played.size} (${Parity.played.distinct().size} distinct)\nads seen in YouTube's answers and left out: ${Ads.count}\nads played: ${ads.size} $ads\n" + Parity.played.joinToString("\n") + "\n")
                            }
                        }
                        "rail" -> app.store.settings.update { it.copy(railCollapsed = arg == "mini") }
                        "pause" -> app.player.pause()
                        // M3 E2E steps, through the same calls a click or key makes
                        "watch" -> app.watch(Video(arg, ""))
                        "seek" -> app.player.seekTo((arg.toDoubleOrNull() ?: 0.0).times(1000).toLong())
                        "speed" -> app.playback.setSpeed(arg.toFloatOrNull() ?: 1f)
                        "jump" -> { JumpAhead.prompt(app); JumpAhead.take(app) }
                        "pip" -> app.platform.startPip()
                        "window" -> Parity.window?.invoke(arg)
                        "key" -> Parity.key?.invoke(arg)
                        "signin" -> startSignIn(app)
                        "signin-cancel" -> cancelSignIn()
                        "report-window" -> E2E.log("window", "screen" to app.current::class.simpleName, "depth" to app.stack.size, "info" to (Parity.windowInfo?.invoke() ?: ""))
                        "quit" -> Parity.quit?.invoke()
                        "shortcuts" -> Perks.shortcuts = arg != "off"
                        // "download <id>@<height>" as the quality question's Download button; "offline <id>" opens the saved copy
                        "download" -> app.offline.start(Video(arg.substringBefore('@'), arg.substringBefore('@')), arg.substringAfter('@', "").toIntOrNull() ?: DownloadPrefs.BEST)
                        "offline" -> app.offline.saved.value.items[arg]?.let { app.watch(it.asVideo()) }
                        // what sits beside the video: "side comments" or "side next"
                        "side" -> app.store.settings.update { it.copy(sideTab = arg) }
                        // fills Watch later from a search: "wl-add lofi"
                        "wl-add" -> YouTube.search(arg, SearchOptions(type = 1)).items.filterIsInstance<Video>().filter { !it.short }.take(6)
                            .forEach { app.store.setInList("wl", it, true) }
                        "close" -> app.playback.close()
                        // the smoke tests' closing numbers: ad requests refused by the HTTP client, ads left out of YouTube's answers
                        "report" -> E2E.log("report", "adRequests" to Ads.requests, "adsLeftOut" to Ads.count, "screen" to app.current::class.simpleName,
                            "saved" to app.offline.saved.value.items.size)
                        // signed-in checks (docs/BUILD-STATUS.md SIGNED), through the same Store calls the buttons make,
                        // so the account mirror sends them: "like <id> 1|0", "wl <id> 1|0", "pl-new <id> <title>", "pl-del <title>"
                        "like" -> app.store.setInList("likes", Video(arg.substringBefore(' '), ""), arg.substringAfter(' ', "1") == "1")
                        "wl" -> app.store.setInList("wl", Video(arg.substringBefore(' '), ""), arg.substringAfter(' ', "1") == "1")
                        "pl-new" -> app.store.newPlaylist(arg.substringAfter(' '), Video(arg.substringBefore(' '), ""))
                        "pl-del" -> app.store.lib.value.playlists.filter { it.value.title == arg }.keys.forEach { app.store.deletePlaylist(it) }
                        // "dump home|next <id>|subs|history|wl|liked|playlists|bell": what the account answers, one line per item, to dump-<what>.txt
                        "dump" -> {
                            val what = arg.substringBefore(' ')
                            fun line(i: app.tubeless.engine.Item) = when (i) {
                                is Video -> "video\t${i.id}\t${i.author}\t${i.title}"
                                is app.tubeless.engine.Playlist -> "playlist\t${i.id}\t${i.countText}\t${i.title}"
                                is Channel -> "channel\t${i.id}\t\t${i.name}"
                                else -> "item\t${i.id}"
                            }
                            val lines: List<String> = when (what) {
                                "home" -> HomeCache.items.map(::line) + YouTubeAccount.homeChips.map { "chip\t\t\t${it.title}" }
                                "next" -> YouTube.details(arg.substringAfter(' ')).related.map(::line)
                                "subs" -> YouTubeAccount.subscriptions()?.items.orEmpty().map(::line)
                                "history" -> YouTubeAccount.history()?.everything.orEmpty().map(::line)
                                "wl" -> YouTubeAccount.watchLater()?.items.orEmpty().map(::line)
                                "liked" -> YouTubeAccount.liked()?.items.orEmpty().map(::line)
                                "playlists" -> YouTubeAccount.playlists().orEmpty().map { "playlist\t${it.id}\t${it.countText}\t${it.title}" } +
                                    app.store.lib.value.playlists.map { "local\t${it.key}\t${it.value.items.size}\t${it.value.title}" }
                                "bell" -> NotificationCenter.source.load().map { "notice\t${it.videoId}\t${it.important}\t${it.read}\t${it.text}" } +
                                    "unread\t${NotificationCenter.source.unread()}"
                                "state" -> YouTubeAccount.watch(arg.substringAfter(' '))?.let { listOf("like\t${it.like}", "subscribed\t${it.subscribed}") }.orEmpty() +
                                    listOf("wl\t${YouTubeAccount.watchLater()?.items.orEmpty().any { it.id == arg.substringAfter(' ') }}")
                                // the watch-time pings' timing fields (no ids beyond the video's, no tokens) and the player's position
                                "pings" -> WatchTime.sent.map { q -> q.split('&').filter { it.substringBefore('=') in setOf("docid", "len", "cmt", "st", "et", "state", "final", "rtn") }.joinToString("&") } +
                                    "pos\t${app.player.state.value.positionMs / 1000}\t${app.player.state.value.durationMs / 1000}"
                                // the shape of the bell's entries (endpoints only: ids and click tracking, no account data)
                                "bell-raw" -> InnerTube.call("notification/get_notification_menu", mapOf("notificationsMenuRequestType" to "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX"))
                                    .findAll("notificationRenderer", emptySet()).take(4).map { it.at("navigationEndpoint").toString() }
                                else -> emptyList()
                            }
                            Sys.fs.write(app.platform.dataDir / "dump-$what.txt") { writeUtf8(lines.joinToString("\n") + "\n") }
                        }
                        // notifications made from a search's videos, to see the panel's two groups
                        "demo-notices" -> {
                            val vs = YouTube.search(arg.ifEmpty { "science" }, SearchOptions(type = 1)).items.filterIsInstance<Video>().take(8)
                            NotificationCenter.source = object : NotificationSource {
                                override val available = true
                                override suspend fun load() = vs.mapIndexed { i, v ->
                                    Notice(v.id, v.author + " uploaded: " + v.title, v.avatar, v.thumb, v.publishedText, read = i > 2, important = i < 2, videoId = v.id, channelId = v.channelId)
                                }
                                override suspend fun unread() = 3
                            }
                            NotificationCenter.changed.intValue++
                        }
                    }
                }
            }
        }
    }
}
