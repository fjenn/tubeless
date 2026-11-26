package app.tubeless.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A live stream's chat, and a finished stream's chat replay, read the way YouTube's own watch page
 * reads them (signed out, read only):
 *  - `next` carries a liveChatRenderer whose first continuation opens the chat (or the replay);
 *  - `live_chat/get_live_chat` gives new messages and the next continuation with how long to wait;
 *  - `live_chat/get_live_chat_replay` gives the messages around a point of the video
 *    (`currentPlayerState.playerOffsetMs`), each with its time in the video.
 */
object LiveChat {
    data class Message(
        val id: String,
        val author: String,
        val avatar: String,
        val text: String,
        /** a Super Chat's amount ("$5.00"), else empty */
        val amount: String = "",
        /** the colour YouTube gives that Super Chat (ARGB), else 0 */
        val color: Long = 0,
        /** owner, moderator, member: shown as a badge */
        val badge: String = "",
        /** the replay: where in the video it was said (ms) */
        val offsetMs: Long = 0,
        /** a system line ("Welcome to live chat!"), drawn quieter */
        val system: Boolean = false,
    )

    /** where to start: the first continuation, and whether it is a replay */
    data class Start(val token: String, val replay: Boolean)

    /** the messages of one fetch, the next continuation and how long to wait before asking again */
    data class Batch(val messages: List<Message>, val next: String?, val waitMs: Long)

    /** the chat of [videoId], or null when it has none (not a stream, or chat is off) */
    suspend fun start(videoId: String): Start? {
        val d = InnerTube.call("next", mapOf("videoId" to videoId))
        return startIn(d)
    }

    fun startIn(d: JsonElement): Start? {
        val r = d.findFirst("liveChatRenderer") ?: return null
        val replay = r.at("isReplay").bool
        val conts = r.at("continuations").arr
        // the first continuation: "Top chat" (the header's "Live chat" view token answers 400 when sent signed out)
        val first = conts.firstNotNullOfOrNull { it.at("reloadContinuationData.continuation").str }
        return first?.let { Start(it, replay) }
    }

    /** new messages of a live chat */
    suspend fun live(token: String): Batch {
        val d = InnerTube.call("live_chat/get_live_chat", mapOf("continuation" to token))
        return liveIn(d)
    }

    fun liveIn(d: JsonElement): Batch {
        val c = d.at("continuationContents.liveChatContinuation")
        val msgs = c.at("actions").arr.mapNotNull { a -> a.at("addChatItemAction.item")?.let(::message) }
        val nc = c.at("continuations").arr.firstOrNull()
        val data = nc.at("invalidationContinuationData") ?: nc.at("timedContinuationData") ?: nc.at("reloadContinuationData")
        return Batch(msgs, data.at("continuation").str, (data.at("timeoutMs").long ?: 5000L).coerceIn(1000L, 15000L))
    }

    /** a replay's messages from [offsetMs] on (YouTube sends about the next half minute) */
    suspend fun replay(token: String, offsetMs: Long): Batch {
        val d = InnerTube.call("live_chat/get_live_chat_replay", mapOf(
            "continuation" to token,
            "currentPlayerState" to buildJsonObject { put("playerOffsetMs", offsetMs.toString()) },
        ))
        return replayIn(d)
    }

    fun replayIn(d: JsonElement): Batch {
        val c = d.at("continuationContents.liveChatContinuation")
        val msgs = c.at("actions").arr.flatMap { a ->
            val r = a.at("replayChatItemAction") ?: return@flatMap emptyList()
            val off = r.at("videoOffsetTimeMsec").long ?: 0L
            r.at("actions").arr.mapNotNull { x -> x.at("addChatItemAction.item")?.let(::message)?.copy(offsetMs = off) }
        }
        val nc = c.at("continuations").arr.firstNotNullOfOrNull { it.at("liveChatReplayContinuationData.continuation").str }
        return Batch(msgs, nc, 0)
    }

    private fun runsText(e: JsonElement?): String = e.at("runs").arr.joinToString("") { r ->
        r.at("text").str ?: r.at("emoji.shortcuts.0").str ?: r.at("emoji.emojiId").str?.takeIf { it.length <= 4 } ?: ""
    }.ifEmpty { e.text ?: "" }

    private fun message(item: JsonElement): Message? {
        item.at("liveChatTextMessageRenderer")?.let { m ->
            return Message(
                id = m.at("id").str ?: return null,
                author = m.at("authorName").text ?: "",
                avatar = https(m.at("authorPhoto.thumbnails").arr.lastOrNull().at("url").str),
                text = runsText(m.at("message")),
                badge = badgeOf(m),
            )
        }
        item.at("liveChatPaidMessageRenderer")?.let { m ->
            return Message(
                id = m.at("id").str ?: return null,
                author = m.at("authorName").text ?: "",
                avatar = https(m.at("authorPhoto.thumbnails").arr.lastOrNull().at("url").str),
                text = runsText(m.at("message")),
                amount = m.at("purchaseAmountText").text ?: "",
                color = m.at("bodyBackgroundColor").long ?: 0L,
                badge = badgeOf(m),
            )
        }
        item.at("liveChatMembershipItemRenderer")?.let { m ->
            return Message(
                id = m.at("id").str ?: return null,
                author = m.at("authorName").text ?: "",
                avatar = https(m.at("authorPhoto.thumbnails").arr.lastOrNull().at("url").str),
                text = runsText(m.at("headerSubtext")).ifEmpty { runsText(m.at("message")) },
                badge = "member", color = 0xFF0F9D58,
            )
        }
        item.at("liveChatViewerEngagementMessageRenderer")?.let { m ->
            return Message(id = m.at("id").str ?: "sys", author = "", avatar = "", text = runsText(m.at("message")), system = true)
        }
        return null
    }

    private fun badgeOf(m: JsonElement): String {
        val icons = m.at("authorBadges").arr.mapNotNull { it.at("liveChatAuthorBadgeRenderer.icon.iconType").str }
        return when {
            "OWNER" in icons -> "owner"
            "MODERATOR" in icons -> "moderator"
            "VERIFIED" in icons -> "verified"
            m.at("authorBadges").arr.any { it.at("liveChatAuthorBadgeRenderer.customThumbnail") != null } -> "member"
            else -> ""
        }
    }
}
