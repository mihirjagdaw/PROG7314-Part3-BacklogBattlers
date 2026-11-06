package com.backlogbattlers.api.scoring

import com.backlogbattlers.api.activity.recordActivity
import com.backlogbattlers.api.db.Achievements
import com.backlogbattlers.api.db.ActivityType
import com.backlogbattlers.api.db.CompletionRecords
import com.backlogbattlers.api.db.CompletionType
import com.backlogbattlers.api.db.Games
import com.backlogbattlers.api.db.LibraryEntries
import com.backlogbattlers.api.db.LibraryStatus
import com.backlogbattlers.api.db.MonthlyLeaderboardEntries
import com.backlogbattlers.api.db.UnlockedAchievements
import com.backlogbattlers.api.friends.friendIdsOf
import com.backlogbattlers.api.notifications.overtaken
import com.backlogbattlers.api.steam.SteamCheck
import com.backlogbattlers.api.steam.SteamCheckStatus
import com.backlogbattlers.api.steam.markVerified
import com.backlogbattlers.api.steam.verifiedIds
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.uuid.Uuid

/** Body of `POST /users/me/library/{id}/complete`. */
@Serializable
data class CompleteRequest(val completionType: CompletionType)

/** Response body: the points this call awarded, and the user's new running total for the month. */
@Serializable
data class CompleteResponseDto(
    val libraryEntryId: String,
    val completionType: CompletionType,
    val achievementPoints: Int,
    val completionTimePoints: Int,
    val speedBonusPoints: Int,
    val pointsAwarded: Int,
    val monthlyPoints: Int,
    /** Whether Steam could confirm the unlocked achievements, and if not, why (see [SteamCheckStatus]). */
    val steamStatus: SteamCheckStatus = SteamCheckStatus.NOT_NEEDED,
    /** Unlocked achievements that earned nothing because Steam did not confirm them. */
    val unverifiedAchievementIds: List<String> = emptyList(),
)

/** Why a game could not be completed to 100% yet. */
@Serializable
enum class HundredPercentBlock {
    /** The game has no achievements to unlock, so there is nothing to complete. */
    NO_ACHIEVEMENTS,

    /** Some achievements are still locked. */
    NOT_ALL_UNLOCKED,

    /** Every achievement is unlocked, but Steam did not confirm all of them (see the Steam status). */
    NOT_VERIFIED,
}

/** The body of the 422 answer when 100% is not possible yet. */
@Serializable
data class HundredPercentRefusedDto(
    val error: String,
    val reason: HundredPercentBlock,
    /** Whether Steam could be asked, and if not why not. */
    val steamStatus: SteamCheckStatus,
    /** The achievements that are still locked, or that Steam did not confirm. */
    val achievementIds: List<String>,
)

sealed interface CompletionResult {
    data class Completed(val response: CompleteResponseDto) : CompletionResult
    data object NotFound : CompletionResult
    data object AlreadyCompleted : CompletionResult
    data class NotReadyForHundredPercent(val block: HundredPercentBlock, val achievementIds: List<String>, val steamStatus: SteamCheckStatus) : CompletionResult
}

/**
 * Marks one of [owner]'s library entries complete for [completionType], and awards points for it.
 *
 * Achievement points are paid only for unlocks that have never been counted before (see
 * [UnlockedAchievements.countedForPoints]) AND that Steam confirmed ([steamCheck]), so ticking
 * an achievement you never earned pays nothing. An unconfirmed unlock stays uncounted, so it can
 * still be paid by a later completion once Steam confirms it. Completing an entry twice - once for
 * its story, later for 100% - never pays out the same unlock a second time. Completion-time points use the
 * game's average hours for that milestone, with a flat fallback when IGDB has no figure. A speed
 * bonus tops that up when the player's own logged hours beat that average (see [speedBonusPoints]).
 *
 * The same (entry, completionType) pair can only ever be completed once; a repeat call returns
 * [CompletionResult.AlreadyCompleted] rather than awarding points again.
 *
 * COMPLETIONIST (100%) is only possible when every achievement of the game is unlocked AND confirmed
 * by Steam, either now ([steamCheck]) or earlier (an import, or a check that already paid for it).
 * Otherwise nothing is awarded and [CompletionResult.NotReadyForHundredPercent] says why. Whatever Steam
 * confirms on a completion becomes a confirmed unlock, which can no longer be unchecked.
 */
