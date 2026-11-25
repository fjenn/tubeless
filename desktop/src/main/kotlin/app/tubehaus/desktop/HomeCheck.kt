package app.tubehaus.desktop

import app.tubehaus.engine.Auth
import app.tubehaus.engine.InnerTube
import app.tubehaus.engine.Notifications
import app.tubehaus.engine.YouTubeAccount
import app.tubehaus.engine.parseItems
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

/** EVAL: ./gradlew :desktop:homeCheck -Pcookies=/path/cookies.txt  -> FEwhat_to_watch, signed in when the file exists */
fun main(args: Array<String>) = runBlocking {
    val f = File(args.firstOrNull() ?: "")
    val signed = Auth.load(f)
    println("cookie file: ${if (f.exists()) "found" else "absent"}; signed-in headers: $signed")
    val t = System.currentTimeMillis()
    val d = InnerTube.call("browse", mapOf("browseId" to "FEwhat_to_watch"))
    val loggedOut = d.jsonObject["responseContext"]?.jsonObject?.get("mainAppWebResponseContext")?.jsonObject?.get("loggedOut")
    val items = parseItems(d)
    println("browse FEwhat_to_watch ${System.currentTimeMillis() - t}ms loggedOut=$loggedOut items=${items.size}")
    items.take(8).forEach { println("  - $it".take(160)) }
    // the stand-in for an empty Home: popular videos from YouTube's destination channels
    val t2 = System.currentTimeMillis()
    val pop = app.tubehaus.engine.YouTube.popular(shorts = true)
    val noShorts = app.tubehaus.engine.YouTube.popular(shorts = false)
    println("popular ${System.currentTimeMillis() - t2}ms: ${pop.size} videos (${pop.count { it.short }} Shorts); Shorts off: ${noShorts.size} (${noShorts.count { it.short }} Shorts); ads seen ${app.tubehaus.engine.Ads.count}")
    pop.take(12).forEach { println("  - ${it.author} | ${it.title}".take(120)) }
    account()
}

/**
 * S4: every signed-in read of YouTubeAccount / Notifications (no writes). Signed out each must answer
 * null (the screens then keep their local lists); signed in each should load. Exit 1 on a failure.
 */
private suspend fun account() {
    val signed = YouTubeAccount.signedIn
    var bad = 0
    suspend fun check(name: String, block: suspend () -> String?) {
        val t = System.currentTimeMillis()
        val r = try { block() } catch (e: Exception) { "threw $e" }
        // signed in, an answer "as to a signed-out visitor" means the cookies don't work (expired, rotated)
        val ok = if (signed) r != null && !r.startsWith("threw") && "loggedOut=true" !in r else r == null
        if (!ok) bad++
        println("${if (ok) "ok  " else "FAIL"} $name ${System.currentTimeMillis() - t}ms: ${r ?: "null (signed out: local fallback)"}".take(220))
    }
    println("\n== account reads (${if (signed) "signed in: each should load" else "signed out: each should be null"})")
    check("home") { YouTubeAccount.home()?.let { "loggedOut=${it.loggedOut} items=${it.items.size} chips=${it.chips.map { c -> c.title }} next=${it.next != null} tokens=${it.actions.size}" } }
    check("subscriptions") { YouTubeAccount.subscriptions()?.let { "loggedOut=${it.loggedOut} items=${it.items.size} shelves=${it.shelves.map { s -> s.title + ":" + s.items.size }}" } }
    check("history") { YouTubeAccount.history()?.let { "days=${it.shelves.map { s -> s.title + ":" + s.items.size }} removable=${it.actions.values.count { a -> a.removeFromHistory != null }}" } }
    check("history search") { YouTubeAccount.history("music")?.let { "found=${it.everything.size}" } }
    check("history controls") { YouTubeAccount.historyControls()?.let { "clear=${it.clear != null} pause=${it.pause != null} paused=${it.paused}" } }
    check("watch later") { YouTubeAccount.watchLater()?.let { "items=${it.items.size}" } }
    check("liked") { YouTubeAccount.liked()?.let { "items=${it.items.size}" } }
    check("playlists") { YouTubeAccount.playlists()?.let { "n=${it.size} ${it.take(3).map { p -> p.title }}" } }
    check("channels") { YouTubeAccount.channels(2)?.let { "n=${it.size}" } }
    check("watch state") { YouTubeAccount.watch("dQw4w9WgXcQ")?.let { "like=${it.like} subscribed=${it.subscribed} bell=${it.bell} upNext=${it.upNext.size} loggedOut=${it.loggedOut}" } }
    check("notifications") { Notifications.inbox()?.let { "n=${it.items.size} sections=${it.items.map { n -> n.section }.distinct()}" } }
    check("unseen") { Notifications.unseen()?.toString() }
    if (signed) println("cookies expired (YouTube answered signed out): ${YouTubeAccount.expired}")
    println(if (bad == 0) "account: all ok" else "account: $bad failed")
    if (bad > 0) kotlin.system.exitProcess(1)
}
