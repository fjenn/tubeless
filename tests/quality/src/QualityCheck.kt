package app.tubeless.quality

import app.tubeless.engine.Format
import app.tubeless.engine.Quality
import app.tubeless.ui.Thumbs

/** QUALITY_CHECK=1: the pure parts (screen fit, format pick, picture sizes) against expected answers */
fun check(): Int {
    var bad = 0
    fun eq(name: String, got: Any?, want: Any?) { val ok = got == want; if (!ok) bad++; println("${if (ok) "ok  " else "FAIL"} $name: $got${if (ok) "" else " (want $want)"}") }
    val wide = listOf(256 to 144, 426 to 240, 640 to 360, 854 to 480, 1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
    eq("fit MacBook Pro 3456x2234", Quality.fit(3456, 2234, wide), 2160)
    eq("fit 1080p monitor", Quality.fit(1920, 1080, wide), 1080)
    eq("fit MacBook Air 2560x1664", Quality.fit(2560, 1664, wide), 1440)
    eq("fit iPhone 1206x2622 (portrait)", Quality.fit(1206, 2622, wide), 1440)
    eq("fit 1366x768 laptop", Quality.fit(1366, 768, wide), 720)
    eq("fit no screen", Quality.fit(0, 0, wide), 2160)
    val shorts = listOf(1080 to 1920, 720 to 1280)
    eq("fit Short on iPhone", Quality.fit(1206, 2622, shorts), 1920)
    fun f(itag: Int, c: String, h: Int, fps: Int, br: Int, len: Long) = Format(itag, "", "video/x", c, height = h, width = h * 16 / 9, fps = fps, bitrate = br, contentLength = len)
    val norway = listOf(f(313, "vp9", 2160, 30, 17951000, 77864), f(401, "av01.0.12M.08", 2160, 30, 17336000, 47125),
        f(137, "avc1.640028", 1080, 30, 5509000, 10473), f(248, "vp9", 1080, 30, 3530000, 6428), f(399, "av01.0.08M.08", 1080, 30, 3315000, 5305),
        f(337, "vp9.2", 2160, 30, 30000000, 99999))
    eq("best 1080 (fullest stream)", Quality.best(norway, 1080, lengthSec = 100)?.itag, 137)
    eq("best 2160 SDR only", Quality.best(norway, 2160, lengthSec = 100, sdrOnly = true)?.itag, 313)
    eq("best 2160 AV1 only hardware", Quality.best(norway, 2160, hardware = setOf("h264", "av1"), lengthSec = 100, sdrOnly = true)?.itag, 401)
    eq("best codec picked in Settings", Quality.best(norway, 1080, prefer = "vp9", lengthSec = 100)?.itag, 248)
    eq("hdr av1 PQ", Quality.hdr(f(701, "av01.0.13M.10.0.110.09.16.09.0", 2160, 60, 1, 1)), true)
    eq("hdr av1 SDR 10-bit", Quality.hdr(f(1, "av01.0.13M.10.0.110.01.01.01.0", 2160, 60, 1, 1)), false)
    val u = "https://i.ytimg.com/vi/KLuTLF3x9sA/hqdefault.jpg"
    eq("thumb desktop card 720x405", Thumbs.candidates(u, 720, 405).first().substringAfterLast('/'), "hq720.webp")
    eq("thumb row 336x189", Thumbs.candidates(u, 336, 189).first().substringAfterLast('/'), "hq720.webp")
    eq("thumb tiny 200x112", Thumbs.candidates(u, 200, 112).first().substringAfterLast('/'), "mqdefault.webp")
    eq("thumb Shorts 1206x2144", Thumbs.candidates(u, 1206, 2144).map { it.substringAfterLast('/') }, listOf("oardefault.webp", "hq720.webp", "sddefault.webp", "hqdefault.webp"))
    eq("thumb weak TV", Thumbs.candidates(u, 720, 405, weak = true).first().substringAfterLast('/'), "hqdefault.webp")
    eq("thumb unknown size", Thumbs.candidates(u, 0, 0).first().substringAfterLast('/'), "hq720.webp")
    eq("avatar 80px", Thumbs.candidates("https://yt3.ggpht.com/abc=s88-c-k-c0x00ffffff-no-rj", 80, 80), listOf("https://yt3.ggpht.com/abc=s80-c-k-c0x00ffffff-no-rj"))
    eq("other url untouched", Thumbs.candidates("https://example.com/a.jpg", 100, 100), listOf("https://example.com/a.jpg"))
    return bad
}
