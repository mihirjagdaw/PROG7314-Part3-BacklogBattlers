package com.backlogbattlers.api.routes

import com.backlogbattlers.api.activity.ActivityPageDto
import com.backlogbattlers.api.activity.recordActivity
import com.backlogbattlers.api.configureErrors
import com.backlogbattlers.api.db.Achievements
import com.backlogbattlers.api.db.ActivityEvents
import com.backlogbattlers.api.db.ActivityType
import com.backlogbattlers.api.db.CompletionType
import com.backlogbattlers.api.db.DatabaseFactory
import com.backlogbattlers.api.db.Games
import com.backlogbattlers.api.db.LibraryEntries
import com.backlogbattlers.api.db.MonthlyLeaderboardEntries
import com.backlogbattlers.api.db.Platform
import com.backlogbattlers.api.db.UnlockedAchievements
import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.friends.acceptRequest
import com.backlogbattlers.api.friends.sendRequest
import com.backlogbattlers.api.friends.SendRequestResult
import com.backlogbattlers.api.library.UpdateEntryRequest
import com.backlogbattlers.api.library.updateEntry
import com.backlogbattlers.api.scoring.completeLibraryEntry
import com.backlogbattlers.api.scoring.currentMonthPeriod
import com.backlogbattlers.api.steam.SteamCheck
import com.backlogbattlers.api.steam.SteamCheckStatus
import com.backlogbattlers.api.steam.markVerified
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** The activity feed: what writes its events, and what it shows the caller and their friends. */
class ActivityRoutesTest {

    private val db = DatabaseFactory.init(jdbcUrl = null, memoryName = "activity-routes-${Uuid.random()}")
    private var caller: Uuid? = null
    private val start = Instant.parse("2026-11-01T10:00:00Z")

