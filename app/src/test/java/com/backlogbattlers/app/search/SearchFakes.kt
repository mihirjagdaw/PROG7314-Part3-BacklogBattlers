package com.backlogbattlers.app.search

import com.backlogbattlers.app.data.remote.GameDto
import com.backlogbattlers.app.data.repository.GameRepository
import com.backlogbattlers.app.data.repository.LibraryRepository
import com.backlogbattlers.app.data.repository.SearchHistoryRepository
import com.backlogbattlers.app.data.repository.SettingsRepository
import com.backlogbattlers.app.domain.model.AppLanguage
import com.backlogbattlers.app.domain.model.Achievement
import com.backlogbattlers.app.domain.model.CompletionType
import com.backlogbattlers.app.domain.model.Game
import com.backlogbattlers.app.domain.model.HomeBackdrop
import com.backlogbattlers.app.domain.model.LibraryEntry
import com.backlogbattlers.app.domain.model.LibraryStatus
import com.backlogbattlers.app.domain.model.Platform
import com.backlogbattlers.app.domain.model.Recommendation
import com.backlogbattlers.app.domain.model.ThemeMode
import com.backlogbattlers.app.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.UUID

fun game(
    id: Int,
    title: String,
    platforms: List<String> = listOf("PC"),
    genres: List<String> = listOf("Adventure"),
    avgCompletionHours: Float? = null,
    totalAchievements: Int? = null,
) = Game(
    gameId = id,
    title = title,
    coverImageUrl = "https://images.igdb.com/cover/$id.jpg",
    artworkUrl = "https://shared.akamai.steamstatic.com/$id/capsule_616x353.jpg",
    platforms = platforms,
    genres = genres,
    avgCompletionHours = avgCompletionHours,
    avg100PercentHours = null,
    cachedAt = 0L,
    totalAchievements = totalAchievements,
)

fun achievement(
    id: String,
    name: String = id,
    rarityPercent: Double? = 50.0,
) = Achievement(
    achievementId = id,
    name = name,
    description = "$name description",
    rarityPercent = rarityPercent,
)

class FakeGameRepository : GameRepository {

    var searchResults: List<Game> = emptyList()
    var browseResults: List<Game> = emptyList()
    var popularGames: List<Game> = emptyList()
    // stands in for the cached_games table: what GameViewModel's library list looks games up by id from
    var cachedGames: Map<Int, Game> = emptyMap()
    // what refreshGame returns, when it differs from what is already cached (e.g. a real time-to-beat)
    var refreshedGames: Map<Int, Game> = emptyMap()
    // stands in for the API's achievements endpoint, keyed by game id
    var achievementsByGame: Map<Int, List<Achievement>> = emptyMap()

    var failing = false

    val searches = mutableListOf<Pair<String, Int>>()
    val browses = mutableListOf<Pair<String, Int>>()

    override fun search(query: String): Flow<List<Game>> = MutableStateFlow(emptyList())

    override suspend fun searchOnline(query: String, limit: Int): List<Game> {
        searches += query to limit
        if (failing) throw IOException("no connection")
        return searchResults
    }

    override suspend fun browse(category: String, limit: Int): List<Game> {
        browses += category to limit
        if (failing) throw IOException("no connection")
        return browseResults
    }

    override suspend fun getGame(gameId: Int): Game? = cachedGames[gameId]

    override suspend fun refreshGame(gameId: Int): Game {
        if (failing) throw IOException("no connection")
        val fresh = refreshedGames[gameId] ?: cachedGames[gameId] ?: error("no game $gameId configured on this fake")
        cachedGames = cachedGames + (gameId to fresh)
        return fresh
    }

    override suspend fun getStarterGames(): List<Recommendation> =
        browseResults.map { Recommendation(it, "Trending with new players") }

    override suspend fun getPopularGames(): List<Game> = popularGames

    override suspend fun getAchievements(gameId: Int): List<Achievement> {
        if (failing) throw IOException("no connection")
        val achievements = achievementsByGame[gameId].orEmpty()
        cachedGames[gameId]?.let { cachedGames = cachedGames + (gameId to it.copy(totalAchievements = achievements.size)) }
        return achievements
    }

    // not used by these tests: only the Steam import caches games this way
    override suspend fun cacheFromApi(games: List<GameDto>): List<Game> = emptyList()
}

class FakeLibraryRepository : LibraryRepository {

    private val entries = MutableStateFlow<List<LibraryEntry>>(emptyList())

    override fun observeLibrary(): Flow<List<LibraryEntry>> = entries

    override fun observeByStatus(status: LibraryStatus): Flow<List<LibraryEntry>> =
        entries.map { list -> list.filter { it.status == status } }

    override fun observeByGameId(gameId: Int): Flow<LibraryEntry?> =
        entries.map { list -> list.firstOrNull { it.gameId == gameId } }

    override suspend fun addToLibrary(gameId: Int, platform: Platform, status: LibraryStatus) {
        // a stand-in clock: each entry added is one "tick" later than the last, so tests can
        // check the library list's default newest-first order without a real timestamp
        val addedAt = entries.value.size.toLong()
        entries.value = entries.value + LibraryEntry(
            libraryEntryId = UUID.randomUUID().toString(),
            gameId = gameId,
            platform = platform,
            status = status,
            hoursPlayed = 0f,
            unlockedAchievementIds = emptyList(),
            completionType = null as CompletionType?,
            addedAt = addedAt,
            updatedAt = addedAt,
        )
    }

