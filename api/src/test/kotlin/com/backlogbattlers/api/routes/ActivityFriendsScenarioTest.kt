package com.backlogbattlers.api.routes

import com.backlogbattlers.api.activity.ActivityEventDto
import com.backlogbattlers.api.activity.ActivityPageDto
import com.backlogbattlers.api.configureErrors
import com.backlogbattlers.api.db.Achievements
import com.backlogbattlers.api.db.ActivityType
import com.backlogbattlers.api.db.DatabaseFactory
import com.backlogbattlers.api.db.Games
import com.backlogbattlers.api.db.LibraryEntries
import com.backlogbattlers.api.db.MonthlyLeaderboardEntries
import com.backlogbattlers.api.db.Platform
import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.friends.SendRequestResponseDto
import com.backlogbattlers.api.games.IgdbClient
import com.backlogbattlers.api.games.SteamClient
import com.backlogbattlers.api.games.igdbHttpClient
import com.backlogbattlers.api.steam.markVerified
import com.backlogbattlers.api.scoring.currentMonthPeriod
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * The feed with three people and nothing but HTTP calls between them: you and two friends, Alice and
 * Bob, who are friends of yours and not of each other. Every step goes through the real routes (friend
 * requests, library updates, completions), so this checks that the events are written by the routes,
 * and that each person's feed shows exactly the people they should see.
 */
class ActivityFriendsScenarioTest {

