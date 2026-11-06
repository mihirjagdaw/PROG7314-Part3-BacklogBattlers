package com.backlogbattlers.api.profile

import com.backlogbattlers.api.db.Achievements
import com.backlogbattlers.api.db.CompletionRecords
import com.backlogbattlers.api.db.Friendships
import com.backlogbattlers.api.db.GameGenres
import com.backlogbattlers.api.db.Games
import com.backlogbattlers.api.db.LibraryEntries
import com.backlogbattlers.api.db.LibraryStatus
import com.backlogbattlers.api.db.MonthlyLeaderboardEntries
import com.backlogbattlers.api.db.UnlockedAchievements
import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.friends.areFriends
import com.backlogbattlers.api.scoring.currentMonthPeriod
import com.backlogbattlers.api.settings.profileVisibilityFor
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

// how many of the rarest achievements a profile shows (two rows of seven), how many games its recent
// activity lists, and how far back that looks
private const val SHOWCASE_SLOTS = 14
private const val RECENT_GAMES = 5
private val RECENT_WINDOW: Duration = Duration.ofDays(14)

/** The profile screen's whole answer: who, how far along, the stats, the badges, the showcase and the recent activity. */
@Serializable
data class ProfileDto(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    /** The picture the user picked, by the app's id. */
    val pictureId: String? = null,
    /** Points earned in total, across every month. */
    val xp: Int,
    val level: Int,
    /** XP into the current level, out of [xpForNextLevel] (the XP the whole level takes). */
    val xpIntoLevel: Int,
    val xpForNextLevel: Int,
    val monthlyPoints: Int,
    /** Rank on this month's leaderboard (ties share one), or null before the user has scored this month. */
    val monthRank: Int?,
    val gamesInLibrary: Int,
    val gamesCompleted: Int,
    val achievementsUnlocked: Int,
    val badges: List<BadgeDto>,
    /** The user's rarest unlocked achievements, rarest first. */
    val rarestAchievements: List<ShowcaseAchievementDto>,
    /** How many achievements the user unlocked in the last two weeks, over every game. */
    val unlockedRecently: Int,
    /** The games they unlocked achievements in during those two weeks, latest first. */
    val recentActivity: List<RecentActivityDto>,
    /**
     * True when this is a friend's profile and they have chosen not to show their badges: [badges] is then
     * empty, so the app can say so instead of showing a profile with none earned. Never true on your own.
     */
    val badgesHidden: Boolean = false,
    /** The same for the showcase of rarest achievements. */
    val showcaseHidden: Boolean = false,
)

@Serializable
data class ShowcaseAchievementDto(
    val achievementId: String,
    val name: String,
    val iconUrl: String?,
    val rarityPercent: Double,
    val gameId: Int,
    val gameTitle: String?,
)

/** One game the user unlocked achievements in during the last two weeks. */
@Serializable
data class RecentActivityDto(
    val gameId: Int,
    val gameTitle: String?,
    /** Unlocked in those two weeks. */
    val unlockedCount: Int,
    /** Epoch milliseconds of the latest of them. */
    val lastUnlockedAt: Long,
    /** Unlocked in this game altogether, out of [achievementsTotal]. */
    val achievementsUnlocked: Int,
    val achievementsTotal: Int,
)

/**
 * [target]'s profile as [viewer] may see it, or null when they may not (the same answer as for a
 * user who does not exist, so it never says who has an account). You can see your own in full and
 * your friends' without the sections they switched off in Settings; nobody else's at all.
 */
fun profileForViewer(viewer: Uuid, target: Uuid): ProfileDto? = transaction {
    if (viewer != target && !areFriends(viewer, target)) return@transaction null
    val profile = profileFor(target) ?: return@transaction null
    if (viewer == target) return@transaction profile

    val visibility = profileVisibilityFor(target)
    profile.copy(
        badges = if (visibility.showBadges) profile.badges else emptyList(),
        rarestAchievements = if (visibility.showShowcase) profile.rarestAchievements else emptyList(),
        badgesHidden = !visibility.showBadges,
        showcaseHidden = !visibility.showShowcase,
    )
}

