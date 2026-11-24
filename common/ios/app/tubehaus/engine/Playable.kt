package app.tubehaus.engine

/**
 * What the iPhone's player takes: AVPlayer plays HLS only. A stream with its own list goes as it is;
 * one without (the token-free clients' plain formats, YouTube Kids, the TV client, SABR) gets a list
 * made here by [Sabr.withHls], served from 127.0.0.1. The JVM twin is in common/jvm.
 */
internal object Playable {
    const val hlsOnly = true

    /** [s] with an HLS list, or null when it has nothing AVPlayer plays (no H.264 or AAC) */
    fun of(s: Stream): Stream? = if (s.hls != null) s else Sabr.withHls(s).takeIf { it.hls != null }
}
