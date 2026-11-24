package app.tubehaus.engine

/** What the browser's player takes: everything (Shaka plays DASH, HLS and the SABR formats). Twins: common/jvm, common/ios. */
internal object Playable {
    const val hlsOnly = false
    fun of(s: Stream): Stream? = s
}
