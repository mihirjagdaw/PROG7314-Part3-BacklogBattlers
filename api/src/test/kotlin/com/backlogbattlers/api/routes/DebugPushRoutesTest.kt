package com.backlogbattlers.api.routes

import com.backlogbattlers.api.configureErrors
import com.backlogbattlers.api.db.DatabaseFactory
import com.backlogbattlers.api.db.Devices
import com.backlogbattlers.api.db.Platform
import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.notifications.Notifier
import com.backlogbattlers.api.notifications.PushSender
import com.backlogbattlers.api.notifications.PushType
import com.backlogbattlers.api.notifications.samplePush
import com.backlogbattlers.api.settings.SettingsDto
import com.backlogbattlers.api.settings.saveSettings
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The debug route that fires each kind of push on demand: off by default, and only ever to the caller. */
class DebugPushRoutesTest {

    private val db = DatabaseFactory.init(jdbcUrl = null, memoryName = "debug-push-${Uuid.random()}")
    private var caller: Uuid? = null

    private data class Sent(val tokens: List<String>, val data: Map<String, String>)

    private val sent = mutableListOf<Sent>()
    private val notifier = Notifier(PushSender { tokens, data -> sent += Sent(tokens, data); emptyList() }, launch = Notifier.inline)

    private fun ApplicationTestBuilder.installApi(enabled: Boolean) {
        application {
            install(ContentNegotiation) { json() }
            configureErrors()
            routing { debugPushRoutes(notifier, enabled) { caller } }
        }
    }

    private fun newUser(vararg tokens: String): Uuid = transaction(db) {
        val id = Users.insert {
            it[googleSubjectId] = "google-${Uuid.random()}"
            it[displayName] = "Ana"
            it[email] = "ana@example.com"
            it[lastLoginDate] = LocalDate.now()
        }[Users.id]
        tokens.forEach { token -> Devices.insert { it[Devices.token] = token; it[userId] = id } }
        id
    }

    private suspend fun HttpClient.debugPush(body: String): HttpResponse =
        post("/debug/push") { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun results(response: HttpResponse): List<DebugPushResult> =
        Json.decodeFromString(response.bodyAsText())

    @Test
    fun `there is no debug route unless it was switched on`() = testApplication {
        installApi(enabled = false)
        caller = newUser("ana-phone")

        assertEquals(HttpStatusCode.NotFound, client.debugPush("""{"type":"ALL"}""").status)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `all sends one push of every kind to the caller's own phones`() = testApplication {
        installApi(enabled = true)
        caller = newUser("ana-phone", "ana-tablet")

        val response = client.debugPush("""{"type":"ALL"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(PushType.entries.map { it.name }, results(response).map { it.type })
        assertTrue(results(response).all { it.phones == 2 })
        assertEquals(PushType.entries.map { it.name }, sent.map { it.data["type"] })
        assertTrue(sent.all { it.tokens.toSet() == setOf("ana-phone", "ana-tablet") && it.data["userId"] == caller.toString() })
    }

    @Test
    fun `all is the default when no type is given`() = testApplication {
        installApi(enabled = true)
        caller = newUser("ana-phone")

        client.debugPush("{}")

        assertEquals(PushType.entries.size, sent.size)
    }

    @Test
    fun `one kind can be sent by name, in any letter case`() = testApplication {
        installApi(enabled = true)
        caller = newUser("ana-phone")

        val response = client.debugPush("""{"type":"season_reset"}""")

        assertEquals(listOf(DebugPushResult("SEASON_RESET", 1)), results(response))
        assertEquals("SEASON_RESET", sent.single().data["type"])
        assertEquals("2", sent.single().data["lastRank"])
    }

    @Test
    fun `an unknown kind is a 400 that lists the kinds`() = testApplication {
        installApi(enabled = true)
        caller = newUser("ana-phone")

        val response = client.debugPush("""{"type":"BANANA"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(PushType.entries.all { it.name in response.bodyAsText() })
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `a kind the player switched off is not sent, and reports no phones`() = testApplication {
        installApi(enabled = true)
        caller = newUser("ana-phone")
        saveSettings(
            caller!!,
            SettingsDto("en", "DARK", "HILLS", Platform.PC, achievementNotifications = false, rankChangeNotifications = true, seasonResetNotifications = true, friendActivityNotifications = true),
        )

        val all = results(client.debugPush("""{"type":"ALL"}"""))

        assertEquals(0, all.single { it.type == "ACHIEVEMENT_UNLOCKED" }.phones)
        assertFalse(sent.any { it.data["type"] == "ACHIEVEMENT_UNLOCKED" })
        assertEquals(PushType.entries.size - 1, sent.size)
    }

    @Test
    fun `a player with no phone signed in sends nothing and reports no phones`() = testApplication {
        installApi(enabled = true)
        caller = newUser()

        val all = results(client.debugPush("""{"type":"ALL"}"""))

        assertTrue(all.all { it.phones == 0 })
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `nobody signed in is a 401 and sends nothing`() = testApplication {
        installApi(enabled = true)
        caller = null

        assertEquals(HttpStatusCode.Unauthorized, client.debugPush("""{"type":"ALL"}""").status)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `the sample for every kind is something the app can show`() {
        // the app drops a push about a friend with no name, and one about achievements with no count
        PushType.entries.forEach { type ->
            val data = samplePush(type).data(Uuid.random())
            assertEquals(type.name, data["type"])
            if (type in setOf(PushType.FRIEND_REQUEST_RECEIVED, PushType.FRIEND_REQUEST_ACCEPTED, PushType.FRIEND_COMPLETED_GAME, PushType.OVERTAKEN)) {
                assertTrue(data["actorName"].orEmpty().isNotBlank(), "$type needs a name")
            }
        }
        assertEquals("3", samplePush(PushType.ACHIEVEMENT_UNLOCKED).data(Uuid.random())["count"])
    }
}
//------------------------------EOF------------------------------\\
