package com.backlogbattlers.api.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.javatime.time

/**
 * Every table in the database. Each `object` below becomes one SQL table.
 * Register new tables in [ALL_TABLES] at the bottom, or they will never be created.
 */

/** One row per signed-in person. Created the first time they log in with Google. */
enum class Platform { PC, PLAYSTATION, XBOX, SWITCH, OTHER }
enum class LibraryStatus { BACKLOG, PLAYING, COMPLETED, ABANDONED }
enum class CompletionType {MAIN_STORY, MAIN_EXTRA, COMPLETIONIST }
// what happened, for the activity feed
enum class ActivityType { GAME_COMPLETED, ACHIEVEMENT_UNLOCKED, FRIEND_ADDED, RANK_PASSED }

// Users Table
object Users : Table("users") {
    // Primary key: we generate it in Kotlin instead of the database
    val id = uuid("id").clientDefault { Uuid.random() }

    // he stable Google subject ID for this person how a login token is matched to a row
    val googleSubjectId = varchar("google_subject_id", 128).uniqueIndex()

    val displayName = varchar("display_name", 100)
    val email = varchar("email", 255)
    val avatarUrl = varchar("avatar_url", 512).nullable()

    // Lifetime progression: never reset by the monthly competition
    val xp = integer("xp").default(0)
    val level = integer("level").default(1)

    // Login streak, updated on the first sign-in of each day
    val currentStreak = integer("current_streak").default(0)
    val longestStreak = integer("longest_streak").default(0)
    val lastLoginDate = date("last_login_date")

    // Settings the user can change in the app
    val pushNotificationsEnabled = bool("push_notifications_enabled").default(true)
    val preferredLanguage = varchar("preferred_language", 8).default("en")

    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)
}
// Games Table
object Games : Table("games")
{
    // this is IGDB's id so we don't generate it
    val id = integer("id")
    val title = varchar("title", 255)
    val avgCompletionHours = double("avg_completion_hours").nullable()
    val avg100PercentHours = double("avg_100_percent_hours").nullable()

    override val primaryKey = PrimaryKey(id)
}

//Achievements Table
object Achievements : Table("achievements")
{
    val gameId = integer("game_id").references(Games.id)
    val achievementId = varchar("achievement_id", 64)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val rarityPercent = double("rarity_percent").nullable()
    // absolute Steam CDN urls, null without a STEAM_API_KEY (same condition as name/description)
    val iconUrl = varchar("icon_url", 500).nullable()
    val iconGrayUrl = varchar("icon_gray_url", 500).nullable()

    // reason we have a composite primary key is because ids can repeat acorss different games
    override val primaryKey = PrimaryKey(gameId, achievementId)
}

// LibraryEntries Table
object LibraryEntries : Table ("library_entries")
{
    val id = uuid("id").clientDefault { Uuid.random() }
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val platform = enumerationByName<Platform>("platform", 20)
    val gameId = reference("game_id", Games.id)
    val status = enumerationByName<LibraryStatus>("status", 20).default(LibraryStatus.BACKLOG)
    val hoursPlayed = double("hours_played").default(0.0)
    val completionType = enumerationByName<CompletionType>("completion_type", 20).nullable()
    val hoursPlayedAtCompletion = double("hours_played_at_completion").nullable()
    val completedAt = timestamp("completed_at").nullable()
    val addedAt = timestamp("added_at").clientDefault { Instant.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init
    {
        uniqueIndex(userId, gameId, platform)
    }
}

// UnlockedAchievements Table
object UnlockedAchievements : Table("unlocked_achievements")
{
    val libraryEntryId = reference("library_entry_id", LibraryEntries.id, onDelete = ReferenceOption.CASCADE)
    val achievementId = varchar("achievement_id", 64)
    val unlockedAt = timestamp("unlocked_at").clientDefault { Instant.now() }

    // Set true by /complete once this unlock's points have been counted, so completing the same
    // entry twice (e.g. story then 100%) never pays out for the same achievement twice.
    val countedForPoints = bool("counted_for_points").default(false)