fun completeLibraryEntry(
    owner: Uuid,
    entryId: Uuid,
    completionType: CompletionType,
    // required, with no default, so no caller can skip the Steam check by accident
    steamCheck: SteamCheck,
    monthPeriod: String = currentMonthPeriod(),
): CompletionResult = transaction {
    val entry = LibraryEntries.selectAll()
        .where { (LibraryEntries.id eq entryId) and (LibraryEntries.userId eq owner) }
        .singleOrNull() ?: return@transaction CompletionResult.NotFound

    val alreadyDone = CompletionRecords.selectAll()
        .where { (CompletionRecords.libraryEntryId eq entryId) and (CompletionRecords.completionType eq completionType) }
        .any()
    if (alreadyDone) return@transaction CompletionResult.AlreadyCompleted

    val gameId = entry[LibraryEntries.gameId]
    val game = Games.selectAll().where { Games.id eq gameId }.single()
    val confirmedBefore = verifiedIds(entryId)

    if (completionType == CompletionType.COMPLETIONIST) {
        val everyAchievement = Achievements.selectAll().where { Achievements.gameId eq gameId }.map { it[Achievements.achievementId] }
        val unlockedNow = UnlockedAchievements.selectAll().where { UnlockedAchievements.libraryEntryId eq entryId }
            .map { it[UnlockedAchievements.achievementId] }.toSet()
        val locked = everyAchievement.filter { it !in unlockedNow }
        val unconfirmed = everyAchievement.filter { it !in confirmedBefore && it !in steamCheck.confirmed }
        val refusal = when {
            everyAchievement.isEmpty() -> CompletionResult.NotReadyForHundredPercent(HundredPercentBlock.NO_ACHIEVEMENTS, emptyList(), steamCheck.status)
            locked.isNotEmpty() -> CompletionResult.NotReadyForHundredPercent(HundredPercentBlock.NOT_ALL_UNLOCKED, locked.sorted(), steamCheck.status)
            unconfirmed.isNotEmpty() -> CompletionResult.NotReadyForHundredPercent(HundredPercentBlock.NOT_VERIFIED, unconfirmed.sorted(), steamCheck.status)
            else -> null
        }
        if (refusal != null) return@transaction refusal
    }
    // what Steam just confirmed is locked in, whichever kind of completion this is
    markVerified(entryId, steamCheck.confirmed)

    // Achievement points: only for unlocks on this entry that have never paid out before.
    // ponytail: a client that unlocks an achievement, has it counted, then removes and re-adds
    // it via PATCH would be paid for it twice, since the re-added row starts uncounted again.
    // Fine for a prototype; a real version would track this on the achievement itself.
    val unlockedNotPaid = UnlockedAchievements.selectAll()
        .where { (UnlockedAchievements.libraryEntryId eq entryId) and (UnlockedAchievements.countedForPoints eq false) }
        .map { it[UnlockedAchievements.achievementId] }
    // only what Steam confirmed is paid, just now or by an earlier import or check; the rest stays uncounted and is reported back
    val uncounted = unlockedNotPaid.filter { it in steamCheck.confirmed || it in confirmedBefore }
    val unverified = unlockedNotPaid - uncounted.toSet()

    val achievementPointsTotal = uncounted.sumOf { achievementId ->
        val rarity = Achievements.selectAll()
            .where { (Achievements.gameId eq gameId) and (Achievements.achievementId eq achievementId) }
            .singleOrNull()?.get(Achievements.rarityPercent)
        achievementPoints(rarity)
    }
    if (uncounted.isNotEmpty()) {
        UnlockedAchievements.update({
            (UnlockedAchievements.libraryEntryId eq entryId) and (UnlockedAchievements.achievementId inList uncounted)
        }) {
            it[countedForPoints] = true
        }
    }

    // "story" uses the average time to finish the main story; "100%" uses the average to fully
    // complete it. MAIN_EXTRA (main story + extras) is treated the same as MAIN_STORY: IGDB's
    // three-tier time-to-beat data (hastily/normally/completely) is not fetched, only two tiers.
    val hours = if (completionType == CompletionType.COMPLETIONIST) {
        game[Games.avg100PercentHours]
    } else {
        game[Games.avgCompletionHours]
    }
    val timePoints = completionTimePoints(hours)
    // Rewards the player's own logged hours beating that average, on top of the base points above
    val speedPoints = speedBonusPoints(actualHours = entry[LibraryEntries.hoursPlayed], averageHours = hours)
    val totalPoints = achievementPointsTotal + timePoints + speedPoints

    CompletionRecords.insert {
        it[CompletionRecords.userId] = owner
        it[CompletionRecords.libraryEntryId] = entryId
        it[CompletionRecords.completionType] = completionType
        it[CompletionRecords.achievementPoints] = achievementPointsTotal
        it[CompletionRecords.completionTimePoints] = timePoints
        it[CompletionRecords.speedBonusPoints] = speedPoints
        it[CompletionRecords.pointsAwarded] = totalPoints
        it[CompletionRecords.monthPeriod] = monthPeriod
    }

    LibraryEntries.update({ LibraryEntries.id eq entryId }) {
        it[LibraryEntries.status] = LibraryStatus.COMPLETED
        // a game that was completed to 100% stays a 100% game, even when its story is completed afterwards
        it[LibraryEntries.completionType] =
            if (entry[LibraryEntries.completionType] == CompletionType.COMPLETIONIST) CompletionType.COMPLETIONIST else completionType
        it[LibraryEntries.completedAt] = Instant.now()
        it[LibraryEntries.hoursPlayedAtCompletion] = entry[LibraryEntries.hoursPlayed]
        it[LibraryEntries.updatedAt] = Instant.now()
    }

    val newMonthlyTotal = addMonthlyPoints(owner, monthPeriod, totalPoints)

    // the feed: the completion itself, and which friends it lifted them past on this month's (friends-only) leaderboard
    // the pass is a result of the completion, so it is stamped a millisecond later: it then always sits
    // above the completion in the feed, instead of the two tying and showing in either order
    val happenedAt = Instant.now()
    recordActivity(owner, ActivityType.GAME_COMPLETED, gameId = gameId, points = totalPoints, detail = completionType.name, at = happenedAt)
    val friends = friendIdsOf(owner)
    val standings = MonthlyLeaderboardEntries.selectAll()
        .where { (MonthlyLeaderboardEntries.monthPeriod eq monthPeriod) and (MonthlyLeaderboardEntries.userId inList friends) }
        .associate { it[MonthlyLeaderboardEntries.userId] to it[MonthlyLeaderboardEntries.monthlyPoints] }
    val passed = overtaken(owner, pointsBefore = newMonthlyTotal - totalPoints, pointsAfter = newMonthlyTotal, standings = standings)
    if (passed.isNotEmpty()) {
        // the one named in the feed is the closest of them, the one with the most points
        recordActivity(
            owner,
            ActivityType.RANK_PASSED,
            subject = passed.maxBy { standings.getValue(it) },
            count = passed.size,
            at = happenedAt.plusMillis(1),
        )
    }

    CompletionResult.Completed(
        CompleteResponseDto(
            libraryEntryId = entryId.toString(),
            completionType = completionType,
            achievementPoints = achievementPointsTotal,
            completionTimePoints = timePoints,
            speedBonusPoints = speedPoints,
            pointsAwarded = totalPoints,
            monthlyPoints = newMonthlyTotal,
            steamStatus = steamCheck.status,
            unverifiedAchievementIds = unverified.sorted(),
        ),
    )
}