    override suspend fun updateStatus(libraryEntryId: String, status: LibraryStatus) {
        entries.value = entries.value.map { if (it.libraryEntryId == libraryEntryId) it.copy(status = status) else it }
    }

    override suspend fun updateHoursPlayed(libraryEntryId: String, hours: Float) {
        entries.value = entries.value.map { if (it.libraryEntryId == libraryEntryId) it.copy(hoursPlayed = hours) else it }
    }

    override suspend fun setAchievementUnlocked(libraryEntryId: String, achievementId: String, unlocked: Boolean) {
        val entry = entries.value.firstOrNull { it.libraryEntryId == libraryEntryId } ?: return
        val ids = if (unlocked) entry.unlockedAchievementIds + achievementId else entry.unlockedAchievementIds - achievementId
        entries.value = entries.value.map {
            if (it.libraryEntryId == libraryEntryId) it.copy(unlockedAchievementIds = ids) else it
        }
    }

    override suspend fun remove(libraryEntryId: String) {
        entries.value = entries.value.filterNot { it.libraryEntryId == libraryEntryId }
    }

    // nothing here ever waits to be uploaded, unless a test says so
    var synced = true

    override suspend fun isSynced(libraryEntryId: String): Boolean = synced

    override suspend fun clear(userId: String) {
        entries.value = emptyList()
    }

    // what the account would have told the phone after completing a game: its status is completed, as far as [type]
    fun finish(gameId: Int, type: CompletionType) {
        entries.value = entries.value.map {
            if (it.gameId == gameId) it.copy(status = LibraryStatus.COMPLETED, completionType = type) else it
        }
    }

    // what the account would have told the phone: Steam confirmed these unlocks, or the game is completed
    fun confirm(gameId: Int, vararg achievementIds: String) {
        entries.value = entries.value.map {
            if (it.gameId == gameId) it.copy(verifiedAchievementIds = achievementIds.toList()) else it
        }
    }

    fun complete(gameId: Int, type: CompletionType) {
        entries.value = entries.value.map { if (it.gameId == gameId) it.copy(completionType = type) else it }
    }
}

class FakeSearchHistoryRepository : SearchHistoryRepository {

    private val searches = MutableStateFlow<List<String>>(emptyList())

    override fun observeRecentSearches(): Flow<List<String>> = searches

    override suspend fun recordSearch(query: String) {
        searches.value = listOf(query) + searches.value.filterNot { it.equals(query, ignoreCase = true) }
    }

    override suspend fun removeSearch(query: String) {
        searches.value = searches.value.filterNot { it.equals(query, ignoreCase = true) }
    }

    override suspend fun clear(userId: String) {
        searches.value = emptyList()
    }
}

class FakeSettingsRepository : SettingsRepository {

    val settings = MutableStateFlow(UserSettings())

    override fun observeSettings(): Flow<UserSettings> = settings
    override fun getSettingsBlocking(): UserSettings = settings.value
    override suspend fun setLanguage(language: AppLanguage) { settings.update { it.copy(language = language) } }
    override suspend fun setThemeMode(mode: ThemeMode) { settings.update { it.copy(themeMode = mode) } }
    override suspend fun setHomeBackdrop(backdrop: HomeBackdrop) { settings.update { it.copy(homeBackdrop = backdrop) } }
    override suspend fun setDefaultPlatform(platform: Platform) { settings.update { it.copy(defaultPlatform = platform) } }
    override suspend fun setBiometricLoginEnabled(enabled: Boolean) { settings.update { it.copy(biometricLoginEnabled = enabled) } }
    override suspend fun setShowBadges(show: Boolean) { settings.update { it.copy(showBadges = show) } }
    override suspend fun setShowShowcase(show: Boolean) { settings.update { it.copy(showShowcase = show) } }
    override suspend fun setAchievementNotificationsEnabled(enabled: Boolean) { settings.update { it.copy(achievementNotificationsEnabled = enabled) } }
    override suspend fun setRankChangeNotificationsEnabled(enabled: Boolean) { settings.update { it.copy(rankChangeNotificationsEnabled = enabled) } }
    override suspend fun setSeasonResetNotificationsEnabled(enabled: Boolean) { settings.update { it.copy(seasonResetNotificationsEnabled = enabled) } }
    override suspend fun setFriendActivityNotificationsEnabled(enabled: Boolean) { settings.update { it.copy(friendActivityNotificationsEnabled = enabled) } }

    var syncPending = false
    override suspend fun replaceSyncedSettings(settings: UserSettings) { this.settings.value = settings.copy(biometricLoginEnabled = this.settings.value.biometricLoginEnabled) }
    override suspend fun isSyncPending(): Boolean = syncPending
    override suspend fun setSyncPending(pending: Boolean) { syncPending = pending }
    var biometricOffered = false
    override suspend fun hasOfferedBiometricUnlock(): Boolean = biometricOffered
    override suspend fun setBiometricUnlockOffered() { biometricOffered = true }
}
