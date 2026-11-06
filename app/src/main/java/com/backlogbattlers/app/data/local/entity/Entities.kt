package com.backlogbattlers.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.backlogbattlers.app.data.local.Converters


// this class contains the Room entities for local persistence.
// nothing outside data/local and data/repository imports these directly
// viewModels and ui use domain/model classes and mapping gets done in data/repository.



//------------------------------
// UserEntity class defines the local database table for user profile details
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String?,
    val xp: Int,
    val level: Int,
    val currentStreak: Int,
    //iso date format yyyy mm dd
    val lastLoginDate: String?,
)

//------------------------------
// CachedGameEntity class defines the local database table for cached game details
@Entity(tableName = "cached_games")
@TypeConverters(Converters::class)
data class CachedGameEntity(
    @PrimaryKey val gameId: Int,
    val title: String,
    val coverImageUrl: String?,
    val artworkUrl: String? = null,
    val platforms: List<String>,
    val genres: List<String> = emptyList(),
    val avgCompletionHours: Float?,
    val avg100PercentHours: Float?,
    val cachedAt: Long,
    val totalAchievements: Int? = null,
    // landscape pictures to try in order, stored "|" separated by Converters. the default matches MIGRATION_5_6
    @ColumnInfo(defaultValue = "") val artworkUrls: List<String> = emptyList(),
)

//------------------------------
// LibraryEntryEntity class defines the local database table for user game library items.
// every row belongs to one account, so a second account signed in on the same phone
// never sees (or edits) the first account's library
@Entity(tableName = "library_entries", indices = [Index("userId")])
@TypeConverters(Converters::class)
data class LibraryEntryEntity(
    @PrimaryKey val libraryEntryId: String,
    //the account this entry belongs to. the default matches MIGRATION_4_5 for rows that existed before it
    @ColumnInfo(defaultValue = "") val userId: String,
    val gameId: Int,
    val platform: String,
    val status: String,
    val hoursPlayed: Float,
    val unlockedAchievementIds: List<String>,
    //completionType enum name, nullable
    val completionType: String?,
    val addedAt: Long,
    val updatedAt: Long,
    //marks local only changes that still have to be uploaded to the account
    val pendingSync: Boolean = false,
    //the user removed this entry. it stays hidden here until the account has been told, so a removal
    //made offline is not undone by the next download. matches MIGRATION_6_7 for rows that existed before it
    @ColumnInfo(defaultValue = "0") val pendingDelete: Boolean = false,
    //the unlocked achievements Steam has confirmed, which cannot be unchecked. matches MIGRATION_7_8 for rows that existed before it
    @ColumnInfo(defaultValue = "") val verifiedAchievementIds: List<String> = emptyList(),
)

//------------------------------
// CachedResponseEntity holds the last answer the account's server gave for something a screen shows, so the
// screen can open offline with it (with a "last updated" label) instead of an error. it is the answer as the
// server sent it (json), which the repository reads back the same way as a fresh one.
// kept per signed-in account (viewerId), so a second account on the phone never sees the first one's.
// keys: "profile:me", "profile:<userId>" for a friend's, "activity:newest" for the newest page of the feed
@Entity(tableName = "cached_responses", primaryKeys = ["viewerId", "key"])
data class CachedResponseEntity(
    val viewerId: String,
    val key: String,
    val json: String,
    // epoch milliseconds the server answered, shown as "Updated 5 min ago"
    val savedAt: Long,
)
//------------------------------EOF------------------------------\\