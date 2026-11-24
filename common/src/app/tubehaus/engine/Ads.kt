package app.tubehaus.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The ads YouTube mixes into its lists, found and left out ("no ads" is this app's first promise).
 *
 * - Shorts (reel/reel_item_watch, reel/reel_watch_sequence): an ad is an ordinary-looking entry whose
 *   reelWatchEndpoint carries `adClientParams: {isAd: true}` (a few in every hundred Shorts, signed out
 *   too; anonymous captures 2026-11-24, tests/golden/fixtures/reelSequence/web-ads.json). The same field
 *   uBlock Origin, Control Panel for YouTube and the TV clients (TizenTube, VacuumTube) filter on. Such
 *   an entry has no overlay, and its prefetched player answer carries `adSlots`: the second sign is used
 *   when the first is ever renamed.
 * - Lists (Home, search, related, channel rows): ads come in renderers of their own (adSlotRenderer,
 *   inFeedAdLayoutRenderer, promoted..., searchPyvRenderer...) or as a lockup with ad metadata or an
 *   "Ad"/"Sponsored" badge. [RENDERERS] are never walked into; [item] says when one item is an ad.
 *
 * Every ad's video id is remembered ([isAd]): the player refuses them, wherever they came from.
 */
object Ads {
    /** branches that only ever hold an ad */
    val RENDERERS = setOf(
        "adSlotRenderer", "inFeedAdLayoutRenderer", "promotedVideoRenderer", "compactPromotedVideoRenderer",
        "compactPromotedItemRenderer", "promotedSparklesWebRenderer", "promotedSparklesTextSearchRenderer",
        "searchPyvRenderer", "displayAdRenderer", "actionCompanionAdRenderer", "carouselAdRenderer",
        "bannerPromoRenderer", "statementBannerRenderer", "brandVideoShelfRenderer", "brandVideoSingletonRenderer",
        "videoMastheadAdV3Renderer", "adBreakServiceRenderer", "playerLegacyDesktopWatchAdsRenderer",
        "reelPlayerAdRenderer", "shortsAdRenderer", "adPlacements", "playerAds", "adSlots",
    )

    /** keys inside an item that only an ad has */
    private val MARKS = setOf(
        "feedAdMetadataViewModel", "adBadgeViewModel", "adSlotMetadata", "adLayoutMetadata", "adClientParams",
        "aboutThisAdRenderer", "adButtonViewModel", "adDetailsLineViewModel",
    )

    @Volatile private var ids: Set<String> = emptySet()

    /** a video YouTube handed us as an ad: never shown, never played */
    fun isAd(id: String): Boolean = id in ids

    /** how many ad videos were seen and left out (the engine check) */
    val count: Int get() = ids.size

    /** forgets the ids (tests) */
    internal fun clear() { ids = emptySet() }

    fun mark(id: String) { if (id.isNotEmpty() && id !in ids) ids = ids + id }

    /** whether a reelWatchEndpoint (a Short in YouTube's sequence) is an ad; its id is remembered when it is */
    fun reel(r: JsonElement?): Boolean {
        if (r == null) return false
        val p = r.at("adClientParams")
        val pr = r.at("unserializedPrefetchData.playerResponse")
        val ad = p != null ||
            // the second sign: no overlay (likes, channel, comments) and an ad's player answer
            (r.at("overlay") == null && pr != null && (pr.at("adSlots") != null || pr.at("adPlacements") != null || pr.at("playerAds") != null))
        if (ad) r.at("videoId").str?.let(::mark)
        return ad
    }

    /** whether one list item ([v], the renderer's body) is an ad; its id is remembered when it is */
    fun item(v: JsonElement, id: String?): Boolean {
        val ad = hasAny(v, MARKS) ||
            v.findAll("style").any { it.str == "BADGE_STYLE_TYPE_AD" } ||
            v.findAll("url").any { (it.str ?: "").let { u -> "googleadservices.com" in u || "/aclk?" in u || "doubleclick.net" in u } }
        if (ad && id != null) mark(id)
        return ad
    }

    /** an ad renderer's videos are remembered (the player must never play them) */
    fun markAll(e: JsonElement) {
        for (k in listOf("videoId", "contentId")) for (x in e.findAll(k, emptySet())) x.str?.takeIf { it.length == 11 }?.let(::mark)
    }

    private fun hasAny(e: JsonElement, keys: Set<String>): Boolean = when (e) {
        is JsonObject -> e.any { (k, v) -> k in keys || hasAny(v, keys) }
        is JsonArray -> e.any { hasAny(it, keys) }
        else -> false
    }
}
