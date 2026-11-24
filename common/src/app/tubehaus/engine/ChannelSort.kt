package app.tubehaus.engine

/**
 * The sort chips over a channel's Videos, Shorts and Live tabs (Latest / Popular / Oldest). Each chip
 * is a continuation token: [page] asks for the tab sorted that way.
 */
object ChannelSort {
    data class Chip(val title: String, val token: String, val selected: Boolean)

    /** the chips the tab opened with [params] offers (empty when it has none) */
    suspend fun chips(channelId: String, params: String): List<Chip> {
        val d = InnerTube.call("browse", mapOf("browseId" to channelId, "params" to urlDecode(params)))
        return chipsIn(d)
    }

    fun chipsIn(d: kotlinx.serialization.json.JsonElement): List<Chip> {
        // the newer view model, then the older renderer
        val vm = d.findAll("chipViewModel").mapNotNull { c ->
            val t = c.at("text").str ?: return@mapNotNull null
            // the chip in force comes without a command (e.g. "Latest" beside "Members only" / "Public"): "" = the tab as it opened
            val tok = c.findFirst("continuationCommand").at("token").str ?: return@mapNotNull Chip(t, "", true)
            Chip(t, tok, c.at("selected").bool)
        }
        if (vm.isNotEmpty()) return vm
        return d.findAll("chipCloudChipRenderer").mapNotNull { c ->
            val t = c.at("text").text ?: return@mapNotNull null
            val tok = c.findFirst("continuationCommand").at("token").str ?: return@mapNotNull null
            Chip(t, tok, c.at("isSelected").bool)
        }
    }

    /** the tab's videos in that order; more come from YouTube.browseMore with the page's token */
    suspend fun page(token: String): Page<Item> {
        val d = InnerTube.call("browse", mapOf("continuation" to token))
        val root = d.at("onResponseReceivedActions") ?: d.at("onResponseReceivedEndpoints") ?: d
        return Page(parseItems(root), continuationOf(root))
    }
}
