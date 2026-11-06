package com.backlogbattlers.api.routes

import com.backlogbattlers.api.configureErrors
import com.backlogbattlers.api.db.Achievements
import com.backlogbattlers.api.db.DatabaseFactory
import com.backlogbattlers.api.db.FriendRequests
import com.backlogbattlers.api.db.Friendships
import com.backlogbattlers.api.db.Games
import com.backlogbattlers.api.db.LibraryEntries
import com.backlogbattlers.api.db.Platform
import com.backlogbattlers.api.db.UnlockedAchievements
import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.profile.ProfileDto
import com.backlogbattlers.api.settings.SettingsDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
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

/**
 * A friend's profile: who may open it, what they see, and the "show my badges" and "show my showcase"
 * switches that are saved with the settings and honoured here by the server.
 */
class FriendProfileRoutesTest {

    private val db = DatabaseFactory.init(jdbcUrl = null, memoryName = "friend-profile-${Uuid.random()}")
    private var caller: Uuid? = null

    private fun ApplicationTestBuilder.installApi() {
        application {
            install(ContentNegotiation) { json() }
            configureErrors()
            routing {
                profileRoutes { caller }
                settingsRoutes { caller }
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

    private fun befriend(a: Uuid, b: Uuid) = transaction(db) {
        val (first, second) = if (a.toString() < b.toString()) a to b else b to a
        Friendships.insert { it[userAId] = first; it[userBId] = second }
    }

    /** Hades with two achievements, both unlocked by [owner], so the badges and the showcase have something in them. */
    private fun unlockTwo(owner: Uuid) = transaction(db) {
        Games.insert { it[id] = 1; it[title] = "Hades" }
        listOf("A1" to 40.0, "A2" to 2.0).forEach { (code, rarity) ->
            Achievements.insert { it[gameId] = 1; it[achievementId] = code; it[name] = "$code name"; it[rarityPercent] = rarity }
        }
        val entry = LibraryEntries.insert { it[userId] = owner; it[gameId] = 1; it[platform] = Platform.PC }[LibraryEntries.id]
        listOf("A1", "A2").forEach { code -> UnlockedAchievements.insert { it[libraryEntryId] = entry; it[achievementId] = code } }
    }

    private fun settings(showBadges: Boolean? = null, showShowcase: Boolean? = null): String {
        val shown = listOfNotNull(
            showBadges?.let { "\"showBadges\":$it" },
            showShowcase?.let { "\"showShowcase\":$it" },
        )
        val extra = if (shown.isEmpty()) "" else "," + shown.joinToString(",")
        return """{"language":"en","themeMode":"DARK","homeBackdrop":"HILLS","defaultPlatform":"PC",
            "achievementNotifications":true,"rankChangeNotifications":true,"seasonResetNotifications":true,
            "friendActivityNotifications":true$extra}"""
    }

    private suspend fun HttpClient.saveSettings(body: String): HttpResponse =
        put("/users/me/settings") { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.profileOf(id: Uuid): ProfileDto {
        val response = get("/users/$id/profile")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.decodeFromString(response.bodyAsText())
    }

    // ---- who may open it ----

    @Test
    fun `a profile needs a signed in user`() = testApplication {
        installApi()
        caller = null

        assertEquals(HttpStatusCode.Unauthorized, client.get("/users/${Uuid.random()}/profile").status)
    }

    @Test
    fun `an id that is not an id is a 400`() = testApplication {
        installApi()
        caller = newUser("Ana")

        assertEquals(HttpStatusCode.BadRequest, client.get("/users/not-an-id/profile").status)
    }

    @Test
    fun `a friend's profile is the same answer as their own, with their name and level`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben")
        befriend(ana, ben)
        unlockTwo(ben)
        caller = ana

        val profile = client.profileOf(ben)

        assertEquals(ben.toString(), profile.userId)
        assertEquals("Ben", profile.displayName)
        assertEquals(2, profile.achievementsUnlocked)
        assertEquals(listOf("A2", "A1"), profile.rarestAchievements.map { it.achievementId }) // rarest first
        assertTrue(profile.badges.isNotEmpty())
        assertFalse(profile.badgesHidden)
        assertFalse(profile.showcaseHidden)
    }

    @Test
    fun `someone who is not a friend is a 404, the same as a user who does not exist`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val stranger = newUser("Stranger")
        caller = ana

        val notFriend = client.get("/users/$stranger/profile")
        val nobody = client.get("/users/${Uuid.random()}/profile")

        assertEquals(HttpStatusCode.NotFound, notFriend.status)
        assertEquals(HttpStatusCode.NotFound, nobody.status)
        assertEquals(nobody.bodyAsText(), notFriend.bodyAsText()) // it never says who has an account
    }

    @Test
    fun `a friend request that has not been accepted does not open a profile`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben")
        transaction(db) { FriendRequests.insert { it[senderId] = ana; it[receiverId] = ben } }
        caller = ana

        assertEquals(HttpStatusCode.NotFound, client.get("/users/$ben/profile").status)
    }

    @Test
    fun `friendship works both ways round`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben")
        befriend(ana, ben)

        caller = ben
        assertEquals("Ana", client.profileOf(ana).displayName)
        caller = ana
        assertEquals("Ben", client.profileOf(ben).displayName)
    }

