package com.backlogbattlers.app.profile

import android.app.Activity
import com.backlogbattlers.app.R
import com.backlogbattlers.app.data.remote.ProfileApi
import com.backlogbattlers.app.data.remote.ProfileUnavailableException
import com.backlogbattlers.app.data.remote.SessionExpiredException
import com.backlogbattlers.app.data.repository.AccessTokenSource
import com.backlogbattlers.app.data.repository.AuthRepository
import com.backlogbattlers.app.data.repository.AuthResult
import com.backlogbattlers.app.data.repository.DeleteAccountResult
import com.backlogbattlers.app.data.repository.ProfileCustomizationRepository
import com.backlogbattlers.app.data.repository.ProfileRepository
import com.backlogbattlers.app.data.repository.ProfileRepositoryImpl
import com.backlogbattlers.app.data.repository.ProfileSaveResult
import com.backlogbattlers.app.data.repository.ResponseCache
import com.backlogbattlers.app.data.repository.SavedResponse
import com.backlogbattlers.app.domain.model.BadgeId
import com.backlogbattlers.app.domain.model.BadgeProgress
import com.backlogbattlers.app.domain.model.CachedProfile
import com.backlogbattlers.app.domain.model.Profile
import com.backlogbattlers.app.domain.model.ProfileCustomization
import com.backlogbattlers.app.domain.model.User
import com.backlogbattlers.app.util.daysSince
import com.backlogbattlers.app.viewmodels.ProfileUiState
import com.backlogbattlers.app.viewmodels.ProfileViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// the profile screen: the repository that reads the profile from the API, and the view model behind the screen
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileScreenTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    //------------------------------
    // the repository, against a fake of the server

    private val seen = mutableListOf<HttpRequestData>()
    private var body = """
        {"userId":"u1","displayName":"Dylan","avatarUrl":null,"pictureId":"pfp_05","xp":850,"level":4,"xpIntoLevel":250,"xpForNextLevel":400,
         "monthlyPoints":80,"monthRank":2,"gamesInLibrary":103,"gamesCompleted":4,"achievementsUnlocked":212,
         "badges":[
           {"id":"NEMESIS","progress":1,"target":3,"earned":false},
           {"id":"BADGE_FROM_THE_FUTURE","progress":1,"target":1,"earned":true},
           {"id":"FIRST_BLOOD","progress":1,"target":1,"earned":true}],
         "rarestAchievements":[{"achievementId":"A1","name":"Beat it","iconUrl":"https://icons.test/a1.jpg","rarityPercent":0.4,"gameId":7,"gameTitle":"Hades"}],
         "unlockedRecently":9,
         "recentActivity":[
           {"gameId":7,"gameTitle":"Hades","unlockedCount":4,"lastUnlockedAt":1762340000000,"achievementsUnlocked":36,"achievementsTotal":42},
           {"gameId":8,"gameTitle":null,"unlockedCount":1,"lastUnlockedAt":1762330000000,"achievementsUnlocked":1,"achievementsTotal":0}]}
    """.trimIndent()

    private val repository = ProfileRepositoryImpl(
        ProfileApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    seen += request
                    respond(body, HttpStatusCode.OK, jsonHeaders)
                },
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
            baseUrl = "https://api.test",
            tokens = AccessTokenSource(fresh = { "token" }, renew = { "token" }, endSession = { }),
        ),
        artFor = { id -> if (id == 7) listOf("https://art.test/7-wide.jpg", "https://art.test/7-cover.jpg") else emptyList() },
    )

    // a ResponseCache that keeps everything in memory, for the repository's offline copy
    private class InMemoryResponseCache : ResponseCache {
        val saved = mutableMapOf<String, SavedResponse>()
        override suspend fun load(key: String): SavedResponse? = saved[key]
        override suspend fun save(key: String, json: String, savedAt: Long) { saved[key] = SavedResponse(json, savedAt) }
        override suspend fun clear(userId: String) { saved.clear() }
    }

    // a repository over the same fake server, answering [status], with an offline copy in [cache]
    private fun repositoryWith(cache: ResponseCache?, status: HttpStatusCode = HttpStatusCode.OK, clock: () -> Long = { 1L }) =
        ProfileRepositoryImpl(
            ProfileApi(
                httpClient = HttpClient(
                    MockEngine { request ->
                        seen += request
                        if (status == HttpStatusCode.OK) respond(body, status, jsonHeaders) else respond("", status)
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                },
                baseUrl = "https://api.test",
                tokens = AccessTokenSource(fresh = { "token" }, renew = { "token" }, endSession = { }),
            ),
            cache = cache,
            clock = clock,
        )

    @Test
    fun `a friend's profile is asked for by their id with the callers token`() = runBlocking {
        repository.getFriendProfile("u2")

        assertEquals("/users/u2/profile", seen.single().url.encodedPath)
        assertEquals("Bearer token", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a friend who is not a friend any more is a profile that is unavailable`() {
        val notFriends = repositoryWith(cache = null, status = HttpStatusCode.NotFound)

        assertThrows(ProfileUnavailableException::class.java) { runBlocking { notFriends.getFriendProfile("u2") } }
    }

    @Test
    fun `the sections a friend keeps private are mapped as hidden`() = runBlocking {
        body = body.replace("\"unlockedRecently\"", "\"badgesHidden\":true,\"showcaseHidden\":true,\"unlockedRecently\"")

        val profile = repository.getFriendProfile("u2")

        assertTrue(profile.badgesHidden)
        assertTrue(profile.showcaseHidden)
    }

    @Test
    fun `a profile without the hidden flags, as an older server sends it, shows both sections`() = runBlocking {
        val profile = repository.getProfile()

        assertFalse(profile.badgesHidden)
        assertFalse(profile.showcaseHidden)
    }

    @Test
    fun `a profile that loaded is kept for offline, with when the server answered`() = runBlocking {
        val cache = InMemoryResponseCache()
        val cached = repositoryWith(cache, clock = { 4_200L })

        cached.getProfile()
        val saved = cached.cachedProfile()

        assertEquals(4_200L, saved?.savedAt)
        assertEquals("Dylan", saved?.profile?.displayName)
        assertEquals(4, saved?.profile?.level)
        // read back the way a fresh answer is: the badge this version does not know is still left out
        assertEquals(listOf(BadgeId.FIRST_BLOOD, BadgeId.NEMESIS), saved?.profile?.badges?.map { it.id })
    }

    @Test
    fun `a friend's profile is kept apart from yours and from other friends'`() = runBlocking {
        val cache = InMemoryResponseCache()
        val cached = repositoryWith(cache)

        cached.getFriendProfile("u2")

        assertNull(cached.cachedProfile()) // not yours
        assertNull(cached.cachedProfile("u3")) // not another friend's
        assertEquals("Dylan", cached.cachedProfile("u2")?.profile?.displayName)
    }

    @Test
    fun `a profile that failed to load is not kept, and the older copy stays`() = runBlocking {
        val cache = InMemoryResponseCache()
        repositoryWith(cache, clock = { 1_000L }).getProfile()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repositoryWith(cache, status = HttpStatusCode.InternalServerError, clock = { 2_000L }).getProfile() }
        }

        assertEquals(1_000L, repositoryWith(cache).cachedProfile()?.savedAt)
    }

    @Test
    fun `a saved profile that can no longer be read counts as none`() = runBlocking {
        val cache = InMemoryResponseCache()
        cache.saved["profile:me"] = SavedResponse("this is not json", 1L)

        assertNull(repositoryWith(cache).cachedProfile())
    }

    @Test
    fun `without a cache nothing is kept and nothing is read`() = runBlocking {
        repository.getProfile()

        assertNull(repository.cachedProfile())
    }

    @Test
    fun `a cache that cannot save never stops a profile from loading`() = runBlocking {
        val broken = object : ResponseCache {
            override suspend fun load(key: String): SavedResponse? = null
            override suspend fun save(key: String, json: String, savedAt: Long) = throw java.io.IOException("disk full")
            override suspend fun clear(userId: String) = Unit
        }

        assertEquals("Dylan", repositoryWith(broken).getProfile().displayName)
    }

    @Test
    fun `the profile is asked for with the callers token`() = runBlocking {
        repository.getProfile()

        assertEquals("/users/me/profile", seen.single().url.encodedPath)
        assertEquals("Bearer token", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `the profile is mapped, with the level, totals and showcase`() = runBlocking {
        val profile = repository.getProfile()

        assertEquals("Dylan", profile.displayName)
        assertEquals("pfp_05", profile.pictureId)
        assertEquals(4, profile.level)
        assertEquals(250, profile.xpIntoLevel)
        assertEquals(400, profile.xpForNextLevel)
        assertEquals(103, profile.gamesInLibrary)
        assertEquals(4, profile.gamesCompleted)
        assertEquals(212, profile.achievementsUnlocked)
        assertEquals(2, profile.monthRank)
        assertEquals("Beat it", profile.rarestAchievements.single().name)
        assertEquals(0.4, profile.rarestAchievements.single().rarityPercent, 0.0)
        assertEquals(9, profile.unlockedRecently)
    }

    @Test
    fun `a badge this version does not know is left out, and the rest keep the apps order`() = runBlocking {
        val profile = repository.getProfile()

        assertEquals(listOf(BadgeId.FIRST_BLOOD, BadgeId.NEMESIS), profile.badges.map { it.id })
        assertEquals(1, profile.badgesEarned)
    }

    @Test
    fun `recent activity keeps each games progress, and gets its pictures from the phone`() = runBlocking {
        val activity = repository.getProfile().recentActivity

        assertEquals(listOf("Hades", null), activity.map { it.gameTitle })
        assertEquals(4, activity.first().unlockedCount)
        assertEquals(36, activity.first().achievementsUnlocked)
        assertEquals(42, activity.first().achievementsTotal)
        assertEquals(1762340000000, activity.first().lastUnlockedAt)
        assertEquals(listOf("https://art.test/7-wide.jpg", "https://art.test/7-cover.jpg"), activity.first().artUrls)
        // a game the phone has never seen has no picture, and is still listed
        assertEquals(emptyList<String>(), activity.last().artUrls)
    }

    @Test
    fun `a profile from a server without the recent activity still loads`() = runBlocking {
        body = """{"userId":"u1","displayName":"Dylan"}"""

        val profile = repository.getProfile()

        assertEquals(0, profile.unlockedRecently)
        assertEquals(emptyList<Any>(), profile.recentActivity)
    }

    @Test
    fun `days since counts whole days, and never goes below nothing`() {
        val day = 24L * 60 * 60 * 1000
        val now = 10 * day

        assertEquals(0, daysSince(now - 3_600_000, now))
        assertEquals(1, daysSince(now - 30 * 3_600_000, now))
        assertEquals(2, daysSince(now - 2 * day, now))
        assertEquals(13, daysSince(now - 13 * day - 5_000, now))
        // a phone whose clock is behind the server's
        assertEquals(0, daysSince(now + day, now))
    }

    @Test
    fun `a user who has not scored this month has no rank`() = runBlocking {
        body = body.replace("\"monthRank\":2", "\"monthRank\":null")

        assertNull(repository.getProfile().monthRank)
    }

    //------------------------------
    // the view model, against fakes

    private class FakeAuthRepository : AuthRepository {
        var user: User? = User("u1", "Alex", "alex@example.com", null, 0, 1, 0, null)

        override fun observeCurrentUser(): Flow<User?> = MutableStateFlow(user)
        override suspend fun getCurrentUser(): User? = user
        override suspend fun signInWithGoogle(activity: Activity): AuthResult = AuthResult.Failure(R.string.sign_in_failed)
        // what the screen asked the account to save, as (name, picture)
        val saved = mutableListOf<Pair<String?, String?>>()
        override suspend fun updateProfile(displayName: String?, pictureId: String?): ProfileSaveResult {
            saved += displayName to pictureId
            return ProfileSaveResult.SAVED
        }
        override suspend fun signOut() = Unit
        override suspend fun freshAccessToken(): String? = null
        override suspend fun renewAccessToken(): String? = null
        override suspend fun endSession() = Unit
        override suspend fun deleteAccount(): DeleteAccountResult = DeleteAccountResult.FAILED
    }

    private class FakeCustomizationRepository : ProfileCustomizationRepository {
        val saved = MutableStateFlow(ProfileCustomization(pictureId = "pfp_03"))

        override fun observeCustomization(): Flow<ProfileCustomization> = saved
        override suspend fun save(customization: ProfileCustomization) {
            saved.value = customization
        }
        override suspend fun clear(userId: String) = Unit
    }

    private class FakeProfileRepository : ProfileRepository {
        var profile: Profile = sample(level = 1)
        var failure: Throwable? = null
        var loads = 0

        override suspend fun getProfile(): Profile {
            loads++
            failure?.let { throw it }
            return profile
        }

        // friends' profiles by user id, how loading one fails, and the ids asked for
        val friendProfiles = mutableMapOf<String, Profile>()
        var friendFailure: Throwable? = null
        val friendsAsked = mutableListOf<String>()

        override suspend fun getFriendProfile(userId: String): Profile {
            friendsAsked += userId
            friendFailure?.let { throw it }
            return friendProfiles[userId] ?: throw ProfileUnavailableException()
        }

        // the copies kept for offline: the signed in user's own, and friends' by user id
        var cached: CachedProfile? = null
        val cachedFriends = mutableMapOf<String, CachedProfile>()

        override suspend fun cachedProfile(userId: String?): CachedProfile? = if (userId == null) cached else cachedFriends[userId]
    }

    private companion object {
        fun sample(level: Int, pictureId: String? = null) = Profile(
            userId = "u1",
            displayName = "Dylan",
            avatarUrl = null,
            pictureId = pictureId,
            xp = 0,
            level = level,
            xpIntoLevel = 0,
            xpForNextLevel = 100,
            monthlyPoints = 0,
            monthRank = null,
            gamesInLibrary = 0,
            gamesCompleted = 0,
            achievementsUnlocked = 0,
            badges = listOf(BadgeProgress(BadgeId.FIRST_BLOOD, 0, 1, false)),
            rarestAchievements = emptyList(),
            unlockedRecently = 0,
            recentActivity = emptyList(),
        )
    }

    private val auth = FakeAuthRepository()
    private val customization = FakeCustomizationRepository()
    private val fake = FakeProfileRepository()
    private var now = 1_000_000L

    private fun TestScope.viewModel(): ProfileViewModel {
        val vm = ProfileViewModel(auth, customization, fake, clock = { now })
        // the state is only kept while something collects it
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        return vm
    }

    private fun ProfileViewModel.state(): ProfileUiState = uiState.value

    @Test
    fun `the profile loads on opening, next to the name and picture`() = runTest {
        fake.profile = sample(level = 3)

        val state = viewModel().state()

        assertEquals(3, state.profile?.level)
        assertEquals("Alex", state.user?.displayName)
        assertEquals("pfp_03", state.customization.pictureId)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `a picture only this phone has is sent to the account, so friends can see it`() = runTest {
        customization.saved.value = ProfileCustomization(pictureId = "pfp_03")
        fake.profile = sample(level = 1, pictureId = null)

        viewModel()

        assertEquals(listOf<Pair<String?, String?>>(null to "pfp_03"), auth.saved)
        assertEquals("pfp_03", customization.saved.value.pictureId)
    }

    @Test
    fun `a picture only the account has is kept on this phone, as on a new phone`() = runTest {
        customization.saved.value = ProfileCustomization(pictureId = null)
        fake.profile = sample(level = 1, pictureId = "pfp_09")

        val state = viewModel().state()

        assertEquals("pfp_09", customization.saved.value.pictureId)
        assertEquals("pfp_09", state.customization.pictureId)
        assertTrue(auth.saved.isEmpty())
    }

    @Test
    fun `a picture both sides have is left alone, even when they differ`() = runTest {
        customization.saved.value = ProfileCustomization(pictureId = "pfp_03")
        fake.profile = sample(level = 1, pictureId = "pfp_09")

        viewModel()

        assertEquals("pfp_03", customization.saved.value.pictureId)
        assertTrue(auth.saved.isEmpty())
    }

    @Test
    fun `a profile that cannot load shows the error, and trying again loads it`() = runTest {
        fake.failure = java.io.IOException("offline")
        val vm = viewModel()
        assertEquals(R.string.profile_error_load, vm.state().errorMessage)
        assertNull(vm.state().profile)
        assertFalse(vm.state().isLoading)

        fake.failure = null
        vm.refresh()

        assertNull(vm.state().errorMessage)
        assertEquals(1, vm.state().profile?.level)
    }

    // ---- a friend's profile ----

    private fun TestScope.friendViewModel(userId: String): ProfileViewModel {
        val vm = ProfileViewModel(auth, customization, fake, clock = { now }, viewedUserId = userId)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
        return vm
    }

    @Test
    fun `a friend's profile is loaded by their id, and the name and picture come from it, not from the account`() = runTest {
        fake.friendProfiles["u2"] = sample(level = 7, pictureId = "pfp_12").copy(userId = "u2", displayName = "Mihir")

        val state = friendViewModel("u2").state()

        assertEquals(listOf("u2"), fake.friendsAsked)
        assertEquals(0, fake.loads) // the signed in user's own profile is not asked for
        assertTrue(state.viewingFriend)
        assertEquals(7, state.profile?.level)
        assertEquals("Mihir", state.displayName)
        assertEquals("pfp_12", state.pictureId)
        assertNull(state.user) // not Alex, who is signed in
    }

    @Test
    fun `a friend's profile never changes the signed in user's own picture`() = runTest {
        customization.saved.value = ProfileCustomization(pictureId = "pfp_03")
        fake.friendProfiles["u2"] = sample(level = 1, pictureId = "pfp_12").copy(userId = "u2")

        friendViewModel("u2")

        assertEquals("pfp_03", customization.saved.value.pictureId)
        assertTrue(auth.saved.isEmpty())
    }

    @Test
    fun `your own profile is not a friend's view`() = runTest {
        val state = viewModel().state()

        assertFalse(state.viewingFriend)
        assertEquals("Alex", state.displayName)
        assertEquals("pfp_03", state.pictureId)
    }

    @Test
    fun `a friend who is no longer a friend shows the unavailable message and no profile`() = runTest {
        // the fake answers an unknown id the way the server does for a non-friend: ProfileUnavailableException
        val state = friendViewModel("u9").state()

        assertEquals(R.string.profile_error_unavailable, state.errorMessage)
        assertNull(state.profile)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a friend's profile that cannot load says so in words about this profile, not yours`() = runTest {
        fake.friendFailure = java.io.IOException("offline")

        val state = friendViewModel("u2").state()

        assertEquals(R.string.profile_error_load_friend, state.errorMessage)
    }

    @Test
    fun `the sections a friend keeps private come through as hidden`() = runTest {
        fake.friendProfiles["u2"] = sample(level = 2).copy(userId = "u2", badges = emptyList(), badgesHidden = true, showcaseHidden = true)

        val profile = friendViewModel("u2").state().profile

        assertTrue(profile!!.badgesHidden)
        assertTrue(profile.showcaseHidden)
        assertTrue(profile.badges.isEmpty())
    }

    // ---- the copy saved for offline ----

    @Test
    fun `a saved profile is shown at once when the server cannot be reached, with when it was saved`() = runTest {
        fake.cached = CachedProfile(sample(level = 5), savedAt = 900_000L)
        fake.failure = java.io.IOException("offline")

        val state = viewModel().state()

        assertEquals(5, state.profile?.level)
        assertEquals(900_000L, state.savedAt)
        assertNull(state.errorMessage) // there is something to show, so it is not an error screen
        assertFalse(state.isLoading)
        assertEquals(R.string.profile_error_load, state.message) // a toast says it could not refresh
    }

    @Test
    fun `the server's profile replaces the saved one and the saved label goes`() = runTest {
        fake.cached = CachedProfile(sample(level = 5), savedAt = 900_000L)
        fake.profile = sample(level = 6)

        val state = viewModel().state()

        assertEquals(6, state.profile?.level)
        assertNull(state.savedAt)
    }

    @Test
    fun `with no saved copy and no connection the error screen shows`() = runTest {
        fake.failure = java.io.IOException("offline")

        val state = viewModel().state()

        assertNull(state.profile)
        assertNull(state.savedAt)
        assertEquals(R.string.profile_error_load, state.errorMessage)
    }

    @Test
    fun `coming back online reloads a saved profile even though it is not old`() = runTest {
        fake.cached = CachedProfile(sample(level = 5), savedAt = 900_000L)
        fake.failure = java.io.IOException("offline")
        val vm = viewModel()
        assertEquals(900_000L, vm.state().savedAt)

        fake.failure = null
        fake.profile = sample(level = 8)
        vm.refreshIfStale() // no time has passed, but what is shown is the saved copy

        assertEquals(8, vm.state().profile?.level)
        assertNull(vm.state().savedAt)
    }

    @Test
    fun `a friend's saved profile is shown for that friend, not another's or yours`() = runTest {
        fake.cachedFriends["u2"] = CachedProfile(sample(level = 4).copy(userId = "u2", displayName = "Mihir"), savedAt = 800_000L)
        fake.cached = CachedProfile(sample(level = 9), savedAt = 700_000L)
        fake.friendFailure = java.io.IOException("offline")

        val state = friendViewModel("u2").state()

        assertEquals("Mihir", state.displayName)
        assertEquals(4, state.profile?.level)
        assertEquals(800_000L, state.savedAt)
    }

    @Test
    fun `a friend who is no longer one drops the saved copy too`() = runTest {
        fake.cachedFriends["u2"] = CachedProfile(sample(level = 4).copy(userId = "u2"), savedAt = 800_000L)
        // the server says 404 for u2

        val state = friendViewModel("u2").state()

        assertNull(state.profile)
        assertNull(state.savedAt)
        assertEquals(R.string.profile_error_unavailable, state.errorMessage)
    }

    @Test
    fun `a session that ended shows no error, the app is already going back to login`() = runTest {
        fake.failure = SessionExpiredException()

        val state = viewModel().state()

        assertNull(state.errorMessage)
        assertNull(state.message)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a reload that fails keeps the profile on screen and says so once`() = runTest {
        val vm = viewModel()

        fake.failure = java.io.IOException("offline")
        vm.refresh(pulled = true)

        assertEquals(1, vm.state().profile?.level)
        assertNull(vm.state().errorMessage)
        assertEquals(R.string.profile_error_load, vm.state().message)
        assertFalse(vm.state().isRefreshing)
        vm.messageShown()
        assertNull(vm.state().message)
    }

    @Test
    fun `pulling to refresh replaces the profile with the newest`() = runTest {
        val vm = viewModel()

        fake.profile = sample(level = 5)
        vm.refresh(pulled = true)

        assertEquals(5, vm.state().profile?.level)
        assertFalse(vm.state().isRefreshing)
    }

    @Test
    fun `coming back soon reloads nothing, and coming back later does`() = runTest {
        val vm = viewModel()
        assertEquals(1, fake.loads)

        now += 10_000
        vm.refreshIfStale()
        assertEquals(1, fake.loads)

        now += 25_000
        fake.profile = sample(level = 2)
        vm.refreshIfStale()
        assertEquals(2, fake.loads)
        assertEquals(2, vm.state().profile?.level)
    }

    @Test
    fun `coming back to a profile that never loaded tries again at once`() = runTest {
        fake.failure = java.io.IOException("offline")
        val vm = viewModel()
        assertTrue(vm.state().profile == null)

        fake.failure = null
        vm.refreshIfStale()

        assertEquals(2, fake.loads)
        assertEquals(1, vm.state().profile?.level)
    }
}
//------------------------------EOF------------------------------\\
