package com.backlogbattlers.api.scoring

import com.backlogbattlers.api.db.MonthlyLeaderboardEntries
import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.friends.friendIdsOf
import com.backlogbattlers.api.profile.pictureIdsFor
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val DEFAULT_LIMIT = 20
const val MAX_LEADERBOARD_LIMIT = 100

/** One row of the leaderboard. Equal points share a rank (1, 2, 2, 4), never a tie-break by name. */
@Serializable
data class LeaderboardEntryDto(
    val rank: Int,
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val monthlyPoints: Int,
    /** The picture they picked, by the app's id. */
    val pictureId: String? = null,
)

@Serializable
data class LeaderboardResponseDto(
    val monthPeriod: String,
    /** Epoch milliseconds; when this competition resets and a fresh one begins. */
    val seasonEndsAt: Long,
    val entries: List<LeaderboardEntryDto>,
    /** The signed-in caller's own row and rank, present even when it falls outside [entries]. Null when nobody is signed in. */
    val me: LeaderboardEntryDto?,
)

/** A user's points total for [monthPeriod] (the current month by default), or 0 if they have none. */
fun monthlyPointsFor(userId: Uuid, monthPeriod: String = currentMonthPeriod()): Int = transaction {
    MonthlyLeaderboardEntries.selectAll()
        .where { (MonthlyLeaderboardEntries.userId eq userId) and (MonthlyLeaderboardEntries.monthPeriod eq monthPeriod) }
        .singleOrNull()?.get(MonthlyLeaderboardEntries.monthlyPoints) ?: 0
}

/**
 * This month's standings between [caller] and their friends: the top [limit] of them, plus
 * [caller]'s own row and rank so the app can always pin it. Friends who have not scored this
 * month are still listed, at 0 points, sharing the last rank. Equal points are listed by name.
 *
 * The board is friends-only, so with nobody signed in ([caller] null) it is empty.
 */
fun monthlyLeaderboard(caller: Uuid?, monthPeriod: String = currentMonthPeriod(), limit: Int = DEFAULT_LIMIT): LeaderboardResponseDto = transaction {
    val seasonEndsAt = seasonEndsAt(monthPeriod).toEpochMilli()
    if (caller == null) return@transaction LeaderboardResponseDto(monthPeriod, seasonEndsAt, emptyList(), me = null)

    val players = (listOf(caller) + friendIdsOf(caller)).distinct()
    val points = MonthlyLeaderboardEntries.selectAll()
        .where { (MonthlyLeaderboardEntries.monthPeriod eq monthPeriod) and (MonthlyLeaderboardEntries.userId inList players) }
        .associate { it[MonthlyLeaderboardEntries.userId] to it[MonthlyLeaderboardEntries.monthlyPoints] }
    val users = Users.selectAll().where { Users.id inList players }.toList()
    val pictures = pictureIdsFor(players)

    // highest first; a tie (including everyone still on 0) is listed by name so the order never jumps around
    val sorted = users.sortedWith(
        compareByDescending<ResultRow> { points[it[Users.id]] ?: 0 }
            .thenBy { it[Users.displayName].lowercase() }
            .thenBy { it[Users.id].toString() },
    )
    val ranks = competitionRanks(sorted.map { points[it[Users.id]] ?: 0 })

    val ranked = sorted.mapIndexed { index, user ->
        val id = user[Users.id]
        LeaderboardEntryDto(
            rank = ranks[index],
            userId = id.toString(),
            displayName = user[Users.displayName],
            avatarUrl = user[Users.avatarUrl],
            monthlyPoints = points[id] ?: 0,
            pictureId = pictures[id],
        )
    }

    val me = ranked.find { it.userId == caller.toString() }
    LeaderboardResponseDto(monthPeriod, seasonEndsAt, ranked.take(limit), me)
}

/**
 * Standard competition ranking ("1224 ranking") for points already sorted highest first: tied
 * scores share a rank, and the next distinct score picks up at "1 + how many people rank above
 * it", so a two-way tie for 2nd is followed by 4th, not 3rd. This is the ranking convention
 * sports leaderboards normally use, as opposed to dense ranking, which would give 1, 2, 2, 3.
 */
fun competitionRanks(pointsDescending: List<Int>): List<Int> {
    val ranks = ArrayList<Int>(pointsDescending.size)
    pointsDescending.forEachIndexed { index, points ->
        // A new score's rank is its 1-based position; a tie repeats the rank just above it
        ranks += if (index == 0 || points != pointsDescending[index - 1]) index + 1 else ranks[index - 1]
    }
    return ranks
}