    override val primaryKey = PrimaryKey(libraryEntryId, achievementId)
}

// CompletionRecords Table: one row per (library entry, completion type), e.g. a game can be
// completed once for its story and again later for 100%.
object CompletionRecords : Table("completion_records")
{
    val id = uuid("id").clientDefault { Uuid.random() }
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val libraryEntryId = reference("library_entry_id", LibraryEntries.id, onDelete = ReferenceOption.CASCADE)
    val completionType = enumerationByName<CompletionType>("completion_type", 20)
    val achievementPoints = integer("achievement_points")
    val completionTimePoints = integer("completion_time_points")
    // A bonus for finishing faster than IGDB's average for this milestone; see scoring/PointsCalculator.kt
    val speedBonusPoints = integer("speed_bonus_points")
    val pointsAwarded = integer("points_awarded")
    // e.g. "2026-09"; ties this record to one monthly competition cycle
    val monthPeriod = varchar("month_period", 7)
    val awardedAt = timestamp("awarded_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init
    {
        // stops the same completion type on the same entry from ever paying out twice
        uniqueIndex(libraryEntryId, completionType)
    }
}

// MonthlyLeaderboardEntries Table: one row per user per month, holding their running points total.
object MonthlyLeaderboardEntries : Table("monthly_leaderboard_entries")
{
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val monthPeriod = varchar("month_period", 7)
    val monthlyPoints = integer("monthly_points").default(0)

    override val primaryKey = PrimaryKey(userId, monthPeriod)

    init
    {
        // speeds up "top N for this month" reads
        index(false, monthPeriod, monthlyPoints)
    }
}

// friendrequests table
object FriendRequests : Table("friend_requests")
{
    val id = uuid("id").clientDefault { Uuid.random() }
    val senderId = reference("sender_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val receiverId = reference("receiver_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init
    {
        // a sender can only have one pending request open to the same receiver at a time
        uniqueIndex(senderId, receiverId)
    }
}

// friendships table
object Friendships : Table("friendships")
{
    val id = uuid("id").clientDefault { Uuid.random() }
    // userAId is always smaller id of the pair, so a friendship
    // between two people is only ever stored once, never as two of the same rows
    val userAId = reference("user_a_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val userBId = reference("user_b_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init
    {
        uniqueIndex(userAId, userBId)
    }
}

// usersettings table: the app's preferences, saved here so they follow the user to another phone.
// one row per user, written in full by PUT /users/me/settings; no row until the app first saves.
// a table of its own rather than columns on Users, because SchemaUtils.create adds missing tables
// to a live database but never adds columns to an existing one
object UserSettings : Table("user_settings")
{
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    // a locale tag the app is translated into: en, af or zu
    val language = varchar("language", 8)
    val themeMode = varchar("theme_mode", 16)
    val homeBackdrop = varchar("home_backdrop", 16)
    val defaultPlatform = enumerationByName<Platform>("default_platform", 20)
    val achievementNotifications = bool("achievement_notifications")
    val rankChangeNotifications = bool("rank_change_notifications")
    val seasonResetNotifications = bool("season_reset_notifications")
    val friendActivityNotifications = bool("friend_activity_notifications")
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(userId)
}

// devices table: the phones a user is signed in on, so pushes can reach them.
// keyed by the firebase cloud messaging token, because one token belongs to one app install: when
// someone else signs in on the same phone, POST /devices moves the token to them
object Devices : Table("devices")
{
    val token = varchar("token", 512)
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(token)

