package com.backlogbattlers.app.viewmodels

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backlogbattlers.app.R
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.data.repository.CompletionRepository
import com.backlogbattlers.app.data.repository.GameRepository
import com.backlogbattlers.app.data.repository.LibraryRepository
import com.backlogbattlers.app.data.repository.SettingsRepository
import com.backlogbattlers.app.domain.model.Achievement
import com.backlogbattlers.app.domain.model.CompleteOutcome
import com.backlogbattlers.app.domain.model.CompletionType
import com.backlogbattlers.app.domain.model.Game
import com.backlogbattlers.app.domain.model.LibraryStatus
import com.backlogbattlers.app.domain.model.Platform
import com.backlogbattlers.app.util.achievementPoints
import com.backlogbattlers.app.util.MAX_HOURS_PLAYED
import com.backlogbattlers.app.util.completionPercent as computeCompletionPercent
import com.backlogbattlers.app.util.primaryPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// one of a game's achievements, paired with whether the signed in user has unlocked it, and whether
// Steam has confirmed that unlock (which locks it in: it cannot be unchecked any more)
data class AchievementUiItem(
    val achievement: Achievement,
    val unlocked: Boolean,
    val verified: Boolean = false,
) {
    val points: Int get() = achievementPoints(achievement.rarityPercent)
}

data class GameDetailUiState(
    val game: Game? = null,
    val achievements: List<AchievementUiItem> = emptyList(),
    // the game itself comes straight from the local cache; only the achievement list waits on
    // the network, so the header can show while this is still true
    val achievementsLoading: Boolean = true,
    val achievementsFailed: Boolean = false,
    // the default platform from Settings, and the platform the game is filed under once it is in the library
    val preferredPlatform: Platform = Platform.PC,
    val libraryPlatform: Platform? = null,
    // how far the game has been completed, null while it has not been
    val completionType: CompletionType? = null,
    // where the player stands with the game and the hours they have logged. both are null until the
    // game is in the library: picking a status or logging hours is what adds it
    val status: LibraryStatus? = null,
    val hoursPlayed: Float? = null,
    // the backend is being asked to complete the game
    val completing: Boolean = false,
    // a message from strings.xml for the screen to show once (a tap on an achievement Steam confirmed)
    @StringRes val message: Int? = null,
    // how the last attempt to complete the game went, for the screen to show once
    val completionOutcome: CompleteOutcome? = null,
) {
    // what the header shows: where the game is filed if it is in the library, otherwise where it would be
    val platform: Platform?
        get() = libraryPlatform ?: game?.let { primaryPlatform(it.platforms, preferredPlatform) }

    val unlockedCount: Int get() = achievements.count { it.unlocked }
    val totalCount: Int get() = achievements.size
    val earnedPoints: Int get() = achievements.filter { it.unlocked }.sumOf { it.points }
    val totalPoints: Int get() = achievements.sumOf { it.points }

    // any completion includes the story, and 100% is the furthest a game goes
    val storyCompleted: Boolean get() = completionType != null
    val hundredPercentCompleted: Boolean get() = completionType == CompletionType.COMPLETIONIST

    // every achievement ticked. 100% needs that, and then Steam has to confirm each one
    val allUnlocked: Boolean get() = totalCount > 0 && unlockedCount == totalCount
    val canCompleteStory: Boolean get() = !storyCompleted
    val canCompleteHundredPercent: Boolean get() = !hundredPercentCompleted && allUnlocked

    // whole achievements unlocked out of the whole list, not a points ratio
    val completionPercent: Int
        get() = computeCompletionPercent(unlockedCount, totalCount)
}

