package com.backlogbattlers.app.notifications

import com.backlogbattlers.app.domain.model.UserSettings

// the notification channels, which the user can also turn off in the phone's own settings.
// one per Settings section the pushes belong to
enum class PushChannel(val id: String) {
    FRIENDS("friend_activity"),
    COMPETITION("competition"),
    ACHIEVEMENTS("achievements"),
}

// the friends screen tab a push can open on, passed as the friendsFragment "openTab" argument
const val OPEN_TAB_REQUESTS = "REQUESTS"

//------------------------------
// the kinds of push the API sends (names match the API's PushType), with the Settings switch that
// turns each off, its channel, and the screen tapping it opens
enum class PushType(
    val channel: PushChannel,
    // the friendsFragment tab to open on, for the pushes that open the friends screen
    val friendsTab: String? = null,
    // the pushes about a friend say who; the ones about you alone (your achievements, a new season) do not
    val hasActor: Boolean = true,
    // true for a push about something the player does themselves in the app (an import's unlocks),
    // which they are looking at while it happens: it is only shown when the app is not open. the
    // ones caused by someone else, or by the calendar (a friend request, being passed, a new season)
    // are worth showing even while the app is open
    val hiddenWhileAppOpen: Boolean = false,
) {
    FRIEND_REQUEST_RECEIVED(PushChannel.FRIENDS, friendsTab = OPEN_TAB_REQUESTS),
    FRIEND_REQUEST_ACCEPTED(PushChannel.FRIENDS),
    FRIEND_COMPLETED_GAME(PushChannel.FRIENDS),
    OVERTAKEN(PushChannel.COMPETITION),
    ACHIEVEMENT_UNLOCKED(PushChannel.ACHIEVEMENTS, hasActor = false, hiddenWhileAppOpen = true),
    SEASON_RESET(PushChannel.COMPETITION, hasActor = false),
    ;

    //------------------------------
    // whether the matching switch under Settings > Notifications is on
    fun isWanted(settings: UserSettings): Boolean = when (this) {
        FRIEND_REQUEST_RECEIVED, FRIEND_REQUEST_ACCEPTED, FRIEND_COMPLETED_GAME -> settings.friendActivityNotificationsEnabled
        OVERTAKEN -> settings.rankChangeNotificationsEnabled
        ACHIEVEMENT_UNLOCKED -> settings.achievementNotificationsEnabled
        SEASON_RESET -> settings.seasonResetNotificationsEnabled
    }

    companion object {
        fun fromName(name: String?): PushType? = entries.firstOrNull { it.name == name }
    }
}

//------------------------------
// one push as the API sends it: a data message, so the app always builds the notification itself
data class Push(
    val type: PushType,
    // the account the push was sent to
    val recipientId: String,
    // the friend who sent, accepted, completed or overtook. empty for the pushes about you alone
    val actorName: String = "",
    // the game, for a friend's completion and for unlocked achievements (null when they are spread over several games)
    val gameTitle: String? = null,
    // for unlocked achievements: how many, and the rarest one's name when it is known
    val count: Int? = null,
    val achievementName: String? = null,
    // for a new season: how the user finished the month before. no rank means they scored nothing
    val lastRank: Int? = null,
    val lastPoints: Int? = null,
) {

    //------------------------------
    // shown only to the account it was sent to (not after it signed out, or to whoever signed in
    // next), and only while its switch is on. the API checks the switch too; this covers a change
    // the API hasn't been told about yet, e.g. one made offline. [appOpen] is whether the app is on
    // screen: a push about the player's own action is held back then (see PushType.hiddenWhileAppOpen)
    fun shouldShow(signedInUserId: String?, settings: UserSettings, appOpen: Boolean = false): Boolean =
        signedInUserId != null && signedInUserId == recipientId && type.isWanted(settings) &&
            !(appOpen && type.hiddenWhileAppOpen)

    companion object {
        //------------------------------
        // reads a push from an FCM data payload, or null for one this version of the app doesn't know
        fun from(data: Map<String, String>): Push? {
            val type = PushType.fromName(data["type"]) ?: return null
            val recipientId = data["userId"]?.takeIf { it.isNotBlank() } ?: return null
            val actorName = data["actorName"]?.takeIf { it.isNotBlank() }
            if (type.hasActor && actorName == null) return null
            val count = data["count"]?.toIntOrNull()
            // a push about achievements that does not say how many is not worth showing
            if (type == PushType.ACHIEVEMENT_UNLOCKED && (count == null || count < 1)) return null
            return Push(
                type = type,
                recipientId = recipientId,
                actorName = actorName.orEmpty(),
                gameTitle = data["gameTitle"]?.takeIf { it.isNotBlank() },
                count = count,
                achievementName = data["achievementName"]?.takeIf { it.isNotBlank() },
                lastRank = data["lastRank"]?.toIntOrNull(),
                lastPoints = data["lastPoints"]?.toIntOrNull(),
            )
        }
    }
}
//------------------------------EOF------------------------------\\
