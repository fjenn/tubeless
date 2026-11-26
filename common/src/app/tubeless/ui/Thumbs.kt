package app.tubeless.ui

import app.tubeless.engine.Volatile
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Size
import kotlin.math.max

/**
 * Pictures at the size they are shown (QUALITY). Every video picture in the app is asked as YouTube's
 * hqdefault/mqdefault/maxresdefault address; here it becomes the smallest one that is sharp at the box's real
 * size in pixels (screen density included): mqdefault (320 wide), hqdefault (480, a 4:3 frame with the 16:9
 * picture in it), sddefault (640), hq720 (1280); a tall box (Shorts) gets oardefault, the 1080×1920 upright
 * picture. All as WebP (a third to two thirds the bytes). When one isn't there (old or SD videos have no hq720,
 * normal videos no oardefault) the next smaller one is asked. Channel pictures are asked at the size shown
 * (=s88 → =s{px}). Coil decodes to the box's size, so memory follows what is on screen, not the file.
 */
object Thumbs {
    private val VIDEO = Regex("""^https://i\d?\.ytimg\.com/vi(?:_webp)?/([\w-]{11})/(default|mqdefault|hqdefault|sddefault|hq720|maxresdefault|oardefault)\.(?:jpg|webp)$""")
    private val AVATAR = Regex("""^(https://yt\d\.(?:ggpht|googleusercontent)\.com/[^=?]+)=s(\d+)(-[^?]*)?$""")
    /** the 16:9 picture's width in each, smallest first */
    private val LADDER = listOf("mqdefault" to 320, "hqdefault" to 480, "sddefault" to 640, "hq720" to 1280)

    /** pictures that answered 404 (an SD video's hq720): not asked again this session */
    @Volatile private var missing: Set<String> = emptySet()

    private fun url(id: String, name: String) = "https://i.ytimg.com/vi_webp/$id/$name.webp"

    /**
     * The addresses to try for [url] shown in a box of [w]×[h] pixels, best first ([url] alone when it isn't one of
     * YouTube's video pictures or the size isn't known). [weak]: a small device keeps what it asked for at most.
     */
    fun candidates(url: String, w: Int, h: Int, weak: Boolean = false): List<String> {
        if (w <= 0 || h <= 0) return listOf(url)
        AVATAR.find(url)?.let { m ->
            val px = ((max(w, h) + 7) / 8 * 8).coerceIn(48, 900)
            return listOf(m.groupValues[1] + "=s" + px + m.groupValues[3])
        }
        val m = VIDEO.find(url) ?: return listOf(url)
        val id = m.groupValues[1]
        // a 16:9 picture filling (crop) a w×h box must be this wide
        val need = max(w.toDouble(), h * 16.0 / 9.0)
        val names = LADDER.map { it.first }
        var top = LADDER.firstOrNull { it.second >= need }?.first ?: "hq720"
        if (weak) top = names[minOf(names.indexOf(top), names.indexOf("hqdefault"))]
        val chain = ArrayList<String>()
        if (h > w * 1.05 && !weak) chain += "oardefault"
        chain += names.subList(names.indexOf("hqdefault").coerceAtMost(names.indexOf(top)), names.indexOf(top) + 1).reversed()
        if (top == "mqdefault") chain += "hqdefault"
        val gone = missing
        return chain.distinct().filter { "$id/$it" !in gone }.map { url(id, it) }.ifEmpty { listOf(url(id, "hqdefault")) }
    }

    /** the picture interceptor for Coil (App.kt) */
    class Sized(private val weak: Boolean) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val data = chain.request.data as? String ?: return chain.proceed()
            val (w, h) = px(chain.size)
            val list = candidates(data, w, h, weak)
            if (list.size == 1 && list[0] == data) return chain.proceed()
            var last: ImageResult? = null
            for ((i, u) in list.withIndex()) {
                val r = chain.withRequest(chain.request.newBuilder().data(u).build()).proceed()
                if (r !is ErrorResult) return r
                last = r
                // a 404 means that size isn't made for this video; anything else (offline) won't get better further down
                val notThere = r.throwable.message?.contains("404") == true
                if (!notThere || i == list.lastIndex) break
                VIDEO.find(u)?.let { mm -> missing = missing + (mm.groupValues[1] + "/" + mm.groupValues[2]) }
            }
            return last ?: chain.proceed()
        }

        private fun px(s: Size): Pair<Int, Int> {
            val w = (s.width as? Dimension.Pixels)?.px ?: 0
            val h = (s.height as? Dimension.Pixels)?.px ?: 0
            return w to h
        }
    }
}
