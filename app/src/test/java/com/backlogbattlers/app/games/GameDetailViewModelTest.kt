package com.backlogbattlers.app.games

import com.backlogbattlers.app.data.repository.CompletionRepository
import com.backlogbattlers.app.domain.model.CompleteOutcome
import com.backlogbattlers.app.domain.model.CompletionType
import com.backlogbattlers.app.domain.model.HundredPercentBlock
import com.backlogbattlers.app.domain.model.LibraryStatus
import com.backlogbattlers.app.domain.model.Platform
import com.backlogbattlers.app.domain.model.SteamStatus
import com.backlogbattlers.app.search.FakeGameRepository
import com.backlogbattlers.app.search.FakeLibraryRepository
import com.backlogbattlers.app.search.FakeSettingsRepository
import com.backlogbattlers.app.search.achievement
import com.backlogbattlers.app.search.game
import com.backlogbattlers.app.R
import com.backlogbattlers.app.util.statusChoices
import com.backlogbattlers.app.viewmodels.GameDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var games: FakeGameRepository
    private lateinit var library: FakeLibraryRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        games = FakeGameRepository()
        library = FakeLibraryRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val settings = FakeSettingsRepository()

    // what the backend would answer, and what it was asked
    private class FakeCompletionRepository : CompletionRepository {
        var outcome: CompleteOutcome = CompleteOutcome.Completed(CompletionType.MAIN_STORY, 95, 0, SteamStatus.NOT_NEEDED)
        var failure: Throwable? = null
        val asked = mutableListOf<Pair<String, CompletionType>>()

        override suspend fun complete(libraryEntryId: String, type: CompletionType): CompleteOutcome {
            asked += libraryEntryId to type
            failure?.let { throw it }
            return outcome
        }
    }

    private val completion = FakeCompletionRepository()

    private fun viewModel() = GameDetailViewModel(games, library, settings, completionRepository = { completion })

    @Test
    fun `a freshly loaded game starts fully locked`() = runTest {
        games.cachedGames = mapOf(1 to game(1, "Elden Ring"))
        games.achievementsByGame = mapOf(
            1 to listOf(
                achievement("common", rarityPercent = 60.0), // 5 points
                achievement("rare", rarityPercent = 10.0), // 20 points
            ),
        )
        val viewModel = viewModel()

        viewModel.load(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Elden Ring", state.game?.title)
        assertEquals(0, state.unlockedCount)
        assertEquals(2, state.totalCount)
        assertEquals(0, state.earnedPoints)
        assertEquals(25, state.totalPoints)
        assertEquals(0, state.completionPercent)
        assertTrue(state.achievements.all { !it.unlocked })
    }

    @Test
    fun `achievements are sorted from least to most rare`() = runTest {
        games.cachedGames = mapOf(1 to game(1, "Elden Ring"))
        games.achievementsByGame = mapOf(
            1 to listOf(
                achievement("rare", rarityPercent = 5.0),
                achievement("common", rarityPercent = 80.0),
                achievement("middle", rarityPercent = 40.0),
            ),
        )
        val viewModel = viewModel()

        viewModel.load(1)
        advanceUntilIdle()

        val achievements = viewModel.uiState.value.achievements
        assertEquals("common", achievements[0].achievement.achievementId)
        assertEquals("middle", achievements[1].achievement.achievementId)
        assertEquals("rare", achievements[2].achievement.achievementId)
    }

    @Test
    fun `unlocking an achievement earns its points and moves completion`() = runTest {
        games.cachedGames = mapOf(1 to game(1, "Elden Ring", platforms = listOf("PLAYSTATION")))
        games.achievementsByGame = mapOf(
            1 to listOf(
                achievement("common", rarityPercent = 60.0), // 5 points
                achievement("ultra", rarityPercent = 0.5), // 75 points
            ),
        )
        val viewModel = viewModel()
        viewModel.load(1)
        advanceUntilIdle()

        viewModel.toggleAchievement("ultra")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.unlockedCount)
        assertEquals(75, state.earnedPoints)
        assertEquals(80, state.totalPoints)
        assertEquals(50, state.completionPercent)
        assertTrue(state.achievements.first { it.achievement.achievementId == "ultra" }.unlocked)

        // tracking an achievement implies tracking the game, on the platform it is filed under
        assertEquals(1, library.observeLibrary().first().size)
        assertEquals(Platform.PLAYSTATION, library.observeLibrary().first().single().platform)
    }

    @Test
    fun `the header shows the default platform until the game is filed, then where it was filed`() = runTest {
        games.cachedGames = mapOf(1 to game(1, "Hades", platforms = listOf("PC", "SWITCH")))
        games.achievementsByGame = mapOf(1 to listOf(achievement("a")))
        settings.setDefaultPlatform(Platform.SWITCH)
        val viewModel = viewModel()
        viewModel.load(1)
        advanceUntilIdle()

        assertEquals(Platform.SWITCH, viewModel.uiState.value.platform)

        // already in the library under PC, e.g. added before the default was changed
        library.addToLibrary(gameId = 1, platform = Platform.PC, status = LibraryStatus.BACKLOG)
        advanceUntilIdle()

        assertEquals(Platform.PC, viewModel.uiState.value.platform)
    }

    @Test
    fun `tapping an unlocked achievement locks it back`() = runTest {
        games.cachedGames = mapOf(1 to game(1, "Elden Ring"))
        games.achievementsByGame = mapOf(1 to listOf(achievement("common", rarityPercent = 60.0)))
        val viewModel = viewModel()
        viewModel.load(1)
        advanceUntilIdle()

        viewModel.toggleAchievement("common")
        advanceUntilIdle()
        viewModel.toggleAchievement("common")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.unlockedCount)
        assertEquals(0, state.earnedPoints)
        assertFalse(state.achievements.single().unlocked)
    }

    @Test
    fun `a failed achievement fetch can be retried`() = runTest {
        games.cachedGames = mapOf(1 to game(1, "Elden Ring"))
        games.achievementsByGame = mapOf(1 to listOf(achievement("common")))
        games.failing = true
        val viewModel = viewModel()

        viewModel.load(1)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertTrue(state.achievementsFailed)
        assertFalse(state.achievementsLoading)
        assertTrue(state.achievements.isEmpty())
        // the header does not need achievements to have loaded
        assertEquals("Elden Ring", state.game?.title)

        games.failing = false
        viewModel.retryAchievements()
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertFalse(state.achievementsFailed)
        assertEquals(1, state.totalCount)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.loadedWithTwoAchievements(): GameDetailViewModel {
        games.cachedGames = mapOf(1 to game(1, "Hades", platforms = listOf("PC")))
        games.achievementsByGame = mapOf(1 to listOf(achievement("a", rarityPercent = 60.0), achievement("b", rarityPercent = 10.0)))
        val viewModel = viewModel()
        viewModel.load(1)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `an achievement Steam confirmed stays unlocked when tapped, and the screen says why`() = runTest {
        val viewModel = loadedWithTwoAchievements()
        viewModel.toggleAchievement("a")
        advanceUntilIdle()
        library.confirm(1, "a")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.achievements.single { it.achievement.achievementId == "a" }.verified)

        viewModel.toggleAchievement("a")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.achievements.single { it.achievement.achievementId == "a" }.unlocked)
        assertEquals(R.string.achievement_verified_locked, state.message)
        viewModel.messageShown()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `an achievement that is only ticked can still be unticked`() = runTest {
        val viewModel = loadedWithTwoAchievements()
        viewModel.toggleAchievement("a")
        advanceUntilIdle()

        viewModel.toggleAchievement("a")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.achievements.single { it.achievement.achievementId == "a" }.unlocked)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `100 percent is only offered once every achievement is ticked, and the story until it is completed`() = runTest {
        val viewModel = loadedWithTwoAchievements()
        assertTrue(viewModel.uiState.value.canCompleteStory)
        assertFalse(viewModel.uiState.value.canCompleteHundredPercent)

        viewModel.toggleAchievement("a")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canCompleteHundredPercent)

        viewModel.toggleAchievement("b")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canCompleteHundredPercent)

        library.complete(1, CompletionType.MAIN_STORY)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.storyCompleted)
        assertFalse(viewModel.uiState.value.canCompleteStory)
        assertTrue(viewModel.uiState.value.canCompleteHundredPercent)

        library.complete(1, CompletionType.COMPLETIONIST)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hundredPercentCompleted)
        assertFalse(viewModel.uiState.value.canCompleteHundredPercent)
    }

    @Test
    fun `a game with no achievements can complete its story but never offers 100 percent`() = runTest {
        games.cachedGames = mapOf(1 to game(1, "Plain Game"))
        games.achievementsByGame = mapOf(1 to emptyList())
        val viewModel = viewModel()
        viewModel.load(1)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canCompleteStory)
        assertFalse(viewModel.uiState.value.canCompleteHundredPercent)
    }

    @Test
    fun `completing asks the backend for that entry and type, and the outcome is shown once`() = runTest {
        val viewModel = loadedWithTwoAchievements()
        viewModel.toggleAchievement("a")
        advanceUntilIdle()
        val entryId = library.observeByGameId(1).first()!!.libraryEntryId

        viewModel.complete(CompletionType.MAIN_STORY)
        advanceUntilIdle()

        assertEquals(listOf(entryId to CompletionType.MAIN_STORY), completion.asked)
        val state = viewModel.uiState.value
        assertEquals(completion.outcome, state.completionOutcome)
        assertFalse(state.completing)
        viewModel.outcomeShown()
        assertNull(viewModel.uiState.value.completionOutcome)
    }

    @Test
    fun `completing a game that is not in the library yet adds it first`() = runTest {
        val viewModel = loadedWithTwoAchievements()
        assertNull(library.observeByGameId(1).first())

        viewModel.complete(CompletionType.MAIN_STORY)
        advanceUntilIdle()

        val entry = library.observeByGameId(1).first()
        assertEquals(Platform.PC, entry?.platform)
        assertEquals(listOf(entry!!.libraryEntryId to CompletionType.MAIN_STORY), completion.asked)
    }

    @Test
    fun `a refused 100 percent is passed on as it came, and a request that fails says so`() = runTest {
        val viewModel = loadedWithTwoAchievements()
        completion.outcome = CompleteOutcome.NotReady(HundredPercentBlock.NOT_VERIFIED, SteamStatus.NOT_LINKED, 2)
        viewModel.complete(CompletionType.COMPLETIONIST)
        advanceUntilIdle()
        assertEquals(completion.outcome, viewModel.uiState.value.completionOutcome)
        viewModel.outcomeShown()

        completion.failure = java.io.IOException("offline")
        viewModel.complete(CompletionType.COMPLETIONIST)
        advanceUntilIdle()

        assertEquals(CompleteOutcome.Failed, viewModel.uiState.value.completionOutcome)
        assertFalse(viewModel.uiState.value.completing)
    }

    @Test
    fun `a second tap while completing does not ask the backend twice`() = runTest {
        val viewModel = loadedWithTwoAchievements()

        viewModel.complete(CompletionType.MAIN_STORY)
        viewModel.complete(CompletionType.MAIN_STORY)
        advanceUntilIdle()

        assertEquals(1, completion.asked.size)
    }

    @Test
    fun `a game not in the library shows no status or hours until one is picked`() = runTest {
        val viewModel = loadedWithTwoAchievements()

        assertNull(viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.hoursPlayed)
    }

    @Test
    fun `picking a status adds the game to the library and shows the pick`() = runTest {
        val viewModel = loadedWithTwoAchievements()

        viewModel.setStatus(LibraryStatus.PLAYING)
        advanceUntilIdle()

        assertEquals(LibraryStatus.PLAYING, library.observeByGameId(1).first()?.status)
        assertEquals(LibraryStatus.PLAYING, viewModel.uiState.value.status)
    }

    @Test
    fun `the status can be changed between not started, playing and abandoned`() = runTest {
        val viewModel = loadedWithTwoAchievements()
        viewModel.setStatus(LibraryStatus.PLAYING)
        advanceUntilIdle()

        viewModel.setStatus(LibraryStatus.ABANDONED)
        advanceUntilIdle()
        assertEquals(LibraryStatus.ABANDONED, viewModel.uiState.value.status)

        viewModel.setStatus(LibraryStatus.BACKLOG)
        advanceUntilIdle()
        assertEquals(LibraryStatus.BACKLOG, viewModel.uiState.value.status)
        assertEquals(1, library.observeLibrary().first().size) // still one entry, not one per pick
    }

    @Test
    fun `a status can never be set to completed from the picker`() = runTest {
        val viewModel = loadedWithTwoAchievements()
        viewModel.setStatus(LibraryStatus.PLAYING)
        advanceUntilIdle()

        viewModel.setStatus(LibraryStatus.COMPLETED)
        advanceUntilIdle()

        assertEquals(LibraryStatus.PLAYING, viewModel.uiState.value.status)
    }

    // a game with 112 hours logged, then completed as far as [type]
    private suspend fun TestScope.completedGame(type: CompletionType): GameDetailViewModel {
        val viewModel = loadedWithTwoAchievements()
        viewModel.setHours(112f)
        advanceUntilIdle()
        library.finish(1, type)
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `a game whose story is completed can still have its hours changed, as there is the 100 percent to go`() = runTest {
        val viewModel = completedGame(CompletionType.MAIN_STORY)

        viewModel.setHours(140f)
        advanceUntilIdle()

        assertEquals(140f, viewModel.uiState.value.hoursPlayed)
    }

    @Test
    fun `a game whose story is completed can go back to in progress or abandoned`() = runTest {
        val viewModel = completedGame(CompletionType.MAIN_STORY)
        assertEquals(LibraryStatus.COMPLETED, viewModel.uiState.value.status)

        viewModel.setStatus(LibraryStatus.PLAYING)
        advanceUntilIdle()
        assertEquals(LibraryStatus.PLAYING, viewModel.uiState.value.status)

        viewModel.setStatus(LibraryStatus.ABANDONED)
        advanceUntilIdle()
        assertEquals(LibraryStatus.ABANDONED, viewModel.uiState.value.status)
    }

    @Test
    fun `a game whose story is completed cannot go back to not started`() = runTest {
        val viewModel = completedGame(CompletionType.MAIN_STORY)

        viewModel.setStatus(LibraryStatus.BACKLOG)
        advanceUntilIdle()

        assertEquals(LibraryStatus.COMPLETED, viewModel.uiState.value.status)
    }

    @Test
    fun `a game completed to 100 percent is locked, its hours and its status`() = runTest {
        val viewModel = completedGame(CompletionType.COMPLETIONIST)

        viewModel.setHours(5f)
        viewModel.setStatus(LibraryStatus.PLAYING)
        advanceUntilIdle()

        assertEquals(112f, viewModel.uiState.value.hoursPlayed)
        assertEquals(LibraryStatus.COMPLETED, viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.hundredPercentCompleted)
    }

    @Test
    fun `the dropdown offers not started, in progress and abandoned, never completed`() {
        assertEquals(
            listOf(LibraryStatus.BACKLOG, LibraryStatus.PLAYING, LibraryStatus.ABANDONED),
            statusChoices(LibraryStatus.PLAYING),
        )
        assertEquals(statusChoices(LibraryStatus.PLAYING), statusChoices(null))
    }

    @Test
    fun `for a game with its story completed the dropdown shows completed, in progress and abandoned`() {
        assertEquals(
            listOf(LibraryStatus.COMPLETED, LibraryStatus.PLAYING, LibraryStatus.ABANDONED),
            statusChoices(LibraryStatus.COMPLETED),
        )
    }

    @Test
    fun `logging hours adds the game to the library and shows them`() = runTest {
        val viewModel = loadedWithTwoAchievements()

        viewModel.setHours(12.5f)
        advanceUntilIdle()

        assertEquals(12.5f, library.observeByGameId(1).first()?.hoursPlayed)
        assertEquals(12.5f, viewModel.uiState.value.hoursPlayed)
    }

    @Test
    fun `hours that are negative or past the limit are not saved`() = runTest {
        val viewModel = loadedWithTwoAchievements()
        viewModel.setHours(3f)
        advanceUntilIdle()

        viewModel.setHours(-1f)
        viewModel.setHours(100_001f)
        advanceUntilIdle()

        assertEquals(3f, viewModel.uiState.value.hoursPlayed)
    }

    @Test
    fun `the header refreshes with the real time-to-beat, which search and popular never carry`() = runTest {
        games.cachedGames = mapOf(1 to game(1, "Elden Ring", avgCompletionHours = null))
        games.refreshedGames = mapOf(1 to game(1, "Elden Ring", avgCompletionHours = 119.2f))
        val viewModel = viewModel()

        viewModel.load(1)
        advanceUntilIdle()

        assertEquals(119.2f, viewModel.uiState.value.game?.avgCompletionHours)
    }
}
