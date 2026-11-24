package app.tubehaus.desktop

import app.tubehaus.engine.Accounts
import app.tubehaus.engine.AccountsData
import app.tubehaus.engine.Ck
import app.tubehaus.engine.Clients
import app.tubehaus.engine.CookieJar
import app.tubehaus.engine.Identity
import app.tubehaus.engine.InnerTube
import app.tubehaus.engine.MemorySecrets
import app.tubehaus.engine.SapisidHash
import app.tubehaus.engine.Session
import app.tubehaus.engine.json
import app.tubehaus.engine.parse
import kotlinx.serialization.encodeToString
import kotlin.system.exitProcess

/**
 * Offline unit checks of the account code (S3), fake cookies only, no network:
 * ./gradlew :desktop:accountCheck  (or the init script in docs/BUILD-STATUS.md S3 until S0 adds the task)
 */
fun main() {
    var failed = 0
    var passed = 0
    fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
        if (ok) passed++ else { failed++; println("FAIL $name ${detail()}") }
    }
    val ts = 1_700_000_000L
    val origin = "https://www.youtube.com"

    // ---- SAPISIDHASH
    val only = SapisidHash.authorization(mapOf("SAPISID" to "SAPISIDfake/abc"), origin, ts)
    check("sapisidhash plain", only == "SAPISIDHASH 1700000000_8811ca9c1f5c5a1cedc8afe5e2cfa601f6a876f6") { "$only" }
    val all = SapisidHash.authorization(mapOf("SAPISID" to "SAPISIDfake/abc", "__Secure-1PAPISID" to "1PSIDfake", "__Secure-3PAPISID" to "3PSIDfake"), origin, ts)
    check("sapisidhash 1P/3P", all == "SAPISIDHASH 1700000000_8811ca9c1f5c5a1cedc8afe5e2cfa601f6a876f6 " +
        "SAPISID1PHASH 1700000000_be81b3e5a1ea8056c637914d4f885b64217fb7b1 SAPISID3PHASH 1700000000_36d5a1c59a91a0bdf1d856489087b3cb6cd0573b") { "$all" }
    check("sapisidhash 3P fallback", SapisidHash.authorization(mapOf("__Secure-3PAPISID" to "3PSIDfake"), origin, ts)!!.startsWith("SAPISIDHASH 1700000000_36d5a1c5"))
    check("sapisidhash none", SapisidHash.authorization(mapOf("SID" to "x"), origin, ts) == null)
    val u = SapisidHash.authorization(mapOf("SAPISID" to "s"), origin, ts, userSessionId = "123")!!
    check("sapisidhash _u", Regex("^SAPISIDHASH 1700000000_[0-9a-f]{40}_u$").matches(u)) { u }
    check("sapisidhash _u hash", u.split('_')[1] == SapisidHash.sha1Hex("123 1700000000 s $origin"))

    // ---- jar: header, cookies.txt, Set-Cookie rotation and deletion
    val txt = """
        # Netscape HTTP Cookie File
        .youtube.com	TRUE	/	TRUE	1900000000	SAPISID	sap1
        #HttpOnly_.youtube.com	TRUE	/	TRUE	1900000000	__Secure-3PSIDTS	old
        .google.com	TRUE	/	TRUE	1900000000	SID	google-only
        .youtube.com	TRUE	/	TRUE	1600000000	EXPIRED	x
    """.trimIndent()
    val jar = CookieJar.fromNetscape(txt)
    check("netscape youtube only", jar.cookies.none { it.name == "SID" })
    check("netscape header", jar.header(nowS = ts) == "SAPISID=sap1; __Secure-3PSIDTS=old") { jar.header(nowS = ts) }
    check("netscape httponly", jar.cookies.first { it.name == "__Secure-3PSIDTS" }.httpOnly)
    check("sapisid", jar.sapisid(ts) == "sap1")
    check("fromText header", CookieJar.fromText("Cookie: SAPISID=a; PREF=f").header(nowS = ts) == "SAPISID=a; PREF=f")
    val rot = CookieJar(jar.cookies)
    val changed = rot.apply(listOf(
        "__Secure-3PSIDTS=new; Domain=.youtube.com; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Secure; HttpOnly",
        "EVIL=1; Domain=.google.com; Path=/",
        "PREF=f6=40000000; Max-Age=3600; Path=/; Domain=.youtube.com",
    ), "www.youtube.com", ts)
    check("rotation changed", changed)
    check("rotation value", rot.values(nowS = ts)["__Secure-3PSIDTS"] == "new")
    check("rotation expires parsed", rot.cookies.first { it.name == "__Secure-3PSIDTS" }.expires == 1792567680L) { "${rot.cookies.first { it.name == "__Secure-3PSIDTS" }.expires}" }
    check("foreign domain refused", rot.cookies.none { it.name == "EVIL" })
    check("max-age", rot.cookies.first { it.name == "PREF" }.expires == ts + 3600)
    check("same again unchanged", !rot.apply(listOf("__Secure-3PSIDTS=new; Domain=.youtube.com; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Secure; HttpOnly"), "www.youtube.com", ts))
    check("delete", rot.apply(listOf("PREF=; Max-Age=0; Domain=.youtube.com; Path=/"), "www.youtube.com", ts) && rot.cookies.none { it.name == "PREF" })
    check("host-only cookie", CookieJar.parseSetCookie("YSC=y; Path=/", "www.youtube.com", ts)?.domain == "www.youtube.com")
    check("other host refused", CookieJar.parseSetCookie("A=1; Domain=.youtube.com", "evil.example", ts) == null)
    val joined = CookieJar.splitJoined("A=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/, B=2; Path=/,C=3")
    check("split joined Set-Cookie (iOS)", joined.map { it.substringBefore('=') } == listOf("A", "B", "C")) { "$joined" }
    check("sameAs ignores order", CookieJar.fromHeader("a=1; b=2").sameAs(CookieJar.fromHeader("b=2; a=1")))

    // ---- signed-in rule: only WEB-family calls carry the account
    InnerTube.authHeaders = { mapOf("Authorization" to "SAPISIDHASH x", "Cookie" to "SAPISID=x") }
    for (c in listOf(Clients.WEB to "browse", Clients.WEB to "next", Clients.WEB to "like/like", Clients.WEB to "player", Clients.MUSIC to "browse"))
        check("signs ${c.first.name} ${c.second}", InnerTube.signs(c.first, c.second))
    for (c in listOf(Clients.VISIONOS, Clients.ANDROID_VR, Clients.KIDS, Clients.TV, Clients.MUSIC_ANDROID, Clients.WEB_SABR))
        check("anonymous ${c.name} player", !InnerTube.signs(c, "player"))
    check("visitor_id anonymous", !InnerTube.signs(Clients.WEB, "visitor_id"))
    check("exception auth=true", InnerTube.signs(Clients.TV, "player", auth = true))
    check("auth=false", !InnerTube.signs(Clients.WEB, "browse", auth = false))
    check("headers attach when signed", InnerTube.headers(Clients.WEB, "v", signed = true)["Authorization"] == "SAPISIDHASH x")
    check("headers bare when not", InnerTube.headers(Clients.VISIONOS, "v", signed = false).keys.none { it == "Authorization" || it == "Cookie" })
    InnerTube.authHeaders = null
    check("signed out never signs", !InnerTube.signs(Clients.WEB, "browse"))

    // ---- Accounts: a saved session (fake cookies) is restored and signs calls; rotation is captured
    val me = Identity("Fake Person", "@fake", "fake@example.com", null, gaiaId = "g1")
    val brand = Identity("Fake Brand", "@brand", pageId = "p123", gaiaId = "g2")
    val s = Session("acct-test", jar.cookies, listOf(me, brand), active = brand.key, addedAt = ts, source = "import")
    val store = MemorySecrets().apply { write("accounts", json.encodeToString(AccountsData(listOf(s), "acct-test"))) }
    var changes = 0
    Accounts.now = { ts }
    Accounts.onChange += { changes++ }
    Accounts.setup(store, null)
    check("restored signed in", Accounts.signedIn && InnerTube.authHeaders != null)
    check("onChange on restore", changes == 1) { "$changes" }
    val h = Accounts.headers(origin)
    check("headers cookie", h["Cookie"] == "SAPISID=sap1; __Secure-3PSIDTS=old") { "${h["Cookie"]}" }
    check("headers hash", h["Authorization"] == SapisidHash.authorization(mapOf("SAPISID" to "sap1"), origin, ts))
    check("brand page id", h["X-Goog-PageId"] == "p123" && InnerTube.onBehalfOf?.invoke() == "p123")
    check("auth user", h["X-Goog-AuthUser"] == "0")
    Accounts.capture("https://www.youtube.com/youtubei/v1/browse", listOf("__Secure-3PSIDTS=rotated; Domain=.youtube.com; Path=/"))
    check("capture rotates live jar", Accounts.headers(origin)["Cookie"]!!.contains("__Secure-3PSIDTS=rotated"))
    check("rotation fires no onChange", changes == 1)
    Accounts.capture("https://rr1---sn.googlevideo.com/videoplayback", listOf("X=1; Path=/"))
    check("non-youtube capture ignored", !Accounts.headers(origin)["Cookie"]!!.contains("X=1"))
    kotlinx.coroutines.runBlocking {
        Accounts.choose("acct-test", me.key)
        check("switch channel fires onChange", changes == 2) { "$changes" }
        check("personal channel no page id", Accounts.headers(origin)["X-Goog-PageId"] == null)
        Accounts.signOut()
        check("signed out", !Accounts.signedIn && InnerTube.authHeaders == null && changes == 3)
        check("secret cleared", store.read("accounts") == null)
    }
    // the extension's browser session through the same API
    Accounts.useBrowserSession { "SAPISIDfake/abc" }
    val bh = InnerTube.authHeaders?.invoke(origin).orEmpty()
    check("browser session hash only", bh["Authorization"] == only && "Cookie" !in bh && changes == 4) { "$bh $changes" }
    Accounts.useBrowserSession(null)
    check("browser session off", InnerTube.authHeaders == null && changes == 5)

    // ---- account/accounts_list
    val list = parse("""{"actions":[{"getMultiPageMenuAction":{"menu":{"multiPageMenuRenderer":{"sections":[{"accountSectionListRenderer":{
      "header":{"googleAccountHeaderRenderer":{"name":{"simpleText":"Fake Person"},"email":{"simpleText":"fake@example.com"}}},
      "contents":[{"accountItemSectionRenderer":{"contents":[
        {"accountItem":{"accountName":{"simpleText":"Fake Person"},"accountPhoto":{"thumbnails":[{"url":"//yt3.example/a=s48"},{"url":"https://yt3.example/a=s88"}]},
          "isSelected":true,"hasChannel":true,"channelHandle":{"simpleText":"@fake"},
          "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"accountStateToken":{"hasChannel":true,"obfuscatedGaiaId":"g1"}},{"datasyncIdToken":{"datasyncIdToken":"g1||"}}]}}}},
        {"accountItem":{"accountName":{"runs":[{"text":"Fake Brand"}]},"accountPhoto":{"thumbnails":[{"url":"//yt3.example/b"}]},
          "isSelected":false,"hasChannel":true,"accountByline":{"simpleText":"@brand"},
          "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":"p123"}},{"accountStateToken":{"obfuscatedGaiaId":"g2"}}]}}}}
      ]}}]}}]}}}}]}""")
    val ids = Accounts.parseAccountsList(list)
    check("accounts_list count", ids.size == 2) { "$ids" }
    check("accounts_list personal", ids[0].let { it.name == "Fake Person" && it.handle == "@fake" && it.email == "fake@example.com" && it.selected && it.gaiaId == "g1" && it.photo == "https://yt3.example/a=s88" && it.datasyncId == "g1||" }) { "${ids[0]}" }
    check("accounts_list brand", ids[1].let { it.name == "Fake Brand" && it.handle == "@brand" && it.pageId == "p123" && it.key == "p123" && it.photo == "https://yt3.example/b" }) { "${ids[1]}" }

    println("accountCheck: $passed passed, $failed failed")
    exitProcess(if (failed == 0) 0 else 1)
}
