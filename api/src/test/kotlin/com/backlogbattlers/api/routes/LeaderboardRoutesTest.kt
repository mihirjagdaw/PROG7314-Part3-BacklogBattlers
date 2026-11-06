package com.backlogbattlers.api.routes

import com.backlogbattlers.api.configureErrors
import com.backlogbattlers.api.db.DatabaseFactory
import com.backlogbattlers.api.db.Friendships
import com.backlogbattlers.api.db.MonthlyLeaderboardEntries
import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.scoring.LeaderboardResponseDto
import com.backlogbattlers.api.scoring.currentMonthPeriod
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** GET /leaderboard/monthly, end to end. The route reads the real current month, so seeded points
 * use it too. The board is the signed-in caller and their friends. */
class LeaderboardRoutesTest {

    private val db = DatabaseFactory.init(jdbcUrl = null, memoryName = "leaderboard-routes-${Uuid.random()}")
    private var caller: Uuid? = null

    private fun ApplicationTestBuilder.installApi() {
        application {
            install(ContentNegotiation) { json() }
            configureErrors()
            routing { leaderboardRoutes { caller } }
        }
    }

    private fun newUser(name: String): Uuid = transaction(db) {
        Users.insert {
            it[googleSubjectId] = "google-${Uuid.random()}"
            it[displayName] = name
            it[email] = "$name@example.com"
            it[lastLoginDate] = LocalDate.now()
        }[Users.id]
    }

    // The route reads the real current month by default, so seeded rows must use it too
    private fun givePoints(user: Uuid, points: Int, monthPeriod: String = currentMonthPeriod()) = transaction(db) {
        MonthlyLeaderboardEntries.insert {
            it[userId] = user
            it[MonthlyLeaderboardEntries.monthPeriod] = monthPeriod
            it[monthlyPoints] = points
        }
    }

    // friendships are stored once per pair, with the smaller id (as a string) first
    private fun befriend(a: Uuid, b: Uuid) = transaction(db) {
        val (first, second) = if (a.toString() < b.toString()) a to b else b to a
        Friendships.insert {
            it[userAId] = first
            it[userBId] = second
        }
    }

    private suspend fun ApplicationTestBuilder.board(query: String = ""): LeaderboardResponseDto =
        Json.decodeFromString(client.get("/leaderboard/monthly$query").bodyAsText())

    @Test
    fun `without signing in the board is empty, not an error`() = testApplication {
        installApi()
        givePoints(newUser("Alice"), 100)
        caller = null

        val response = client.get("/leaderboard/monthly")

        assertEquals(HttpStatusCode.OK, response.status)
        val board = Json.decodeFromString<LeaderboardResponseDto>(response.bodyAsText())
        assertEquals(emptyList(), board.entries)
        assertNull(board.me)
    }

    @Test
    fun `a signed-in caller sees only their friends, including ones on zero, and their own row`() = testApplication {
        installApi()
        val me = newUser("Me")
        val leader = newUser("Leader")
        val quiet = newUser("Quiet")
        val stranger = newUser("Stranger")
        befriend(me, leader)
        befriend(me, quiet)
        givePoints(leader, 900)
        givePoints(me, 50)
        givePoints(stranger, 5_000)
        caller = me

        val board = board()

        assertEquals(listOf("Leader", "Me", "Quiet"), board.entries.map { it.displayName })
        assertEquals(listOf(900, 50, 0), board.entries.map { it.monthlyPoints })
        assertEquals("Me", board.me?.displayName)
        assertEquals(2, board.me?.rank)
    }

    @Test
    fun `entries are ordered highest first and tied scores share a rank`() = testApplication {
        installApi()
        val me = newUser("Me")
        val first = newUser("First")
        val secondA = newUser("SecondA")
        val secondB = newUser("SecondB")
        listOf(first, secondA, secondB).forEach { befriend(me, it) }
        givePoints(first, 300)
        givePoints(secondA, 200)
        givePoints(secondB, 200)
        givePoints(me, 100)
        caller = me

        val board = board()

        assertEquals(listOf(1, 2, 2, 4), board.entries.map { it.rank })
        assertEquals(listOf("First", "SecondA", "SecondB", "Me"), board.entries.map { it.displayName })
    }

    @Test
    fun `limit trims the page but the season and me still come through`() = testApplication {
        installApi()
        val me = newUser("Caller")
        repeat(5) { val friend = newUser("Player$it"); befriend(me, friend); givePoints(friend, 100 - it) }
        givePoints(me, 1)
        caller = me

        val board = board("?limit=2")

        assertEquals(2, board.entries.size)
        assertEquals("Caller", board.me?.displayName)
        assertEquals(6, board.me?.rank)
        assertEquals(currentMonthPeriod(), board.monthPeriod)
    }

    @Test
    fun `an invalid limit is a 400, but the parameter is optional`() = testApplication {
        installApi()

        assertEquals(HttpStatusCode.OK, client.get("/leaderboard/monthly").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/leaderboard/monthly?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/leaderboard/monthly?limit=abc").status)
    }
}
