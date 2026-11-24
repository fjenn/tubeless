package app.tubehaus.engine

import app.tubehaus.engine.Volatile

/**
 * YouTube Premium's "1080p Premium" (enhanced bitrate, itag 616), offered only when the signed-in account's own
 * cookies are Premium (PLAN §1.3 exception; everyone else gets the best regular formats).
 *
 * How (yt-dlp's premium clients): the signed-in web player with Safari's user agent ("web_safari") answers with an
 * HLS master list, and for a Premium account it names a 616 variant (VP9 1080p at a higher bitrate). Its address
 * plays as it is (HLS, no SABR, no token on the segments).
 *
 * STUB (S2, 2026-11-23): written against yt-dlp's description, not verified: there is no Premium account on this
 * machine. Nothing calls it yet; the player hook is MpvPlayer's HLS path once it is verified.
 */
object Premium {
    /** Settings → Premium bitrate (off until verified with a Premium account) */
    @Volatile var enabled = false

    private val WEB_SAFARI = Client(
        "WEB", Clients.WEB.version, 1,
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15,gzip(gfe)",
    )

    /** the address of video [id]'s itag 616 variant, or null (signed out, not Premium, not offered) */
    suspend fun hls616(id: String): String? {
        if (!enabled || InnerTube.authHeaders == null) return null
        return runCatching {
            val d = InnerTube.call("player", mapOf("videoId" to id, "contentCheckOk" to true, "racyCheckOk" to true), WEB_SAFARI)
            val master = d.at("streamingData.hlsManifestUrl").str ?: return null
            Net.get(master).lines().firstOrNull { !it.startsWith("#") && ("/itag/616/" in it || "itag=616" in it) }
        }.getOrNull()
    }
}
