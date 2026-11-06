package com.backlogbattlers.api.notifications

import com.backlogbattlers.api.configureErrors
import com.backlogbattlers.api.db.CompletionType
import com.backlogbattlers.api.db.DatabaseFactory
import com.backlogbattlers.api.db.Devices
import com.backlogbattlers.api.db.Friendships
import com.backlogbattlers.api.db.Games
import com.backlogbattlers.api.db.LibraryEntries
import com.backlogbattlers.api.db.MonthlyLeaderboardEntries
import com.backlogbattlers.api.db.Platform
import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.routes.completionRoutes
import com.backlogbattlers.api.routes.friendRoutes
import com.backlogbattlers.api.scoring.currentMonthPeriod
import com.backlogbattlers.api.settings.SettingsDto
import com.backlogbattlers.api.settings.saveSettings
import com.backlogbattlers.api.friends.FriendRequestDto
import com.backlogbattlers.api.games.IgdbClient
import com.backlogbattlers.api.games.SteamClient
import com.backlogbattlers.api.games.igdbHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Who gets told about what, and that the Settings switches are checked before anything is sent. */
class NotifierTest {

    private val db = DatabaseFactory.init(jdbcUrl = null, memoryName = "notifier-${Uuid.random()}")
    private var caller: Uuid? = null

    /** One push as it reached the sender. */
    private data class Sent(val tokens: List<String>, val data: Map<String, String>)

    private val sent = mutableListOf<Sent>()
    private var deadTokens = emptyList<String>()
    private val sender = PushSender { tokens, data -> sent += Sent(tokens, data); deadTokens }
    private val notifier = Notifier(sender, launch = Notifier.inline)

