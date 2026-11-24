package app.tubehaus.engine

import okio.ByteString.Companion.toByteString

/**
 * The Shorts sequence exactly as youtube.com/shorts asks for it, in the one YouTube session (signed in: the
 * account's; signed out: the remembered visitor's), so the order is YouTube's own for this viewer:
 *  - no seed (the Shorts tab): `reel/reel_item_watch` with REEL_WATCH_INPUT_TYPE_SEEDLESS answers the first
 *    Short (replacementEndpoint) and the sequence's start (sequenceContinuation);
 *  - a seed (a Short opened from a shelf): the sequence starts from it (sequenceParams {3: {1: id, 3: 0, 4: 1}},
 *    the shape of YouTube's own);
 *  - then `reel/reel_watch_sequence` page by page (continuationEndpoint's token).
 * Each entry keeps its reel playerParams: the watch-time pings are asked with them (el=shortspage).
 */
class ReelSequence {
    data class Reel(val video: Video, val playerParams: String?)

    private var next: String? = null
    var started = false
        private set
    val ended: Boolean get() = started && next == null

    /** the first page: [seed] first when given, else YouTube's own first Short */
    suspend fun start(seed: String?): List<Reel> {
        started = true
        if (seed != null) {
            next = seedParams(seed)
            return more()
        }
        val d = InnerTube.call("reel/reel_item_watch", mapOf(
            "playerRequest" to kotlinx.serialization.json.buildJsonObject { put("videoId", kotlinx.serialization.json.JsonPrimitive("")) },
            "params" to "CA8%3D",
            "inputType" to "REEL_WATCH_INPUT_TYPE_SEEDLESS",
            "disablePlayerResponse" to true,
        ))
        next = d.at("sequenceContinuation").str
        val first = d.at("replacementEndpoint.reelWatchEndpoint")?.takeIf { !Ads.reel(it) }?.let(::reel)
        return listOfNotNull(first) + more()
    }

    /** the next page of the sequence; empty at its end */
    suspend fun more(): List<Reel> {
        val token = next ?: return emptyList()
        val d = InnerTube.call("reel/reel_watch_sequence", mapOf("sequenceParams" to token))
        next = d.at("continuationEndpoint.continuationCommand.token").str?.takeIf { it != token }
        return parse(d)
    }

    companion object {
        /** the Shorts of one page, without the ads YouTube puts among them (Ads.reel) */
        fun parse(d: kotlinx.serialization.json.JsonElement?): List<Reel> =
            d.at("entries").arr.mapNotNull { e ->
                val r = e.at("command.reelWatchEndpoint") ?: return@mapNotNull null
                if (Ads.reel(r)) null else reel(r)
            }.filter { !Ads.isAd(it.video.id) }

        private fun reel(r: kotlinx.serialization.json.JsonElement): Reel? {
            val id = r.at("videoId").str?.takeIf { it.length == 11 } ?: return null
            val vd = r.at("unserializedPrefetchData.playerResponse.videoDetails")
            return Reel(
                Video(
                    id, vd.at("title").str ?: "", author = vd.at("author").str ?: "", channelId = vd.at("channelId").str ?: "",
                    lengthSec = vd.at("lengthSeconds").str?.toIntOrNull() ?: 0, short = true,
                ),
                r.at("playerParams").str,
            )
        }

        /** a sequence that starts after [videoId] (protobuf {3: {1: id, 3: 0, 4: 1}}, base64url, URL-encoded) */
        fun seedParams(videoId: String): String {
            val id = videoId.encodeToByteArray()
            val inner = byteArrayOf(0x0A, id.size.toByte()) + id + byteArrayOf(0x18, 0x00, 0x20, 0x01)
            val outer = byteArrayOf(0x1A, inner.size.toByte()) + inner
            return urlEncode(outer.toByteString().base64Url())
        }
    }
}