    private val db = DatabaseFactory.init(jdbcUrl = null, memoryName = "activity-scenario-${Uuid.random()}")
    private var caller: Uuid? = null

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val fakeIgdb = IgdbClient(
        igdbHttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/oauth2/token") {
                    respond("""{"access_token":"tok","expires_in":3600,"token_type":"bearer"}""", HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond("[]", HttpStatusCode.OK, jsonHeaders)
                }
            },
        ),
        clientId = "id",
        clientSecret = "secret",
    )
    private val fakeSteam = SteamClient(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }), apiKey = "key")

    private fun ApplicationTestBuilder.installApi() {
        application {
            install(ContentNegotiation) { json() }
            configureErrors()
            routing {
                friendRoutes { caller }
                libraryRoutes(fakeIgdb) { caller }
                completionRoutes(fakeIgdb, fakeSteam) { caller }
                activityRoutes { caller }
            }
        }
    }

    private fun newUser(name: String): Uuid = transaction(db) {
        Users.insert {
            it[googleSubjectId] = "google-${Uuid.random()}"
            it[displayName] = name
            it[email] = "${name.lowercase()}@example.com"
            it[lastLoginDate] = LocalDate.now()
        }[Users.id]
    }

    private fun seedGame(id: Int, title: String, vararg achievements: Pair<String, Double>) = transaction(db) {
        Games.insert {
            it[Games.id] = id
            it[Games.title] = title
            it[avgCompletionHours] = 12.0
            it[avg100PercentHours] = 30.0
        }
        achievements.forEach { (code, rarity) ->
            Achievements.insert { it[gameId] = id; it[achievementId] = code; it[name] = "$code name"; it[rarityPercent] = rarity }
        }
    }

    private fun addEntry(owner: Uuid, gameId: Int): Uuid = transaction(db) {
        LibraryEntries.insert {
            it[userId] = owner
            it[LibraryEntries.gameId] = gameId
            it[platform] = Platform.PC
        }[LibraryEntries.id]
    }

    private suspend fun HttpClient.json(path: String, body: String): HttpResponse =
        post(path) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.befriend(sender: Uuid, accepter: Uuid) {
        caller = sender
        val sent = json("/users/me/friends/requests", """{"targetUserId":"$accepter"}""")
        assertEquals(HttpStatusCode.Created, sent.status)
        val requestId = Json.decodeFromString<SendRequestResponseDto>(sent.bodyAsText()).requestId
        caller = accepter
        assertEquals(HttpStatusCode.OK, json("/users/me/friends/requests/$requestId/accept", "").status)
    }

    private suspend fun HttpClient.unlock(owner: Uuid, entry: Uuid, vararg codes: String) {
        caller = owner
        val body = """{"unlockedAchievementIds":[${codes.joinToString(",") { "\"$it\"" }}]}"""
        assertEquals(HttpStatusCode.OK, patch("/users/me/library/$entry") { contentType(ContentType.Application.Json); setBody(body) }.status)
    }

    private suspend fun HttpClient.complete(owner: Uuid, entry: Uuid, type: String) {
        caller = owner
        assertEquals(HttpStatusCode.OK, json("/users/me/library/$entry/complete", """{"completionType":"$type"}""").status)
    }

    private suspend fun HttpClient.feedOf(person: Uuid): List<ActivityEventDto> {
        caller = person
        val response = get("/activity")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.decodeFromString<ActivityPageDto>(response.bodyAsText()).events
    }

    // one plain line per event, as the screen words it
    private fun line(e: ActivityEventDto, viewer: String): String {
        val actor = if (e.actorName == viewer) "You" else e.actorName
        return when (e.type) {
            ActivityType.GAME_COMPLETED -> "$actor completed ${e.gameTitle}${if (e.detail == "COMPLETIONIST") " to 100%" else ""} (+${e.points} points)"
            ActivityType.ACHIEVEMENT_UNLOCKED ->
                if (e.count == 1) "$actor unlocked ${e.detail} in ${e.gameTitle}" else "$actor unlocked ${e.count} achievements in ${e.gameTitle} (rarest: ${e.detail})"
            ActivityType.FRIEND_ADDED -> "$actor and ${if (e.subjectName == viewer) "You" else e.subjectName} are now friends"
            ActivityType.RANK_PASSED -> "$actor passed ${e.subjectName}${if (e.count > 1) " and ${e.count - 1} others" else ""} on the leaderboard"
        }
    }

    private fun show(viewer: String, feed: List<ActivityEventDto>) {
        println("--- $viewer's feed ---")
        feed.forEach { println("  " + line(it, viewer)) }
    }

    @Test
    fun `two friends and you see exactly the right feeds`() = testApplication {
        installApi()
        seedGame(1, "Hades", "H_RARE" to 3.0, "H_COMMON" to 70.0, "H_MID" to 30.0)
        seedGame(2, "Celeste", "C_ONE" to 40.0)
        val me = newUser("Me")
        val alice = newUser("Alice")
        val bob = newUser("Bob")
        val aliceHades = addEntry(alice, 1)
        val bobCeleste = addEntry(bob, 2)
        val meCeleste = addEntry(me, 2)
        val month = currentMonthPeriod()
        // Me and Bob both have 5 points this month, and Alice's completion goes past both. Only Me is
        // her friend, so only Me counts as passed: Bob isn't on her (friends-only) leaderboard
        transaction(db) {
            MonthlyLeaderboardEntries.insert { it[userId] = me; it[monthPeriod] = month; it[monthlyPoints] = 5 }
            MonthlyLeaderboardEntries.insert { it[userId] = bob; it[monthPeriod] = month; it[monthlyPoints] = 5 }
        }

        client.befriend(me, alice)
        Thread.sleep(5)
        client.befriend(me, bob)
        Thread.sleep(5)
        client.unlock(alice, aliceHades, "H_RARE", "H_COMMON", "H_MID")
        // 100% needs every achievement confirmed by Steam, which an import would have recorded
        transaction(db) { markVerified(aliceHades, listOf("H_RARE", "H_COMMON", "H_MID")) }
        Thread.sleep(5)
        client.complete(alice, aliceHades, "COMPLETIONIST")
        Thread.sleep(5)
        client.unlock(bob, bobCeleste, "C_ONE")
        Thread.sleep(5)
        client.unlock(me, meCeleste, "C_ONE")

        val mine = client.feedOf(me)
        val alices = client.feedOf(alice)
        val bobs = client.feedOf(bob)
        show("Me", mine)
        show("Alice", alices)
        show("Bob", bobs)

        // me: my own unlock, Bob's unlock, Alice's completion and her pass of me, her unlocks, then both friendships
        assertEquals(
            listOf(
                ActivityType.ACHIEVEMENT_UNLOCKED, // me, Celeste
                ActivityType.ACHIEVEMENT_UNLOCKED, // Bob, Celeste
                ActivityType.RANK_PASSED, // Alice passed me
                ActivityType.GAME_COMPLETED, // Alice, Hades
                ActivityType.ACHIEVEMENT_UNLOCKED, // Alice, Hades
                ActivityType.FRIEND_ADDED, // Bob accepted
                ActivityType.FRIEND_ADDED, // Alice accepted
            ),
            mine.map { it.type },
        )
        assertEquals(listOf("Me", "Bob", "Alice", "Alice", "Alice", "Bob", "Alice"), mine.map { it.actorName })
        // Alice is not friends with Bob, so she sees yours and hers, not his
        assertEquals(false, alices.any { it.actorName == "Bob" })
        assertEquals(true, alices.any { it.actorName == "Me" })
        assertEquals(false, bobs.any { it.actorName == "Alice" })

        val unlockedHades = mine.single { it.type == ActivityType.ACHIEVEMENT_UNLOCKED && it.gameTitle == "Hades" }
        assertEquals(3, unlockedHades.count)
        assertEquals("H_RARE name", unlockedHades.detail) // the rarest of the three
        val passed = mine.single { it.type == ActivityType.RANK_PASSED }
        assertEquals("Me", passed.subjectName)
        assertEquals(1, passed.count) // Bob, not her friend, isn't counted
    }
}
//------------------------------EOF------------------------------\\