    // completing a game first asks Steam to confirm unlocked achievements. these tests unlock none,
    // so neither is ever called: any request they did get would be answered with a 500
    private val unusedIgdb = IgdbClient(igdbHttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }), clientId = "id", clientSecret = "secret")
    private val unusedSteam = SteamClient(HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }), apiKey = "key")

    private fun ApplicationTestBuilder.installApi() {
        application {
            install(ContentNegotiation) { json() }
            configureErrors()
            routing {
                friendRoutes(notifier) { caller }
                completionRoutes(unusedIgdb, unusedSteam, notifier) { caller }
            }
        }
    }

    private fun newUser(name: String, vararg tokens: String): Uuid = transaction(db) {
        val id = Users.insert {
            it[googleSubjectId] = "google-${Uuid.random()}"
            it[displayName] = name
            it[email] = "${name.lowercase()}@example.com"
            it[lastLoginDate] = LocalDate.now()
        }[Users.id]
        tokens.forEach { token -> Devices.insert { it[Devices.token] = token; it[userId] = id } }
        id
    }

    private fun everythingOn() = SettingsDto(
        language = "en",
        themeMode = "DARK",
        homeBackdrop = "HILLS",
        defaultPlatform = Platform.PC,
        achievementNotifications = true,
        rankChangeNotifications = true,
        seasonResetNotifications = true,
        friendActivityNotifications = true,
    )

    private fun befriend(a: Uuid, b: Uuid) = transaction(db) {
        val (first, second) = if (a.toString() < b.toString()) a to b else b to a
        Friendships.insert { it[userAId] = first; it[userBId] = second }
    }

    /** A library entry for Hades, which is worth 70 points to complete (its average hours, no achievements). */
    private fun hadesEntry(owner: Uuid): Uuid = transaction(db) {
        Games.upsert { it[id] = 113112; it[title] = "Hades"; it[avgCompletionHours] = 70.0; it[avg100PercentHours] = 150.0 }
        LibraryEntries.insert { it[userId] = owner; it[gameId] = 113112; it[platform] = Platform.PC }[LibraryEntries.id]
    }

    private fun setMonthlyPoints(userId: Uuid, points: Int) = transaction(db) {
        MonthlyLeaderboardEntries.insert {
            it[MonthlyLeaderboardEntries.userId] = userId
            it[monthPeriod] = currentMonthPeriod()
            it[monthlyPoints] = points
        }
    }

    // ---- the preference filter ----

    @Test
    fun `with no saved settings every push is allowed, because the app ships with every switch on`() {
        PushType.entries.forEach { assertTrue(allowsPush(settings = null, pushEnabled = true, type = it)) }
    }

    @Test
    fun `the friend activity switch covers friend requests, acceptances and completions, and nothing else`() {
        val friendsOff = everythingOn().copy(friendActivityNotifications = false)

        assertFalse(allowsPush(friendsOff, true, PushType.FRIEND_REQUEST_RECEIVED))
        assertFalse(allowsPush(friendsOff, true, PushType.FRIEND_REQUEST_ACCEPTED))
        assertFalse(allowsPush(friendsOff, true, PushType.FRIEND_COMPLETED_GAME))
        assertTrue(allowsPush(friendsOff, true, PushType.OVERTAKEN))
    }

    @Test
    fun `the rank changes switch covers being overtaken`() {
        val rankOff = everythingOn().copy(rankChangeNotifications = false)

        assertFalse(allowsPush(rankOff, true, PushType.OVERTAKEN))
        assertTrue(allowsPush(rankOff, true, PushType.FRIEND_REQUEST_RECEIVED))
    }

    @Test
    fun `the achievements switch covers unlock pushes, and the season switch covers season pushes, and neither covers anything else`() {
        val achievementsOff = everythingOn().copy(achievementNotifications = false)
        val seasonOff = everythingOn().copy(seasonResetNotifications = false)

        assertFalse(allowsPush(achievementsOff, true, PushType.ACHIEVEMENT_UNLOCKED))
        PushType.entries.filter { it != PushType.ACHIEVEMENT_UNLOCKED }.forEach { assertTrue(allowsPush(achievementsOff, true, it)) }
        assertFalse(allowsPush(seasonOff, true, PushType.SEASON_RESET))
        PushType.entries.filter { it != PushType.SEASON_RESET }.forEach { assertTrue(allowsPush(seasonOff, true, it)) }
    }

    @Test
    fun `the account-wide push switch turns everything off`() {
        PushType.entries.forEach { assertFalse(allowsPush(everythingOn(), pushEnabled = false, type = it)) }
    }

    @Test
    fun `wantsPush reads the saved settings, and says no for a user who does not exist`() {
        val user = newUser("Ana")
        assertTrue(wantsPush(user, PushType.OVERTAKEN))

        saveSettings(user, everythingOn().copy(rankChangeNotifications = false))
        assertFalse(wantsPush(user, PushType.OVERTAKEN))

        transaction(db) { Users.update({ Users.id eq user }) { it[pushNotificationsEnabled] = false } }
        assertFalse(wantsPush(user, PushType.FRIEND_REQUEST_RECEIVED))

        assertFalse(wantsPush(Uuid.random(), PushType.FRIEND_REQUEST_RECEIVED))
    }

    // ---- who is overtaken ----

    @Test
    fun `overtaken is everyone level with or ahead of you before, and behind you now`() {
        val me = Uuid.random()
        val level = Uuid.random()
        val passed = Uuid.random()
        val stillAhead = Uuid.random()
        val alreadyBehind = Uuid.random()
        val nowLevel = Uuid.random()
        val standings = mapOf(me to 120, level to 40, passed to 90, stillAhead to 200, alreadyBehind to 10, nowLevel to 120)

        assertEquals(setOf(level, passed), overtaken(me, pointsBefore = 40, pointsAfter = 120, standings).toSet())
    }

    @Test
    fun `nobody is overtaken without a points gain, and people with no points are never counted`() {
        val me = Uuid.random()
        val zero = Uuid.random()

        assertEquals(emptyList(), overtaken(me, 50, 50, mapOf(Uuid.random() to 50)))
        assertEquals(emptyList(), overtaken(me, 0, 30, mapOf(zero to 0)))
    }

    // ---- the four events, end to end ----

    @Test
    fun `a friend request pushes to every phone of the person it was sent to`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben", "ben-phone", "ben-tablet")
        caller = ana

        client.post("/users/me/friends/requests") { contentType(ContentType.Application.Json); setBody("""{"targetUserId":"$ben"}""") }

        val push = sent.single()
        assertEquals(setOf("ben-phone", "ben-tablet"), push.tokens.toSet())
        assertEquals("FRIEND_REQUEST_RECEIVED", push.data["type"])
        assertEquals("Ana", push.data["actorName"])
        assertEquals(ben.toString(), push.data["userId"])
    }

    @Test
    fun `nothing is sent to someone who switched friend activity off`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben", "ben-phone")
        saveSettings(ben, everythingOn().copy(friendActivityNotifications = false))
        caller = ana

        client.post("/users/me/friends/requests") { contentType(ContentType.Application.Json); setBody("""{"targetUserId":"$ben"}""") }

        assertEquals(emptyList(), sent)
    }

    @Test
    fun `accepting a request tells the person who sent it`() = testApplication {
        installApi()
        val ana = newUser("Ana", "ana-phone")
        val ben = newUser("Ben")
        caller = ana
        client.post("/users/me/friends/requests") { contentType(ContentType.Application.Json); setBody("""{"targetUserId":"$ben"}""") }

        caller = ben
        val requestId = Json.decodeFromString<List<FriendRequestDto>>(client.get("/users/me/friends/requests").bodyAsText()).single().requestId
        client.post("/users/me/friends/requests/$requestId/accept")

        val push = sent.single()
        assertEquals(listOf("ana-phone"), push.tokens)
        assertEquals("FRIEND_REQUEST_ACCEPTED", push.data["type"])
        assertEquals("Ben", push.data["actorName"])
    }

    @Test
    fun `completing a game tells your friends, and tells the friends you passed on the leaderboard`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val friend = newUser("Ben", "ben-phone")
        val rival = newUser("Cleo", "cleo-phone")
        val stranger = newUser("Dev", "dev-phone")
        val leader = newUser("Eve", "eve-phone")
        befriend(ana, friend)
        befriend(ana, rival)
        setMonthlyPoints(rival, 50) // Ana goes from 0 to 70 and passes her
        setMonthlyPoints(stranger, 40) // passed on points too, but not a friend, so not on Ana's board
        setMonthlyPoints(leader, 500) // a non-friend well ahead
        caller = ana

        client.post("/users/me/library/${hadesEntry(ana)}/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"completionType":"${CompletionType.MAIN_STORY}"}""")
        }

        val byType = sent.groupBy({ it.data["type"] }, { it.tokens.single() })
        assertEquals(setOf("FRIEND_COMPLETED_GAME", "OVERTAKEN"), byType.keys)
        assertEquals(setOf("ben-phone", "cleo-phone"), byType.getValue("FRIEND_COMPLETED_GAME").toSet())
        assertEquals(listOf("cleo-phone"), byType.getValue("OVERTAKEN"))
        assertEquals("Hades", sent.first { it.data["type"] == "FRIEND_COMPLETED_GAME" }.data["gameTitle"])
        assertEquals("Ana", sent.single { it.data["type"] == "OVERTAKEN" }.data["actorName"])
    }

    @Test
    fun `someone with rank changes off is not told they were overtaken`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val rival = newUser("Cleo", "cleo-phone")
        befriend(ana, rival)
        saveSettings(rival, everythingOn().copy(rankChangeNotifications = false))
        setMonthlyPoints(rival, 50)
        caller = ana

        client.post("/users/me/library/${hadesEntry(ana)}/complete") {
            contentType(ContentType.Application.Json)
            setBody("""{"completionType":"${CompletionType.MAIN_STORY}"}""")
        }

        // she still hears about the game (that switch is on), just not about being passed
        assertEquals(listOf("FRIEND_COMPLETED_GAME"), sent.map { it.data["type"] })
    }

    @Test
    fun `unlocked achievements push the game, how many and the rarest to the player, unless the achievements switch is off`() {
        val ana = newUser("Ana", "ana-phone")

        notifier.achievementsUnlocked(ana, "Hades", 3, "Beat the Game")

        val push = sent.single()
        assertEquals(listOf("ana-phone"), push.tokens)
        assertEquals("ACHIEVEMENT_UNLOCKED", push.data["type"])
        assertEquals("Hades", push.data["gameTitle"])
        assertEquals("3", push.data["count"])
        assertEquals("Beat the Game", push.data["achievementName"])
        assertFalse("actorName" in push.data) // it is about you, not a friend

        sent.clear()
        saveSettings(ana, everythingOn().copy(achievementNotifications = false))
        notifier.achievementsUnlocked(ana, "Hades", 3, "Beat the Game")
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `a new season pushes how last month ended, unless the season switch is off`() {
        val ana = newUser("Ana", "ana-phone")

        notifier.seasonStarted(ana, "2026-10", lastRank = 2, lastPoints = 120)

        val push = sent.single()
        assertEquals("SEASON_RESET", push.data["type"])
        assertEquals("2026-10", push.data["monthPeriod"])
        assertEquals("2", push.data["lastRank"])
        assertEquals("120", push.data["lastPoints"])

        sent.clear()
        saveSettings(ana, everythingOn().copy(seasonResetNotifications = false))
        notifier.seasonStarted(ana, "2026-10", lastRank = 2, lastPoints = 120)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `a season push for someone who scored nothing has no rank`() {
        val ana = newUser("Ana", "ana-phone")

        notifier.seasonStarted(ana, "2026-10", lastRank = null, lastPoints = 0)

        val data = sent.single().data
        assertFalse("lastRank" in data)
        assertEquals("0", data["lastPoints"])
    }

    @Test
    fun `tokens FCM says are gone are forgotten after a send`() {
        val ben = newUser("Ben", "ben-phone", "ben-old-phone")
        deadTokens = listOf("ben-old-phone")

        kotlinx.coroutines.runBlocking { notifier.deliver(ben, Push(PushType.FRIEND_REQUEST_RECEIVED, actorName = "Ana")) }

        assertEquals(listOf("ben-phone"), tokensFor(ben))
    }

    @Test
    fun `a user with no phones registered costs no send at all`() {
        val ben = newUser("Ben")

        kotlinx.coroutines.runBlocking { notifier.deliver(ben, Push(PushType.FRIEND_REQUEST_RECEIVED, actorName = "Ana")) }

        assertEquals(emptyList(), sent)
    }
}
//------------------------------EOF------------------------------\\
