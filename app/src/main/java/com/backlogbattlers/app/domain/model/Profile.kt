package com.backlogbattlers.app.domain.model

// every badge a user can earn. the backend sends the id and how far along the user is; the name,
// description and picture are the app's (see util/Badges.kt), so they follow the app's language.
// an id this version does not know is left out
enum class BadgeId {
    FIRST_BLOOD,
    CLEAN_SWEEP,
    COMPLETION_CLUB,
    FINISHER,
    THE_LONG_HAUL,
    NEEDLE_IN_A_HAYSTACK,
    GHOST_HUNTER,
    STATISTICALLY_IMPROBABLE,
    SHELF_CLEANER,
    DUST_OFF,
    COLD_TURKEY,
    BACKLOG_ZERO,
    TOP_OF_THE_TABLE,
    PHOTO_FINISH,
    COMEBACK,
    NEMESIS,
    DAILY_DRIVER,
    MARATHON_MONTH,
    GENRE_TOURIST,
    PLATFORM_AGNOSTIC,
}

// one badge and how far along the user is: earned once progress reaches target
data class BadgeProgress(val id: BadgeId, val progress: Int, val target: Int, val earned: Boolean)

// one of the user's rarest achievements, for the showcase
data class ShowcaseAchievement(
    val achievementId: String,
    val name: String,
    val iconUrl: String?,
    // the share of players who have it, e.g. 0.4 for 0.4%
    val rarityPercent: Double,
    val gameId: Int,
    val gameTitle: String?,
)

// a game the user unlocked achievements in during the last two weeks
data class RecentActivity(
    val gameId: Int,
    val gameTitle: String?,
    // the game's pictures worth trying for the thumbnail, best first. empty when the phone has never seen the game
    val artUrls: List<String>,
    // unlocked in those two weeks
    val unlockedCount: Int,
    // epoch milliseconds of the latest of them
    val lastUnlockedAt: Long,
    // unlocked in this game altogether, out of achievementsTotal
    val achievementsUnlocked: Int,
    val achievementsTotal: Int,
)

// everything the profile screen shows that comes from the backend. xp is the points earned in
// total, and the level is worked out from it by the backend (see the README for the formula)
data class Profile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    // the picture they picked, by the app's id
    val pictureId: String? = null,
    val xp: Int,
    val level: Int,
    // xp into the current level, out of xpForNextLevel (what the whole level takes)
    val xpIntoLevel: Int,
    val xpForNextLevel: Int,
    val monthlyPoints: Int,
    // null until the user has scored this month
    val monthRank: Int?,
    val gamesInLibrary: Int,
    val gamesCompleted: Int,
    val achievementsUnlocked: Int,
    val badges: List<BadgeProgress>,
    val rarestAchievements: List<ShowcaseAchievement>,
    // unlocked over every game in the last two weeks
    val unlockedRecently: Int,
    val recentActivity: List<RecentActivity>,
    // on a friend's profile: they chose not to show their badges, or their showcase. the lists above are
    // empty then, and the screen says so instead of looking like they have none
    val badgesHidden: Boolean = false,
    val showcaseHidden: Boolean = false,
) {
    val badgesEarned: Int get() = badges.count { it.earned }
}

// a profile as it was last saved for offline: when the server last answered, so the screen can say "Updated 5 min ago"
data class CachedProfile(val profile: Profile, val savedAt: Long)
//------------------------------EOF------------------------------\\