/** The profile of [userId], or null when there is no such user. */
fun profileFor(userId: Uuid, currentMonth: String = currentMonthPeriod(), now: Instant = Instant.now()): ProfileDto? = transaction {
    val user = Users.selectAll().where { Users.id eq userId }.singleOrNull() ?: return@transaction null

    val entryRows = LibraryEntries.selectAll().where { LibraryEntries.userId eq userId }.toList()
    val entries = entryRows.map {
        EntryFacts(it[LibraryEntries.id], it[LibraryEntries.gameId], it[LibraryEntries.platform], it[LibraryEntries.status], it[LibraryEntries.addedAt])
    }
    val entryGame = entries.associate { it.entryId to it.gameId }

    val completionRows = CompletionRecords.selectAll().where { CompletionRecords.userId eq userId }.toList()
    val completions = completionRows.mapNotNull { row ->
        val gameId = entryGame[row[CompletionRecords.libraryEntryId]] ?: return@mapNotNull null
        CompletionFacts(row[CompletionRecords.libraryEntryId], gameId, row[CompletionRecords.completionType], row[CompletionRecords.monthPeriod], row[CompletionRecords.awardedAt])
    }

    val gameIds = entries.map { it.gameId }.distinct()
    val achievementRows = if (gameIds.isEmpty()) emptyMap() else {
        Achievements.selectAll().where { Achievements.gameId inList gameIds }.associateBy { it[Achievements.gameId] to it[Achievements.achievementId] }
    }
    val unlockRows = if (entries.isEmpty()) emptyList() else {
        UnlockedAchievements.selectAll().where { UnlockedAchievements.libraryEntryId inList entryGame.keys.toList() }.toList()
    }
    val unlocks = unlockRows.map { row ->
        val gameId = entryGame.getValue(row[UnlockedAchievements.libraryEntryId])
        UnlockFacts(
            entryId = row[UnlockedAchievements.libraryEntryId],
            gameId = gameId,
            achievementId = row[UnlockedAchievements.achievementId],
            unlockedAt = row[UnlockedAchievements.unlockedAt],
            rarityPercent = achievementRows[gameId to row[UnlockedAchievements.achievementId]]?.get(Achievements.rarityPercent),
        )
    }

    val gameRows = if (gameIds.isEmpty()) emptyMap() else Games.selectAll().where { Games.id inList gameIds }.associateBy { it[Games.id] }
    val genres = if (gameIds.isEmpty()) emptyMap() else {
        GameGenres.selectAll().where { GameGenres.gameId inList gameIds }.groupBy({ it[GameGenres.gameId] }, { it[GameGenres.genre] })
    }
    val games = gameIds.associateWith { id ->
        GameFacts(gameRows[id]?.get(Games.avg100PercentHours), genres[id].orEmpty().toSet())
    }

    // every month the user scored in, with their rank among everyone who scored that month
    val friendIds = Friendships.selectAll()
        .where { (Friendships.userAId eq userId) or (Friendships.userBId eq userId) }
        .map { if (it[Friendships.userAId] == userId) it[Friendships.userBId] else it[Friendships.userAId] }
    val myMonths = MonthlyLeaderboardEntries.selectAll().where { MonthlyLeaderboardEntries.userId eq userId }
        .associate { it[MonthlyLeaderboardEntries.monthPeriod] to it[MonthlyLeaderboardEntries.monthlyPoints] }
    val monthRows = if (myMonths.isEmpty()) emptyList() else {
        MonthlyLeaderboardEntries.selectAll().where { MonthlyLeaderboardEntries.monthPeriod inList myMonths.keys.toList() }.toList()
    }
    val months = myMonths.map { (month, points) ->
        val inMonth = monthRows.filter { it[MonthlyLeaderboardEntries.monthPeriod] == month }
        MonthFacts(
            month = month,
            points = points,
            rank = 1 + inMonth.count { it[MonthlyLeaderboardEntries.monthlyPoints] > points },
            friendPoints = inMonth.filter { it[MonthlyLeaderboardEntries.userId] in friendIds }
                .associate { it[MonthlyLeaderboardEntries.userId] to it[MonthlyLeaderboardEntries.monthlyPoints] },
        )
    }

    val badges = evaluateBadges(BadgeInput(entries, unlocks, completions, games, months, hasFriends = friendIds.isNotEmpty(), currentMonth = currentMonth))

    val xp = completionRows.sumOf { it[CompletionRecords.pointsAwarded] }
    val level = levelFor(xp)
    val thisMonth = months.firstOrNull { it.month == currentMonth }
    val titles = if (gameIds.isEmpty()) emptyMap() else gameRows.mapValues { it.value[Games.title] }

    // the same game on two platforms can hold the same unlock twice, and it is still one achievement
    val owned = unlocks.distinctBy { it.gameId to it.achievementId }

    val rarest = owned
        .filter { it.rarityPercent != null }
        .sortedBy { it.rarityPercent }
        .take(SHOWCASE_SLOTS)
        .map { unlock ->
            val row = achievementRows.getValue(unlock.gameId to unlock.achievementId)
            ShowcaseAchievementDto(unlock.achievementId, row[Achievements.name], row[Achievements.iconUrl], unlock.rarityPercent!!, unlock.gameId, titles[unlock.gameId])
        }

    val since = now.minus(RECENT_WINDOW)
    val totals = achievementRows.keys.groupingBy { it.first }.eachCount()
    val recentGames = owned.groupBy { it.gameId }.mapNotNull { (gameId, all) ->
        val lately = all.filter { it.unlockedAt >= since }
        if (lately.isEmpty()) return@mapNotNull null
        RecentActivityDto(
            gameId = gameId,
            gameTitle = titles[gameId],
            unlockedCount = lately.size,
            lastUnlockedAt = lately.maxOf { it.unlockedAt }.toEpochMilli(),
            achievementsUnlocked = all.size,
            achievementsTotal = maxOf(totals[gameId] ?: 0, all.size),
        )
    }.sortedByDescending { it.lastUnlockedAt }.take(RECENT_GAMES)

    ProfileDto(
        userId = userId.toString(),
        displayName = user[Users.displayName],
        avatarUrl = user[Users.avatarUrl],
        pictureId = pictureIdsFor(listOf(userId))[userId],
        xp = xp,
        level = level.level,
        xpIntoLevel = level.xpIntoLevel,
        xpForNextLevel = level.xpForNextLevel,
        monthlyPoints = thisMonth?.points ?: 0,
        monthRank = thisMonth?.takeIf { it.points > 0 }?.rank,
        gamesInLibrary = gameIds.size,
        gamesCompleted = entryRows.filter { it[LibraryEntries.status] == LibraryStatus.COMPLETED }.map { it[LibraryEntries.gameId] }.distinct().size,
        achievementsUnlocked = owned.size,
        badges = badges,
        rarestAchievements = rarest,
        unlockedRecently = owned.count { it.unlockedAt >= since },
        recentActivity = recentGames,
    )
}
//------------------------------EOF------------------------------\