    init
    {
        // "every phone this user is on", read for each push
        index(false, userId)
    }
}

// usersteamaccounts table: the Steam account a user says is theirs, used to check their achievements
// against Steam. a table of its own rather than a column on Users, because SchemaUtils.create adds
// missing tables to a live database but never adds columns to an existing one.
// one Steam account can belong to one user only
object UserSteamAccounts : Table("user_steam_accounts")
{
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    // the 17 digit SteamID64
    val steamId = varchar("steam_id", 17).uniqueIndex()
    val linkedAt = timestamp("linked_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(userId)
}

// steamapps table: which IGDB game each Steam app is, so importing a Steam library never asks
// IGDB about the same app twice, whoever imports it. game_id is null when IGDB has no game for the
// app (a tool, a soundtrack, a demo); those are asked about again after a while, in case IGDB adds
// them. not a reference to Games: the game is only stored there once someone needs it
object SteamApps : Table("steam_apps")
{
    val appId = integer("app_id")
    val gameId = integer("game_id").nullable()
    val checkedAt = timestamp("checked_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(appId)

    init
    {
        // "the Steam app of this game", read before every achievement lookup
        index(false, gameId)
    }
}

// steam_achievement_checks table: how many minutes Steam said an entry's game had been played when
// its unlocked achievements were last read from Steam. an import only asks Steam again about a game
// played since then, so importing an unchanged library costs no achievement calls
object SteamAchievementChecks : Table("steam_achievement_checks")
{
    val libraryEntryId = reference("library_entry_id", LibraryEntries.id, onDelete = ReferenceOption.CASCADE)
    val minutesPlayed = integer("minutes_played")
    val checkedAt = timestamp("checked_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(libraryEntryId)
}

// activity_events table: one row for each thing the activity feed tells people about, written at
// the moment it happens (a completion, new achievements, a friend request accepted, passing someone
// on the leaderboard). the feed reads the rows of the caller and their friends, newest first.
// kept in the SQL database rather than a document store: the feed is one indexed query, and the
// rows go with their actor when an account is deleted
object ActivityEvents : Table("activity_events")
{
    val id = uuid("id").clientDefault { Uuid.random() }
    // who did it
    val actorId = reference("actor_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val type = enumerationByName<ActivityType>("type", 24)
    // the game it was about (an IGDB id; the title is read from Games). null for the friend and rank events
    val gameId = integer("game_id").nullable()
    // the other person: the friend added, or the closest person passed on the leaderboard
    val subjectId = reference("subject_id", Users.id, onDelete = ReferenceOption.CASCADE).nullable()
    // achievements unlocked, or people passed
    val itemCount = integer("item_count").default(1)
    // points a completion earned
    val points = integer("points").nullable()
    // the completion type, or the name of the rarest achievement unlocked
    val detail = varchar("detail", 255).nullable()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(id)

    init
    {
        // "the newest events of these people", read for every feed page
        index(false, actorId, createdAt)
    }
}

// game_genres table: the IGDB genres of each saved game, one row per genre. IGDB's genres are only
// sent to the app and were never stored, and the Genre Tourist badge counts the genres of the games
// someone has completed. filled whenever a game is saved (see saveGame), so a game saved before this
// table existed gets its genres the next time it is opened
object GameGenres : Table("game_genres")
{
    val gameId = reference("game_id", Games.id, onDelete = ReferenceOption.CASCADE)
    val genre = varchar("genre", 100)

    override val primaryKey = PrimaryKey(gameId, genre)
}

// the unlocks Steam has confirmed for a library entry, by an import or by a check when the game was completed.
// a confirmed unlock cannot be unchecked again, and a game can only be completed to 100% once every
// achievement is one. a table of its own rather than a column on UnlockedAchievements, because
// SchemaUtils.create adds missing tables but never adds columns to existing ones
object VerifiedUnlocks : Table("verified_unlocks")
{
    val libraryEntryId = reference("library_entry_id", LibraryEntries.id, onDelete = ReferenceOption.CASCADE)
    val achievementId = varchar("achievement_id", 64)
    val verifiedAt = timestamp("verified_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(libraryEntryId, achievementId)
}

// userpictures table: the profile picture a user picked, saved so friends and other phones see it.
// the id is the app's own (the app ships the pictures), the API only keeps it. a table of its own for
// the reason UserSettings is one: SchemaUtils.create never adds columns to an existing table
object UserPictures : Table("user_pictures")
{
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val pictureId = varchar("picture_id", 32)

    override val primaryKey = PrimaryKey(userId)
}

// profilevisibility table: which optional sections of a user's profile their friends get to see (the
// badges and the showcase of rarest achievements). it is part of the settings the app syncs, so the
// choice follows the account and the server can honour it when a friend opens the profile. a table
// of its own for the reason UserSettings is one: SchemaUtils.create never adds columns to an
// existing table. no row means both are shown
object ProfileVisibilities : Table("profile_visibility")
{
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val showBadges = bool("show_badges")
    val showShowcase = bool("show_showcase")

    override val primaryKey = PrimaryKey(userId)
}

// seasonannouncements table: the months the "a new season has started" push went out for, so a restart or a
// second instance never sends it twice
object SeasonAnnouncements : Table("season_announcements")
{
    val monthPeriod = varchar("month_period", 7)
    val announcedAt = timestamp("announced_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(monthPeriod)
}

/** Every table, in dependency order (a table must come after the tables it references). */
val ALL_TABLES: Array<Table> = arrayOf(
    Users,
    Games,
    Achievements,
    LibraryEntries,
    UnlockedAchievements,
    FriendRequests,
    Friendships,
    CompletionRecords,
    MonthlyLeaderboardEntries,
    UserSettings,
    Devices,
    UserSteamAccounts,
    SteamApps,
    SteamAchievementChecks,
    ActivityEvents,
    GameGenres,
    VerifiedUnlocks,
    UserPictures,
    SeasonAnnouncements,
    ProfileVisibilities,
)
//------------------------------EOF------------------------------\\
