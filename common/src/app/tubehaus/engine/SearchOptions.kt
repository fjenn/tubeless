package app.tubehaus.engine

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * YouTube's search filters, as in its Filters panel: what kind of result, when it was uploaded,
 * how long it is, live only, and the order. [params] writes them the way YouTube's own search
 * sends them (a small protobuf message: sort = 1, filters = 2 {upload date = 1, type = 2,
 * duration = 3, live = 8}).
 */
data class SearchOptions(
    /** 0 any, 1 videos, 2 channels, 3 playlists, 4 movies */
    val type: Int = 0,
    /** 0 any, 1 last hour, 2 today, 3 this week, 4 this month, 5 this year */
    val date: Int = 0,
    /** 0 any, 4 under 3 minutes, 5 3-20 minutes, 2 over 20 minutes (YouTube's current buckets); 1 under 4 and 3 4-20 (older) */
    val duration: Int = 0,
    /** 0 relevance, 2 upload date, 3 view count, 1 rating */
    val sort: Int = 0,
    val live: Boolean = false,
    /** more features, by their field number: 4 HD, 5 subtitles/CC, 6 Creative Commons, 7 3D, 9 purchased, 14 4K, 15 360°, 23 location, 25 HDR, 26 VR180 */
    val features: Set<Int> = emptySet(),
) {
    /** how many of the filters (other than the type) are set: shown on the Filters button */
    val count: Int get() = listOf(date != 0, duration != 0, sort != 0, live).count { it } + features.size

    @OptIn(ExperimentalEncodingApi::class)
    fun params(): String? {
        val f = ArrayList<Byte>()
        // a field's tag is a varint: numbers above 15 take two bytes
        fun field(n: Int, v: Int) {
            if (v == 0) return
            var tag = n shl 3
            while (tag >= 0x80) { f += ((tag and 0x7F) or 0x80).toByte(); tag = tag ushr 7 }
            f += tag.toByte(); f += v.toByte()
        }
        field(1, date); field(2, type); field(3, duration)
        for (n in (features + if (live) setOf(8) else emptySet()).sorted()) field(n, 1)
        val out = ArrayList<Byte>()
        if (sort != 0) { out += 0x08.toByte(); out += sort.toByte() }
        if (f.isNotEmpty()) { out += 0x12.toByte(); out += f.size.toByte(); out.addAll(f) }
        return if (out.isEmpty()) null else Base64.encode(out.toByteArray())
    }
}
