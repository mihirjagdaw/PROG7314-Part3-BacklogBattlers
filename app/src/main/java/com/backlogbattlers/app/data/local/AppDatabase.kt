package com.backlogbattlers.app.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.backlogbattlers.app.data.local.dao.CachedGameDao
import com.backlogbattlers.app.data.local.dao.CachedResponseDao
import com.backlogbattlers.app.data.local.dao.LibraryEntryDao
import com.backlogbattlers.app.data.local.dao.UserDao
import com.backlogbattlers.app.data.local.entity.CachedGameEntity
import com.backlogbattlers.app.data.local.entity.CachedResponseEntity
import com.backlogbattlers.app.data.local.entity.LibraryEntryEntity
import com.backlogbattlers.app.data.local.entity.UserEntity

//------------------------------
// this class provides the Room database for the application
private const val TAG = "AppDatabase"

@Database(
    entities = [
        UserEntity::class,
        CachedGameEntity::class,
        LibraryEntryEntity::class,
        CachedResponseEntity::class,
    ],
    // !!!changing an entity? increment the version AND add a Migration below (see MIGRATION_4_5).
    // from version 4 on the database is migrated, not wiped, so library changes that have not
    // synced yet survive an app update. a version bump with no migration now crashes on purpose
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    // returns the UserDao instance
    abstract fun userDao(): UserDao

    // returns the CachedGameDao instance
    abstract fun cachedGameDao(): CachedGameDao

    // returns the LibraryEntryDao instance
    abstract fun libraryEntryDao(): LibraryEntryDao

    // returns the CachedResponseDao instance
    abstract fun cachedResponseDao(): CachedResponseDao

    companion object {

        @Volatile
        private var instance: AppDatabase? = null

        // returns the singleton instance of AppDatabase. currentUserId is the signed-in account
        // (null when signed out), which MIGRATION_4_5 hands the existing library rows to
        fun getInstance(context: Context, currentUserId: () -> String?): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "backlog_battlers.db",
                )
                    .addMigrations(Migration4To5(currentUserId), MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    // the Part 2 builds (versions 1-3) only ever held test data, so those are still wiped
                    .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

//------------------------------
// version 4 -> 5: library entries now belong to an account (userId).
// rows from before this change were all made on this phone by whoever was signed in, so they
// go to the account that is signed in now. with nobody signed in they keep an empty userId,
// which no account can see, rather than being handed to whoever signs in next
class Migration4To5(private val currentUserId: () -> String?) : Migration(4, 5) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `library_entries` ADD COLUMN `userId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_entries_userId` ON `library_entries` (`userId`)")
        val userId = currentUserId()
        if (userId != null) {
            db.execSQL("UPDATE `library_entries` SET `userId` = ?", arrayOf(userId))
            Log.i(TAG, "Database 4 -> 5: library entries given to the signed-in account")
        } else {
            Log.i(TAG, "Database 4 -> 5: nobody signed in, library entries left unowned")
        }
    }
}

//------------------------------
// version 5 -> 6: cached games keep every landscape picture the API found (artworkUrls), so a wide tile
// can try the next one when a picture is missing. games cached before this have none, and fall back to
// their single artworkUrl until the API sends them again
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `cached_games` ADD COLUMN `artworkUrls` TEXT NOT NULL DEFAULT ''")
        Log.i(TAG, "Database 5 -> 6: cached games can now hold several landscape pictures")
    }
}

//------------------------------
// version 6 -> 7: a removed library entry is kept hidden (pendingDelete) until the account has been told,
// so syncing the library can't bring back something the user removed while offline
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `library_entries` ADD COLUMN `pendingDelete` INTEGER NOT NULL DEFAULT 0")
        Log.i(TAG, "Database 6 -> 7: library entries can now wait to be deleted from the account")
    }
}

//------------------------------
// version 7 -> 8: a library entry remembers which unlocked achievements Steam has confirmed, so they can't be unchecked.
// entries from before this know of none until the next sync with the account brings them in
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `library_entries` ADD COLUMN `verifiedAchievementIds` TEXT NOT NULL DEFAULT ''")
        Log.i(TAG, "Database 7 -> 8: library entries can now hold their confirmed achievements")
    }
}

//------------------------------
// version 8 -> 9: the profile and the newest page of the activity feed are saved as the server last
// answered them, per account, so those screens open offline with a "last updated" label. nothing
// exists to carry over: the first time each screen loads online it is saved
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cached_responses` (`viewerId` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                "`json` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`viewerId`, `key`))",
        )
        Log.i(TAG, "Database 8 -> 9: the profile and the activity feed can now be kept for offline")
    }
}
//------------------------------EOF------------------------------\\