    private fun ApplicationTestBuilder.installApi() {
        application {
            install(ContentNegotiation) { json() }
            configureErrors()
            routing { activityRoutes { caller } }
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

    private fun befriend(a: Uuid, b: Uuid) = transaction(db) {
        val request = sendRequest(a, b) as SendRequestResult.Sent
        acceptRequest(b, Uuid.parse(request.request.requestId))
    }

    private fun seedGame(id: Int = 113112, title: String = "Hades") = transaction(db) {
        Games.upsert {
            it[Games.id] = id
            it[Games.title] = title
            it[avgCompletionHours] = 20.0
            it[avg100PercentHours] = 40.0
        }
        Achievements.insert { it[gameId] = id; it[achievementId] = "COMMON"; it[name] = "Common One"; it[rarityPercent] = 80.0 }
        Achievements.insert { it[gameId] = id; it[achievementId] = "RARE"; it[name] = "Rare One"; it[rarityPercent] = 3.0 }
        Achievements.insert { it[gameId] = id; it[achievementId] = "NAMELESS"; it[name] = "No Rarity"; it[rarityPercent] = null }
    }

    private fun addEntry(owner: Uuid, gameId: Int = 113112): Uuid = transaction(db) {
        LibraryEntries.insert {
            it[userId] = owner
            it[LibraryEntries.gameId] = gameId
            it[platform] = Platform.PC
        }[LibraryEntries.id]
    }

    /** Every achievement of the seeded game unlocked and confirmed by Steam, which completing to 100% needs. */
    private fun unlockAndVerifyEverything(entry: Uuid) = transaction(db) {
        val codes = listOf("COMMON", "RARE", "NAMELESS")
        codes.forEach { code -> UnlockedAchievements.insert { it[libraryEntryId] = entry; it[achievementId] = code } }
        markVerified(entry, codes)
    }

    private fun event(actor: Uuid, minutesIn: Long, gameId: Int? = null) = transaction(db) {
        recordActivity(actor, ActivityType.GAME_COMPLETED, gameId = gameId, at = start.plusSeconds(minutesIn * 60))
    }

    private fun events(page: String) = Json.decodeFromString<ActivityPageDto>(page)

    @Test
    fun `the feed answers 401 when nobody is signed in`() = testApplication {
        installApi()
        caller = null

        assertEquals(HttpStatusCode.Unauthorized, client.get("/activity").status)
    }

    @Test
    fun `the feed shows the callers and their friends events, newest first, and nobody else's`() = testApplication {
        installApi()
        seedGame()
        val me = newUser("Me")
        val friend = newUser("Friend")
        val stranger = newUser("Stranger")
        befriend(me, friend)
        transaction(db) { ActivityEvents.deleteAll() } // the friendship's own event is not what this checks
        event(me, 1, 113112)
        event(friend, 3)
        event(stranger, 2)
        caller = me

        val feed = events(client.get("/activity").bodyAsText())

        assertEquals(listOf("Friend", "Me"), feed.events.map { it.actorName })
        assertEquals("Hades", feed.events.last().gameTitle)
        assertNull(feed.nextBefore)
    }

    @Test
    fun `the feed is paged, and nextBefore carries on from where the page stopped`() = testApplication {
        installApi()
        val me = newUser("Me")
        (1L..5L).forEach { event(me, it) }
        caller = me

        val first = events(client.get("/activity?limit=2").bodyAsText())
        assertEquals(listOf(5L, 4L), first.events.map { (it.createdAt - start.toEpochMilli()) / 60_000 })
        val second = events(client.get("/activity?limit=2&before=${first.nextBefore}").bodyAsText())
        assertEquals(listOf(3L, 2L), second.events.map { (it.createdAt - start.toEpochMilli()) / 60_000 })
        val last = events(client.get("/activity?limit=2&before=${second.nextBefore}").bodyAsText())
        assertEquals(listOf(1L), last.events.map { (it.createdAt - start.toEpochMilli()) / 60_000 })
        assertNull(last.nextBefore)
    }

    @Test
    fun `a limit or before that is not a number is 400`() = testApplication {
        installApi()
        caller = newUser("Me")

        assertEquals(HttpStatusCode.BadRequest, client.get("/activity?limit=zero").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/activity?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/activity?before=yesterday").status)
    }

    @Test
    fun `completing a game puts it in the feed with its points`() = testApplication {
        installApi()
        seedGame()
        val me = newUser("Me")
        val entry = addEntry(me)
        unlockAndVerifyEverything(entry)
        caller = me

        completeLibraryEntry(me, entry, CompletionType.COMPLETIONIST, SteamCheck(SteamCheckStatus.NOT_NEEDED))

        val completed = events(client.get("/activity").bodyAsText()).events.single()
        assertEquals(ActivityType.GAME_COMPLETED, completed.type)
        assertEquals("Hades", completed.gameTitle)
        assertEquals(CompletionType.COMPLETIONIST.name, completed.detail)
        assertEquals(true, (completed.points ?: 0) > 0)
    }

    @Test
    fun `a completion that passes people names the closest one, once`() = testApplication {
        installApi()
        seedGame()
        val me = newUser("Me")
        val near = newUser("Near")
        val far = newUser("Far")
        val ahead = newUser("Ahead")
        val stranger = newUser("Stranger")
        val entry = addEntry(me)
        val month = currentMonthPeriod()
        transaction(db) {
            MonthlyLeaderboardEntries.insert { it[userId] = near; it[monthPeriod] = month; it[monthlyPoints] = 15 }
            MonthlyLeaderboardEntries.insert { it[userId] = far; it[monthPeriod] = month; it[monthlyPoints] = 5 }
            MonthlyLeaderboardEntries.insert { it[userId] = ahead; it[monthPeriod] = month; it[monthlyPoints] = 100_000 }
            MonthlyLeaderboardEntries.insert { it[userId] = stranger; it[monthPeriod] = month; it[monthlyPoints] = 10 }
        }
        befriend(me, near)
        befriend(me, far)
        befriend(me, ahead)
        unlockAndVerifyEverything(entry)
        caller = me

        // the completion earns more than 15 points, so friends Near (15) and Far (5) are passed and Ahead is not.
        // Stranger (10) is not a friend, so isn't on the board and isn't counted as passed
        completeLibraryEntry(me, entry, CompletionType.COMPLETIONIST, SteamCheck(SteamCheckStatus.NOT_NEEDED))

        val passed = events(client.get("/activity").bodyAsText()).events.single { it.type == ActivityType.RANK_PASSED }
        assertEquals("Near", passed.subjectName)
        assertEquals(2, passed.count)
    }

    @Test
    fun `new achievements become one event naming the rarest, and nothing new is no event`() = testApplication {
        installApi()
        seedGame()
        val me = newUser("Me")
        val entry = addEntry(me)
        caller = me

        transaction(db) { updateEntry(me, entry, UpdateEntryRequest(unlockedAchievementIds = listOf("COMMON", "RARE"))) }
        transaction(db) { updateEntry(me, entry, UpdateEntryRequest(unlockedAchievementIds = listOf("COMMON", "RARE"))) }
        transaction(db) { updateEntry(me, entry, UpdateEntryRequest(hoursPlayed = 3.0)) }

        val unlocked = events(client.get("/activity").bodyAsText()).events.single()
        assertEquals(ActivityType.ACHIEVEMENT_UNLOCKED, unlocked.type)
        assertEquals(2, unlocked.count)
        assertEquals("Rare One", unlocked.detail)
        assertEquals("Hades", unlocked.gameTitle)
    }

    @Test
    fun `accepting a friend request tells both people`() = testApplication {
        installApi()
        val sender = newUser("Sender")
        val accepter = newUser("Accepter")
        befriend(sender, accepter)

        caller = sender
        val seenBySender = events(client.get("/activity").bodyAsText()).events.single()
        caller = accepter
        val seenByAccepter = events(client.get("/activity").bodyAsText()).events.single()

        assertEquals(ActivityType.FRIEND_ADDED, seenBySender.type)
        assertEquals("Accepter", seenBySender.actorName)
        assertEquals("Sender", seenBySender.subjectName)
        assertEquals(seenBySender.eventId, seenByAccepter.eventId)
    }
}
//------------------------------EOF------------------------------\\
