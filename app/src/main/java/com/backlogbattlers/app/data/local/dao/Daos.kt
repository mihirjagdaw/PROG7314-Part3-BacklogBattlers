package com.backlogbattlers.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.backlogbattlers.app.data.local.entity.CachedGameEntity
import com.backlogbattlers.app.data.local.entity.CachedResponseEntity
import com.backlogbattlers.app.data.local.entity.LibraryEntryEntity
import com.backlogbattlers.app.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

//------------------------------
// this interface provides database access ops for UserEntity
@Dao
interface UserDao {

    // observes user data as a Flow
    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    fun observeUser(userId: String): Flow<UserEntity?>

    // fetches user data once
    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    suspend fun getUser(userId: String): UserEntity?

    // inserts or replaces user data
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    // clears user table on logout
    @Query("DELETE FROM users")
    suspend fun clear()
}

//------------------------------
// this interface provides database access ops for CachedGameEntity
@Dao
interface CachedGameDao {

    // fetches a cached game by game id
    @Query("SELECT * FROM cached_games WHERE gameId = :gameId LIMIT 1")
    suspend fun getGame(gameId: Int): CachedGameEntity?

    // searches cached games by title query as a Flow
    @Query("SELECT * FROM cached_games WHERE title LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<CachedGameEntity>>

    // inserts or replaces a cached game
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(game: CachedGameEntity)

    // inserts or replaces a list of cached games
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(games: List<CachedGameEntity>)
}

//------------------------------
// this interface provides database access ops for LibraryEntryEntity.
// every read and delete takes the signed-in account's id, so one account never reaches another's rows
@Dao
interface LibraryEntryDao {

    // observes one account's library entries ordered by update time as a Flow
    // (entries the user removed but the account has not been told about yet are hidden from every read below)
    @Query("SELECT * FROM library_entries WHERE userId = :userId AND pendingDelete = 0 ORDER BY updatedAt DESC")
    fun observeAll(userId: String): Flow<List<LibraryEntryEntity>>

    // observes one account's library entries filtered by status as a Flow
    @Query("SELECT * FROM library_entries WHERE userId = :userId AND pendingDelete = 0 AND status = :status ORDER BY updatedAt DESC")
    fun observeByStatus(userId: String, status: String): Flow<List<LibraryEntryEntity>>

    // fetches one of the account's library entries by id. null if it belongs to someone else
    @Query("SELECT * FROM library_entries WHERE userId = :userId AND pendingDelete = 0 AND libraryEntryId = :id LIMIT 1")
    suspend fun getById(userId: String, id: String): LibraryEntryEntity?

    // observes the account's entry for one game, e.g. for the game detail screen. Null once removed, or if it was never added
    @Query("SELECT * FROM library_entries WHERE userId = :userId AND pendingDelete = 0 AND gameId = :gameId LIMIT 1")
    fun observeByGameId(userId: String, gameId: Int): Flow<LibraryEntryEntity?>

    // fetches the account's library entries marked as pending sync, removals included
    @Query("SELECT * FROM library_entries WHERE userId = :userId AND pendingSync = 1")
    suspend fun getPendingSync(userId: String): List<LibraryEntryEntity>

    // every row of the account, removals not yet uploaded included. for merging the account's library in
    @Query("SELECT * FROM library_entries WHERE userId = :userId")
    suspend fun getAll(userId: String): List<LibraryEntryEntity>

    // clears the pending flag after an upload, but only if the entry was not edited again meanwhile
    // (an edit changes updatedAt), so a change made during the upload is still sent next time
    @Query("UPDATE library_entries SET pendingSync = 0 WHERE libraryEntryId = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    // deletes one row by id, whoever it belongs to. only for rows the sync already holds
    @Query("DELETE FROM library_entries WHERE libraryEntryId = :id")
    suspend fun deleteById(id: String)

    // inserts/ replaces a library entry
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LibraryEntryEntity)

    // deletes library entry
    @Delete
    suspend fun delete(entry: LibraryEntryEntity)

    // deletes every library entry belonging to one account, when that account is deleted
    @Query("DELETE FROM library_entries WHERE userId = :userId")
    suspend fun clear(userId: String)
}

//------------------------------
// database access for CachedResponseEntity: the last server answer a screen showed, per account
@Dao
interface CachedResponseDao {

    // the saved answer for [key] that [viewerId] last got, or null when there is none
    @Query("SELECT * FROM cached_responses WHERE viewerId = :viewerId AND `key` = :key LIMIT 1")
    suspend fun get(viewerId: String, key: String): CachedResponseEntity?

    // saves an answer, replacing the one saved for the same account and key
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(response: CachedResponseEntity)

    // forgets everything saved for one account, when it is deleted or signs out for good
    @Query("DELETE FROM cached_responses WHERE viewerId = :viewerId")
    suspend fun clear(viewerId: String)
}
//------------------------------EOF------------------------------\\