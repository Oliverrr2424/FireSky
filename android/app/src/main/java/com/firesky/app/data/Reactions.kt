package com.firesky.app.data

import org.json.JSONObject

/** What this device remembers about the person's reaction to one forecast event. */
data class EventReaction(
    val sentiment: String? = null,
    val reason: String? = null,
    /** False while the reaction is still waiting to reach the server (offline, or not signed in). */
    val synced: Boolean = true,
)

/** The server's view of one forecast event: how many people reacted nearby, and this account's own reaction. */
data class ReactionSummary(
    val like: Int,
    val dislike: Int,
    val mineSentiment: String?,
    val mineReason: String?,
)

val REACTION_REASONS = listOf("too_high", "too_low", "wrong_time", "other")

fun reactionFor(store: JSONObject, key: String): EventReaction {
    // Reactions saved before sunrise and sunset were rated separately are keyed by day alone and meant the sunset.
    val entry = store.optJSONObject(key)
        ?: store.optJSONObject(key.substringBefore(':')).takeIf { key.endsWith(":sunset") }
        ?: return EventReaction()
    return EventReaction(
        sentiment = entry.optNullableString("sentiment"),
        reason = entry.optNullableString("reason"),
        synced = entry.optBoolean("synced", true),
    )
}

/**
 * Community counts as the person should see them: the server's totals, adjusted so that a reaction
 * made a moment ago (or still waiting to sync) already counts. Null until the server has answered.
 */
fun displayedReactionCounts(summary: ReactionSummary?, own: EventReaction): Pair<Int, Int>? {
    summary ?: return null
    var like = summary.like
    var dislike = summary.dislike
    if (summary.mineSentiment != own.sentiment) {
        when (summary.mineSentiment) {
            "like" -> like--
            "dislike" -> dislike--
        }
        when (own.sentiment) {
            "like" -> like++
            "dislike" -> dislike++
        }
    }
    return like.coerceAtLeast(0) to dislike.coerceAtLeast(0)
}
