package app.tubehaus.engine

/**
 * What this device's player takes (see the iOS twin in common/ios): mpv and ExoPlayer play the
 * formats' own addresses, so every stream goes as it is.
 */
internal object Playable {
    /** whether the player wants an HLS list ([ServerVideo.streamHls] rather than [ServerVideo.stream]) */
    const val hlsOnly = false

    /** [s] ready for the player, or null when it can't play it */
    fun of(s: Stream): Stream? = s
}