// the game detail screen: one game's header stats and its full achievement list, each one
// tappable to unlock, which earns its points and moves completion live. load() is idempotent
// per gameId so the fragment can call it from onViewCreated without refetching on rotation
class GameDetailViewModel(
    private val gameRepository: GameRepository = ServiceLocator.gameRepository,
    private val libraryRepository: LibraryRepository = ServiceLocator.libraryRepository,
    private val settingsRepository: SettingsRepository = ServiceLocator.settingsRepository,
    // fetched when a game is first completed, so building this view model needs no backend
    private val completionRepository: () -> CompletionRepository = { ServiceLocator.completionRepository },
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameDetailUiState())
    val uiState: StateFlow<GameDetailUiState> = _uiState.asStateFlow()

    private var loadedGameId: Int? = null
    // the entry backing the currently unlocked achievements, once one exists for this game
    private var libraryEntryId: String? = null
    private var observeJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                _uiState.update { it.copy(preferredPlatform = settings.defaultPlatform) }
            }
        }
    }

    fun load(gameId: Int) {
        if (loadedGameId == gameId) return
        loadedGameId = gameId
        libraryEntryId = null
        _uiState.value = GameDetailUiState(preferredPlatform = _uiState.value.preferredPlatform)

        viewModelScope.launch {
            // the cache lookup is local, so the header can show right away, ahead of the achievements
            _uiState.update { it.copy(game = gameRepository.getGame(gameId)) }

            // then refresh for the real IGDB time-to-beat, which search/starter/popular never carry
            try {
                val fresh = gameRepository.refreshGame(gameId)
                if (loadedGameId == gameId) _uiState.update { it.copy(game = fresh) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Refreshing game $gameId failed", e)
            }
        }
        fetchAchievements(gameId)
    }

    // retries just the achievement list after a failed load; the game header is already showing
    fun retryAchievements() {
        val gameId = loadedGameId ?: return
        if (!_uiState.value.achievementsFailed) return
        _uiState.update { it.copy(achievementsLoading = true, achievementsFailed = false) }
        fetchAchievements(gameId)
    }

    private fun fetchAchievements(gameId: Int) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            val achievements = try {
                gameRepository.getAchievements(gameId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Loading achievements for game $gameId failed", e)
                _uiState.update { it.copy(achievementsLoading = false, achievementsFailed = true) }
                return@launch
            }

            // stays subscribed for as long as this game is open, so an unlock (from here or
            // elsewhere) updates points and completion live
            libraryRepository.observeByGameId(gameId).collect { entry ->
                libraryEntryId = entry?.libraryEntryId
                val filedUnder = entry?.platform
                val unlockedIds = entry?.unlockedAchievementIds.orEmpty().toSet()
                val verifiedIds = entry?.verifiedAchievementIds.orEmpty().toSet()
                _uiState.update {
                    it.copy(
                        libraryPlatform = filedUnder,
                        completionType = entry?.completionType,
                        status = entry?.status,
                        hoursPlayed = entry?.hoursPlayed,
                        achievements = achievements.map { a ->
                            AchievementUiItem(a, unlocked = a.achievementId in unlockedIds, verified = a.achievementId in verifiedIds)
                        }.sortedByDescending { item -> item.achievement.rarityPercent ?: 0.0 },
                        achievementsLoading = false,
                        achievementsFailed = false,
                    )
                }
            }
        }
    }

    // unlocks a locked achievement, or locks one back if tapped again, unless Steam has confirmed it:
    // that one stays unlocked and the screen says why. Tracking an achievement implies tracking the
    // game, so a game not yet in the library is added to it first
    fun toggleAchievement(achievementId: String) {
        val gameId = loadedGameId ?: return
        val item = _uiState.value.achievements.firstOrNull { it.achievement.achievementId == achievementId } ?: return
        if (item.unlocked && item.verified) {
            _uiState.update { it.copy(message = R.string.achievement_verified_locked) }
            return
        }
        val makeUnlocked = !item.unlocked

        viewModelScope.launch {
            try {
                val entryId = ensureEntry(gameId) ?: return@launch
                libraryRepository.setAchievementUnlocked(entryId, achievementId, makeUnlocked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Toggling $achievementId on game $gameId failed", e)
            }
        }
    }

    // marks the game as not started, in progress or abandoned; a game not in the library yet is added first.
    // kept on the phone and uploaded by the library sync, so it works offline. what can change:
    //  - a game completed to 100% is finished with, so nothing about it changes any more
    //  - completed is only ever set by completing the game, never picked
    //  - a game whose story is completed can go back to in progress or abandoned, while the player goes for
    //    100%, but not back to not started: the story is done
    fun setStatus(status: LibraryStatus) {
        val gameId = loadedGameId ?: return
        val state = _uiState.value
        if (state.hundredPercentCompleted) return
        if (status == LibraryStatus.COMPLETED) return
        if (state.status == LibraryStatus.COMPLETED && status == LibraryStatus.BACKLOG) return
        if (state.status == status) return

        viewModelScope.launch {
            try {
                val entryId = ensureEntry(gameId) ?: return@launch
                libraryRepository.updateStatus(entryId, status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Setting game $gameId to $status failed", e)
            }
        }
    }

    // saves the hours the player says they have played. the speed bonus on completing a game compares
    // them with the game's average time to beat. [hours] has been checked by parseHours already.
    // the hours of a game completed to 100% are locked, because the bonus was worked out from them. before that
    // they stay open, even once the story is completed: the 100% has its own speed bonus
    fun setHours(hours: Float) {
        val gameId = loadedGameId ?: return
        if (hours < 0f || hours > MAX_HOURS_PLAYED) return
        if (_uiState.value.hundredPercentCompleted) return

        viewModelScope.launch {
            try {
                val entryId = ensureEntry(gameId) ?: return@launch
                libraryRepository.updateHoursPlayed(entryId, hours)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Logging $hours hours on game $gameId failed", e)
            }
        }
    }

    // asks the backend to complete the game: its story, or 100%. the backend checks the achievements
    // with Steam and refuses a 100% that is not earned. a game not in the library yet is added first.
    // how it went arrives in the state's completionOutcome, and the entry follows it through the library
    fun complete(type: CompletionType) {
        val gameId = loadedGameId ?: return
        if (_uiState.value.completing) return
        _uiState.update { it.copy(completing = true) }

        viewModelScope.launch {
            val outcome = try {
                val entryId = ensureEntry(gameId)
                if (entryId == null) CompleteOutcome.Failed else completionRepository().complete(entryId, type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Completing game $gameId as $type failed", e)
                CompleteOutcome.Failed
            }
            _uiState.update { it.copy(completing = false, completionOutcome = outcome) }
        }
    }

    // the screen has shown the one-off message, or the outcome of completing the game
    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    fun outcomeShown() {
        _uiState.update { it.copy(completionOutcome = null) }
    }

    // the library entry for this game, added first when there is none yet
    private suspend fun ensureEntry(gameId: Int): String? {
        libraryEntryId?.let { return it }
        val preferred = settingsRepository.observeSettings().first().defaultPlatform
        val platform = _uiState.value.game?.let { primaryPlatform(it.platforms, preferred) } ?: Platform.OTHER
        libraryRepository.addToLibrary(gameId, platform, LibraryStatus.BACKLOG)
        return libraryRepository.observeByGameId(gameId).first()?.libraryEntryId?.also { libraryEntryId = it }
    }

    private companion object {
        const val TAG = "GameDetailViewModel"
    }
}
//------------------------------EOF------------------------------\\
