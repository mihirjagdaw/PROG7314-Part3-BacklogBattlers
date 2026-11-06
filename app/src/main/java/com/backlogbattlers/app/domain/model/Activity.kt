package com.backlogbattlers.app.domain.model

// what one line of the activity feed is about. UNKNOWN is a kind the backend added after this
// version of the app, which the feed leaves out instead of failing on
enum class ActivityType { GAME_COMPLETED, ACHIEVEMENT_UNLOCKED, FRIEND_ADDED, RANK_PASSED, UNKNOWN }

// one thing that happened, by the signed in user or one of their friends. which fields are set
// depends on the type:
//  - GAME_COMPLETED: gameTitle, points, and detail = the completion type (COMPLETIONIST is 100%)
//  - ACHIEVEMENT_UNLOCKED: gameTitle, count, and detail = the name of the rarest one when known
//  - FRIEND_ADDED: the actor and the subject are now friends
//  - RANK_PASSED: the actor passed the subject, the closest of the count people they passed
data class ActivityEvent(
    val eventId: String,
    val type: ActivityType,
    val actorId: String,
    val actorName: String,
    val actorAvatarUrl: String?,
    // the picture the actor picked, by the app's id
    val actorPictureId: String? = null,
    val gameId: Int?,
    val gameTitle: String?,
    val subjectId: String?,
    val subjectName: String?,
    val count: Int,
    val points: Int?,
    val detail: String?,
    // epoch milliseconds
    val createdAt: Long,
)

// one page of the feed, newest first. nextBefore is what to ask for the following page with, null on the last
data class ActivityPage(val events: List<ActivityEvent>, val nextBefore: Long?)

// the newest page as it was last saved for offline, and when the server answered with it
data class CachedActivity(val page: ActivityPage, val savedAt: Long)
//------------------------------EOF------------------------------\\