/**
 * Adds [amount] to [userId]'s running total for [monthPeriod], creating the row if this is
 * their first score of the month. Must be called from inside an existing transaction.
 *
 * ponytail: reads then writes within one transaction rather than an atomic `points + amount`
 * update, so two completions by the same user landing on different connections at the exact
 * same moment could race. Not a concern for a prototype used by one person at a time.
 */
private fun addMonthlyPoints(userId: Uuid, monthPeriod: String, amount: Int): Int {
    val existing = MonthlyLeaderboardEntries.selectAll()
        .where { (MonthlyLeaderboardEntries.userId eq userId) and (MonthlyLeaderboardEntries.monthPeriod eq monthPeriod) }
        .singleOrNull()

    val newTotal = (existing?.get(MonthlyLeaderboardEntries.monthlyPoints) ?: 0) + amount

    if (existing == null) {
        MonthlyLeaderboardEntries.insert {
            it[MonthlyLeaderboardEntries.userId] = userId
            it[MonthlyLeaderboardEntries.monthPeriod] = monthPeriod
            it[MonthlyLeaderboardEntries.monthlyPoints] = newTotal
        }
    } else {
        MonthlyLeaderboardEntries.update({
            (MonthlyLeaderboardEntries.userId eq userId) and (MonthlyLeaderboardEntries.monthPeriod eq monthPeriod)
        }) {
            it[monthlyPoints] = newTotal
        }
    }
    return newTotal
}
