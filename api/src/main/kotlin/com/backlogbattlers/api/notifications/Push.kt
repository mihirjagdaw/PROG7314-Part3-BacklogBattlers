package com.backlogbattlers.api.notifications

import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.settings.SettingsDto
import com.backlogbattlers.api.settings.settingsFor
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * The kinds of push the API sends, each with the Settings switch that turns it off. The app
 * shows them in the user's own language from these names, so a name is never renamed, only added.
 */
enum class PushType(val isWanted: (SettingsDto) -> Boolean) {
    /** Someone sent you a friend request. */
    FRIEND_REQUEST_RECEIVED({ it.friendActivityNotifications }),

    /** Someone accepted the friend request you sent. */
    FRIEND_REQUEST_ACCEPTED({ it.friendActivityNotifications }),

    /** A friend marked a game complete. */
    FRIEND_COMPLETED_GAME({ it.friendActivityNotifications }),

    /** Someone passed you on this month's leaderboard. */
    OVERTAKEN({ it.rankChangeNotifications }),

    /** You unlocked achievements, which a Steam import found. Behind the Achievements switch. */
    ACHIEVEMENT_UNLOCKED({ it.achievementNotifications }),

    /** A new month began, so the leaderboard started again. Behind the Season resets switch. */
    SEASON_RESET({ it.seasonResetNotifications }),
}

/**
 * One push to one user. Sent as an FCM data message (no notification block), so the app always
 * builds the notification itself: in the user's language, on the right channel, and opening the
 * matching screen when tapped.
 */
data class Push(
    val type: PushType,
    /** Who did it: the friend who sent, accepted, completed or overtook. Empty for the pushes about you alone. */
    val actorName: String = "",
    /** The game, for [PushType.FRIEND_COMPLETED_GAME] and [PushType.ACHIEVEMENT_UNLOCKED] (left out when it is several games). */
    val gameTitle: String? = null,
    /** The rarest of the unlocked achievements, for [PushType.ACHIEVEMENT_UNLOCKED]. */
    val achievementName: String? = null,
    /** How many achievements were unlocked, for [PushType.ACHIEVEMENT_UNLOCKED]. */
    val count: Int? = null,
    /** The month that has just started, as 2026-12, for [PushType.SEASON_RESET]. */
    val monthPeriod: String? = null,
    /** How the user finished the month before, for [PushType.SEASON_RESET]: their rank (null when they scored nothing) and points. */
    val lastRank: Int? = null,
    val lastPoints: Int? = null,
) {
    /** The data payload. [recipient] lets the app drop a push meant for an account that has since signed out. */
    fun data(recipient: Uuid): Map<String, String> = buildMap {
        put("type", type.name)
        put("userId", recipient.toString())
        if (actorName.isNotBlank()) put("actorName", actorName)
        if (gameTitle != null) put("gameTitle", gameTitle)
        if (achievementName != null) put("achievementName", achievementName)
        if (count != null) put("count", count.toString())
        if (monthPeriod != null) put("monthPeriod", monthPeriod)
        if (lastRank != null) put("lastRank", lastRank.toString())
        if (lastPoints != null) put("lastPoints", lastPoints.toString())
    }
}

/**
 * A made-up push of [type], for the debug route that lets a tester see each kind arrive on their
 * own phone. The names and numbers are invented, so it never reveals anyone's data.
 */
fun samplePush(type: PushType): Push = when (type) {
    PushType.FRIEND_REQUEST_RECEIVED -> Push(type, actorName = "Alex")
    PushType.FRIEND_REQUEST_ACCEPTED -> Push(type, actorName = "Alex")
    PushType.FRIEND_COMPLETED_GAME -> Push(type, actorName = "Alex", gameTitle = "Hades")
    PushType.OVERTAKEN -> Push(type, actorName = "Alex")
    PushType.ACHIEVEMENT_UNLOCKED -> Push(type, gameTitle = "Hades", achievementName = "Escaped the Underworld", count = 3)
    PushType.SEASON_RESET -> Push(type, monthPeriod = "2026-12", lastRank = 2, lastPoints = 1240)
}

/**
 * Whether a user with these preferences wants [type]. [settings] is null until the app first
 * saves them; the app ships with every switch on, so no row means yes. [pushEnabled] is the
 * account-wide switch on Users, which turns everything off.
 */
fun allowsPush(settings: SettingsDto?, pushEnabled: Boolean, type: PushType): Boolean =
    pushEnabled && (settings == null || type.isWanted(settings))

/** [allowsPush] for a stored user. False when the user no longer exists. */
fun wantsPush(userId: Uuid, type: PushType): Boolean {
    val pushEnabled = transaction {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.pushNotificationsEnabled)
    } ?: return false
    return allowsPush(settingsFor(userId), pushEnabled, type)
}

/**
 * Who [mover] just passed by going from [pointsBefore] to [pointsAfter], given everyone's points
 * this month in [standings]. Someone level with or ahead of the mover before, and behind them
 * now, was overtaken. Nobody is overtaken when the mover's points did not go up.
 */
fun overtaken(mover: Uuid, pointsBefore: Int, pointsAfter: Int, standings: Map<Uuid, Int>): List<Uuid> {
    if (pointsAfter <= pointsBefore) return emptyList()
    return standings
        .filter { (userId, points) -> userId != mover && points > 0 && points >= pointsBefore && points < pointsAfter }
        .keys.toList()
}
//------------------------------EOF------------------------------\\
