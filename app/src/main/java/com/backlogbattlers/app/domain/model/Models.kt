package com.backlogbattlers.app.domain.model

// User data class represents domain model for user details
data class User(
    val userId: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String?,
    val xp: Int,
    val level: Int,
    val currentStreak: Int,
    val lastLoginDate: String?,
)

// Game data class represents domain model for game information
data class Game(
    val gameId: Int,
    val title: String,
    // portrait box art from IGDB
    val coverImageUrl: String?,
    // landscape art for the wide game tiles, null when the game has none
    val artworkUrl: String? = null,
    val platforms: List<String>,
    val genres: List<String> = emptyList(),
    val avgCompletionHours: Float?,
    val avg100PercentHours: Float?,
    val cachedAt: Long,
    // null until this game's achievements have been fetched at least once
    val totalAchievements: Int? = null,
    // every landscape picture the API found for the game, best first: Steam's wide pictures, then IGDB's
    // artwork and a screenshot. some may no longer exist, so the wide tiles try them in order
    val artworkUrls: List<String> = emptyList(),
) {
    // the landscape pictures to try for a wide tile, in order. games cached before the API sent a list
    // (and the built in popular games) only have artworkUrl
    val landscapeArtUrls: List<String>
        get() = artworkUrls.ifEmpty { listOfNotNull(artworkUrl) }
}

// Recommendation data class pairs a game with the line shown under its name, e.g. on the home screen.
// steamRank is its place on Steam's most played chart (1 = most played), so the line can be worded in
// the user's language; null for hand picked games, which show reason as the API sends it
data class Recommendation(
    val game: Game,
    val reason: String,
    val steamRank: Int? = null,
)

// Achievement data class represents domain model for one of a game's achievements.
// rarityPercent is the share of players who have unlocked it globally, null when unknown
data class Achievement(
    val achievementId: String,
    val name: String,
    val description: String?,
    val rarityPercent: Double?,
    // Steam's own icons, null when no STEAM_API_KEY is configured server side
    val iconUrl: String? = null,
    val iconGrayUrl: String? = null,
)

// enum representing gaming platforms
enum class Platform {
    PC,
    PLAYSTATION,
    XBOX,
    SWITCH,
    OTHER,
}

enum class BrowseCategory(val key: String) {
    ACTION("action"),
    RPG("rpg"),
    PUZZLE("puzzle"),
    COOP("coop"),
    STRATEGY("strategy"),
}

enum class SearchSort {
    RELEVANCE,
    NAME_A_Z,
    NAME_Z_A,
}

// enum representing how the games tab's library list can be sorted
enum class LibrarySort {
    RECENTLY_ADDED,
    NAME_A_Z,
    NAME_Z_A,
}

// enum representing backlog library item status
enum class LibraryStatus {
    BACKLOG,
    PLAYING,
    COMPLETED,
    ABANDONED,
}

// enum representing game completion types
enum class CompletionType {
    MAIN_STORY,
    MAIN_EXTRA,
    COMPLETIONIST,
}

// enum representing supported application languages
enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    AFRIKAANS("af"),
    ZULU("zu"),
    ;

    companion object {
        // the language for a locale tag such as "af" or "af-ZA". anything the app has no
        // translation for shows the English text, so it counts as English
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.substringBefore('-')?.lowercase()
            return entries.firstOrNull { it.tag == language } ?: ENGLISH
        }
    }
}

// enum representing the appearance the user picked in settings
enum class ThemeMode {
    DARK,
    LIGHT,
}

// enum representing the artwork shown behind the home screen header.
// the drawable each one maps to lives in util/Backdrops.kt
enum class HomeBackdrop {
    HILLS,
    MOUNTAIN,
    MEADOW,
    FOREST,
    COAST,
}

//------------------------------
// LibraryEntry data class represents domain model for user game library entries
data class LibraryEntry(
    val libraryEntryId: String,
    val gameId: Int,
    val platform: Platform,
    val status: LibraryStatus,
    val hoursPlayed: Float,
    val unlockedAchievementIds: List<String>,
    val completionType: CompletionType?,
    val addedAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = false,
    // the unlocked achievements Steam has confirmed (by an import or when the game was completed).
    // they cannot be unchecked, and 100% needs every achievement of the game to be one
    val verifiedAchievementIds: List<String> = emptyList(),
) {
    // the story is completed, or the game is completed to 100%, which includes it
    val storyCompleted: Boolean
        get() = completionType == CompletionType.MAIN_STORY || completionType == CompletionType.MAIN_EXTRA || completionType == CompletionType.COMPLETIONIST

    // completed to 100%: every achievement unlocked and confirmed by Steam
    val hundredPercentCompleted: Boolean
        get() = completionType == CompletionType.COMPLETIONIST
}

//------------------------------
// LibraryGame data class pairs a library entry with its game, for the games tab's library list
data class LibraryGame(
    val entry: LibraryEntry,
    val game: Game,
)

//------------------------------
// UserSettings data class represents domain model for application settings
data class UserSettings(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val homeBackdrop: HomeBackdrop = HomeBackdrop.HILLS,
    // the platform the user mostly plays on. games that are on it get filed and labelled under it
    val defaultPlatform: Platform = Platform.PC,
    val biometricLoginEnabled: Boolean = false,
    val achievementNotificationsEnabled: Boolean = true,
    val rankChangeNotificationsEnabled: Boolean = true,
    val seasonResetNotificationsEnabled: Boolean = true,
    val friendActivityNotificationsEnabled: Boolean = true,
    // whether friends who open this user's profile see their badges, and their showcase of rarest
    // achievements. synced with the account, so the choice follows the user and the server can honour it
    val showBadges: Boolean = true,
    val showShowcase: Boolean = true,
)

// how a signed in user relates to someone found by a friend search
enum class FriendRelationshipStatus {
    NONE,
    PENDING_SENT,
    PENDING_RECEIVED,
    FRIENDS,
}

// a person the signed in user is friends with, or the signed in user themself once
data class Friend(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val monthlyPoints: Int,
    val achievementCount: Int,
    val isOnline: Boolean,
    // the picture they picked, by the app's id
    val pictureId: String? = null,
)

// one row of the monthly leaderboard. rank comes from the backend (ties share a rank),
// unlike Friend's rank which the app works out itself from list position
data class LeaderboardEntry(
    val rank: Int,
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val monthlyPoints: Int,
    val pictureId: String? = null,
)

// this month's leaderboard: the current page of ranked entries, plus the signed in user's own
// row (me), which the backend always includes even when it falls outside that page
data class LeaderboardBoard(
    val entries: List<LeaderboardEntry>,
    val me: LeaderboardEntry?,
)

// one incoming, not yet answered friend request
data class FriendRequest(
    val requestId: String,
    val fromUserId: String,
    val fromDisplayName: String,
    val fromAvatarUrl: String?,
    val createdAt: Long,
    val fromPictureId: String? = null,
)

// one person found while searching for someone to add
data class FriendSearchResult(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val status: FriendRelationshipStatus,
    val pictureId: String? = null,
)
//------------------------------EOF------------------------------\\