    @Test
    fun `your own profile by id is shown in full, whatever you switched off`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        unlockTwo(ana)
        caller = ana
        client.saveSettings(settings(showBadges = false, showShowcase = false))

        val profile = client.profileOf(ana)

        assertTrue(profile.badges.isNotEmpty())
        assertEquals(2, profile.rarestAchievements.size)
        assertFalse(profile.badgesHidden)
        assertFalse(profile.showcaseHidden)
    }

    // ---- the two switches ----

    @Test
    fun `a friend who turned badges off shows none, and the profile says they are hidden`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben")
        befriend(ana, ben)
        unlockTwo(ben)
        caller = ben
        client.saveSettings(settings(showBadges = false))

        caller = ana
        val profile = client.profileOf(ben)

        assertEquals(emptyList(), profile.badges)
        assertTrue(profile.badgesHidden)
        assertEquals(2, profile.rarestAchievements.size) // the showcase is a separate switch
        assertFalse(profile.showcaseHidden)
    }

    @Test
    fun `a friend who turned the showcase off shows none, and the profile says it is hidden`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben")
        befriend(ana, ben)
        unlockTwo(ben)
        caller = ben
        client.saveSettings(settings(showShowcase = false))

        caller = ana
        val profile = client.profileOf(ben)

        assertEquals(emptyList(), profile.rarestAchievements)
        assertTrue(profile.showcaseHidden)
        assertTrue(profile.badges.isNotEmpty())
        assertFalse(profile.badgesHidden)
    }

    @Test
    fun `what friends always see stays visible when both sections are hidden`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben")
        befriend(ana, ben)
        unlockTwo(ben)
        caller = ben
        client.saveSettings(settings(showBadges = false, showShowcase = false))

        caller = ana
        val profile = client.profileOf(ben)

        assertEquals("Ben", profile.displayName)
        assertEquals(1, profile.level)
        assertEquals(2, profile.achievementsUnlocked)
        assertEquals(1, profile.gamesInLibrary)
        assertEquals(1, profile.recentActivity.size)
    }

    @Test
    fun `turning a section back on shows it again`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben")
        befriend(ana, ben)
        unlockTwo(ben)
        caller = ben
        client.saveSettings(settings(showBadges = false))
        client.saveSettings(settings(showBadges = true))

        caller = ana
        val profile = client.profileOf(ben)

        assertTrue(profile.badges.isNotEmpty())
        assertFalse(profile.badgesHidden)
    }

    @Test
    fun `a friend who never saved settings shows everything`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben")
        befriend(ana, ben)
        unlockTwo(ben)
        caller = ana

        val profile = client.profileOf(ben)

        assertFalse(profile.badgesHidden)
        assertFalse(profile.showcaseHidden)
        assertEquals(2, profile.rarestAchievements.size)
    }

    // ---- the switches are part of the synced settings ----

    @Test
    fun `the two switches are saved with the settings and come back on any phone`() = testApplication {
        installApi()
        caller = newUser("Ana")

        client.saveSettings(settings(showBadges = false, showShowcase = true))
        val saved = Json.decodeFromString<SettingsDto>(client.get("/users/me/settings").bodyAsText())

        assertFalse(saved.showBadges)
        assertTrue(saved.showShowcase)
    }

    @Test
    fun `an app that does not send the switches leaves both on`() = testApplication {
        installApi()
        caller = newUser("Ana")

        val response = client.saveSettings(settings())
        val saved = Json.decodeFromString<SettingsDto>(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(saved.showBadges)
        assertTrue(saved.showShowcase)
    }

    @Test
    fun `each user's switches are their own`() = testApplication {
        installApi()
        val ana = newUser("Ana")
        val ben = newUser("Ben")
        caller = ana
        client.saveSettings(settings(showBadges = false))
        caller = ben
        client.saveSettings(settings(showBadges = true))

        caller = ana
        assertFalse(Json.decodeFromString<SettingsDto>(client.get("/users/me/settings").bodyAsText()).showBadges)
        caller = ben
        assertTrue(Json.decodeFromString<SettingsDto>(client.get("/users/me/settings").bodyAsText()).showBadges)
    }
}
//------------------------------EOF------------------------------\\
