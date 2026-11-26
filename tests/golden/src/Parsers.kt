package app.tubeless.golden

import app.tubeless.engine.AccountParse
import app.tubeless.engine.Bell
import app.tubeless.engine.Notifications
import app.tubeless.engine.WatchTime
import app.tubeless.engine.YouTubeAccount
import app.tubeless.engine.chaptersIn
import app.tubeless.engine.continuationOf
import app.tubeless.engine.parseItems
import app.tubeless.engine.parsePosts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import app.tubeless.engine.at
import app.tubeless.engine.findAll
import app.tubeless.engine.str

/**
 * The parsers golden tests can run. The key is the folder name under tests/golden/fixtures.
 *
 * To test a new parser: add one line here (input: the fixture's JSON, output: anything; it is
 * turned into JSON field by field), make tests/golden/fixtures/<key>/, drop `<case>.json` in it and
 * run goldenCheck with -Pupdate once. Review the `<case>.golden.json` it writes, then commit both.
 */
val PARSERS: Map<String, (JsonElement) -> Any?> = mapOf(
    "parseItems" to { j -> parseItems(j) },
    "continuationOf" to { j -> continuationOf(j) },
    "parsePosts" to { j -> parsePosts(j) },
    // input: a JSON string (a video description)
    "chaptersIn" to { j -> chaptersIn((j as JsonPrimitive).content) },
    // S4, the signed-in account (engine/YouTubeAccount, Notifications, WatchTime)
    "accountFeed" to { j -> AccountParse.feed(j) },
    "historyControls" to { j -> AccountParse.historyControls(j).let { it to it.paused } },
    "watchState" to { j -> AccountParse.watch("wwwwwwwwwww", j) },
    // POLISH: where a video watched on another device picks up. input: {"items": <a signed list>, "next": <a signed next>,
    // "pct": [[percent, length s], ...]}; output: the items' exact points, the page's own point, the percent estimates
    "resumeAt" to { j ->
        mapOf(
            "items" to AccountParse.actions(j.at("items")).mapValues { (_, a) -> listOf(a.resumePercent, a.resumeSec) },
            "next" to AccountParse.watch("wwwwwwwwwww", j.at("next")).resumeSec,
            "fromPercent" to (j.at("pct") as kotlinx.serialization.json.JsonArray).associate { p ->
                val a = p as kotlinx.serialization.json.JsonArray
                val pct = (a[0] as JsonPrimitive).content.toInt(); val len = (a[1] as JsonPrimitive).content.toInt()
                "$pct% of ${len}s" to AccountParse.resumeFromPercent(pct, len)
            },
        )
    },
    "commentSheet" to { j -> AccountParse.comments(j) },
    "notifications" to { j -> Notifications.parse(j) to Notifications.unseenOf(j) },
    "playbackTracking" to { j -> AccountParse.tracking(j) },
    // input: [channelId, "ALL"|"PERSONALIZED"|"NONE"] -> the params written here and the index read back;
    // or [params] -> the index read from params YouTube made
    "bellParams" to { j ->
        val a = (j as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }
        if (a.size == 1) AccountParse.prefIndex(a[0])
        else YouTubeAccount.bellParams(a[0], Bell.valueOf(a[1])).let { it to AccountParse.prefIndex(it) }
    },
    // FIX1: YouTube's Shorts sequence (reel/reel_watch_sequence) and a seeded sequence's params
    "reelSequence" to { j -> app.tubeless.engine.Ads.clear(); app.tubeless.engine.ReelSequence.parse(j) },
    // FIX2: the ads among Shorts and list items: what is left out, and the ad ids the player will refuse
    "adsLeftOut" to { j ->
        app.tubeless.engine.Ads.clear()
        val kept = if (j.at("entries") != null) app.tubeless.engine.ReelSequence.parse(j).map { it.video.id } else parseItems(j).map { it.id }
        val refused = j.findAll("videoId", emptySet()).mapNotNull { it.str }.distinct().filter { app.tubeless.engine.Ads.isAd(it) }
        val played = refused.map { id -> kotlinx.coroutines.runBlocking { runCatching { app.tubeless.engine.YouTube.stream(id) }.exceptionOrNull()?.message } }
        mapOf("kept" to kept, "refused" to refused, "playerSays" to played.distinct())
    },
    "reelSeed" to { j -> app.tubeless.engine.ReelSequence.seedParams((j as JsonPrimitive).content) },
    // input: {base, cpn, cmt, st, et, len, state?, rt, rtn}
    "watchTimeUrl" to { j ->
        val o = j as kotlinx.serialization.json.JsonObject
        fun d(k: String) = (o[k] as JsonPrimitive).content.toDouble()
        WatchTime.url((o["base"] as JsonPrimitive).content, (o["cpn"] as JsonPrimitive).content, d("cmt"), d("st"), d("et"), d("len"),
            (o["state"] as? JsonPrimitive)?.content, d("rt"), (o["rtn"] as JsonPrimitive).content.toInt())
    },
)
