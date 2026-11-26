package app.tubeless.engine

import kotlinx.serialization.json.JsonElement

/** One entry of the bell's inbox. */
data class Notice(
    val id: String,
    val text: String,
    val sent: String,
    /** the channel's picture */
    val avatar: String,
    /** the video's picture, when it is about a video */
    val thumb: String,
    val videoId: String?,
    val channelId: String?,
    val read: Boolean,
    /** "Important" or "More notifications" (empty when YouTube doesn't split them) */
    val section: String = "",
    /** about a Short (YouTube links it to the Shorts player) */
    val short: Boolean = false,
)

data class NoticePage(val items: List<Notice>, val next: String? = null, val loggedOut: Boolean = false)

/**
 * The account's notifications (the bell): `notification/get_notification_menu` for the inbox and its
 * further pages, `notification/get_unseen_count` for the badge. Signed out both answer null. The panel
 * that shows them is S7's (ui/NotificationsPanel); new-upload alerts without an account are local (S7).
 */
object Notifications {
    suspend fun inbox(): NoticePage? {
        if (!YouTubeAccount.signedIn) return null
        return parse(InnerTube.call("notification/get_notification_menu", mapOf("notificationsMenuRequestType" to "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX")))
    }

    suspend fun more(token: String): NoticePage? {
        if (!YouTubeAccount.signedIn) return null
        return parse(InnerTube.call("notification/get_notification_menu", mapOf("ctoken" to token)))
    }

    /** the number on the bell; null signed out or unknown */
    suspend fun unseen(): Int? {
        if (!YouTubeAccount.signedIn) return null
        return try { unseenOf(InnerTube.call("notification/get_unseen_count", emptyMap())) } catch (e: Exception) { null }
    }

    fun unseenOf(d: JsonElement?): Int? = d.at("unseenCount").int
        ?: d?.findAll("updateNotificationsUnseenCountAction", emptySet())?.firstOrNull().at("unseenCount").int

    fun parse(d: JsonElement?): NoticePage {
        if (d == null) return NoticePage(emptyList())
        val out = ArrayList<Notice>()
        // sections ("Important", "More notifications") or one plain list; continuations come as appendContinuationItemsAction
        val sections = d.findAll("multiPageMenuNotificationSectionRenderer", emptySet())
        if (sections.isNotEmpty()) for (s in sections) {
            val title = s.at("sectionTitle").text ?: ""
            for (r in s.findAll("notificationRenderer", emptySet())) notice(r, title)?.let { out += it }
        } else for (r in d.findAll("notificationRenderer", emptySet())) notice(r, "")?.let { out += it }
        val next = d.findAll("continuationItemRenderer", emptySet()).firstNotNullOfOrNull { it.findFirst("token", emptySet()).str }
            ?: d.findAll("getNotificationMenuEndpoint", emptySet()).firstNotNullOfOrNull { it.at("ctoken").str }
        return NoticePage(out.distinctBy { it.id }, next, AccountParse.loggedOut(d))
    }

    private fun notice(r: JsonElement, section: String): Notice? {
        val nav = r.at("navigationEndpoint")
        val id = r.at("notificationId").str ?: nav?.findFirst("videoId", emptySet()).str ?: return null
        return Notice(
            id = id,
            text = r.at("shortMessage").text ?: "",
            sent = r.at("sentTimeText").text ?: "",
            avatar = bestImage(r.at("thumbnail")),
            thumb = bestImage(r.at("videoThumbnail")),
            // uploads of Shorts link to the Shorts player (reelWatchEndpoint), the rest to the watch page
            videoId = nav.at("watchEndpoint.videoId").str ?: nav.at("reelWatchEndpoint.videoId").str,
            channelId = nav.at("browseEndpoint.browseId").str?.takeIf { it.startsWith("UC") },
            read = r.at("read").bool,
            section = section,
            short = nav.at("reelWatchEndpoint") != null,
        )
    }
}
