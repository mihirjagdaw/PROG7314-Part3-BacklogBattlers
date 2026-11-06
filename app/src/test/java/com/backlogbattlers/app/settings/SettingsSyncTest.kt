package com.backlogbattlers.app.settings

import com.backlogbattlers.app.data.remote.SettingsApi
import com.backlogbattlers.app.data.repository.SettingsSync
import com.backlogbattlers.app.domain.model.AppLanguage
import com.backlogbattlers.app.domain.model.HomeBackdrop
import com.backlogbattlers.app.domain.model.Platform
import com.backlogbattlers.app.domain.model.ThemeMode
import com.backlogbattlers.app.domain.model.UserSettings
import com.backlogbattlers.app.search.FakeSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// keeping this phone's settings and the account's in step
class SettingsSyncTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    // what the account has saved on the server: Afrikaans, light, on PlayStation
    private val savedOnAccount = """
        {"language":"af","themeMode":"LIGHT","homeBackdrop":"COAST","defaultPlatform":"PLAYSTATION",
         "achievementNotifications":true,"rankChangeNotifications":false,
         "seasonResetNotifications":true,"friendActivityNotifications":false,"updatedAt":1761030000000}
    """.trimIndent()

    private val settings = FakeSettingsRepository()
    private val seen = mutableListOf<HttpRequestData>()
    private var applied: Pair<UserSettings, UserSettings>? = null
    private var signedIn = true

    // the server: GET answers [getStatus] with [getBody]; PUT echoes the body back, or fails with [putFailure]
    private var getStatus = HttpStatusCode.OK
    private var getBody = savedOnAccount
    private var putFailure: Throwable? = null

    private fun sync(): SettingsSync {
        val client = HttpClient(
            MockEngine { request ->
                seen += request
                when (request.method) {
                    HttpMethod.Get -> if (getStatus == HttpStatusCode.OK) respond(getBody, getStatus, jsonHeaders) else respondError(getStatus)
                    else -> {
                        putFailure?.let { throw it }
                        respond((request.body as TextContent).text, HttpStatusCode.OK, jsonHeaders)
                    }
                }
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return SettingsSync(
            settingsRepository = settings,
            settingsApi = SettingsApi(client, baseUrl = "https://api.example.test"),
            accessToken = { if (signedIn) "token-123" else null },
            applyToApp = { before, after -> applied = before to after },
        )
    }

    private fun sentBody() = Json.parseToJsonElement((seen.last().body as TextContent).text).jsonObject

    @Test
    fun `signing in on another phone brings the account's settings down and applies them`() = runBlocking {
        settings.settings.value = UserSettings(biometricLoginEnabled = true)

        sync().sync()

        val now = settings.settings.value
        assertEquals(AppLanguage.AFRIKAANS, now.language)
        assertEquals(ThemeMode.LIGHT, now.themeMode)
        assertEquals(HomeBackdrop.COAST, now.homeBackdrop)
        assertEquals(Platform.PLAYSTATION, now.defaultPlatform)
        assertFalse(now.rankChangeNotificationsEnabled)
        // the fingerprint lock belongs to this phone, so the account's settings never touch it
        assertTrue(now.biometricLoginEnabled)
        // the theme and language were handed over to be applied to the whole app
        assertEquals(ThemeMode.DARK, applied!!.first.themeMode)
        assertEquals(ThemeMode.LIGHT, applied!!.second.themeMode)
        assertEquals("Bearer token-123", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `nothing is reapplied when the phone already matches the account`() = runBlocking {
        sync().sync()
        applied = null

        sync().sync()

        assertNull(applied)
    }

    @Test
    fun `an account with no saved settings gets this phone's`() = runBlocking {
        getStatus = HttpStatusCode.NotFound
        settings.settings.value = UserSettings(language = AppLanguage.ZULU, defaultPlatform = Platform.XBOX)

        sync().sync()

        assertEquals(listOf(HttpMethod.Get, HttpMethod.Put), seen.map { it.method })
        val body = sentBody()
        assertEquals("zu", body["language"]!!.jsonPrimitive.content)
        assertEquals("XBOX", body["defaultPlatform"]!!.jsonPrimitive.content)
        assertTrue(body["achievementNotifications"]!!.jsonPrimitive.boolean)
        // the fingerprint lock is never sent
        assertFalse(body.containsKey("biometricLoginEnabled"))
    }

    @Test
    fun `a change made offline is kept and uploaded on the next sync, not overwritten`() = runBlocking {
        settings.settings.value = UserSettings(themeMode = ThemeMode.LIGHT)
        putFailure = IOException("no connection")

        assertFalse(sync().push())
        assertTrue(settings.syncPending)

        // back online: the next sync uploads the waiting change instead of pulling the account's
        putFailure = null
        seen.clear()
        sync().sync()

        assertEquals(listOf(HttpMethod.Put), seen.map { it.method })
        assertEquals("LIGHT", sentBody()["themeMode"]!!.jsonPrimitive.content)
        assertFalse(settings.syncPending)
        assertNull(applied)
    }

    @Test
    fun `a server that can't be reached leaves this phone's settings as they are`() = runBlocking {
        getStatus = HttpStatusCode.InternalServerError
        val before = UserSettings(homeBackdrop = HomeBackdrop.FOREST)
        settings.settings.value = before

        sync().sync()

        assertEquals(before, settings.settings.value)
        assertNull(applied)
    }

    @Test
    fun `the two profile section switches are uploaded, and are sent even when they are on`() = runBlocking {
        settings.settings.value = UserSettings(showBadges = false, showShowcase = true)

        sync().push()

        val body = sentBody()
        assertFalse(body["showBadges"]!!.jsonPrimitive.boolean)
        // an "on" must reach the account too, or turning a section back on would never be saved
        assertTrue(body["showShowcase"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `the switches come down with the account's settings on another phone`() = runBlocking {
        getBody = savedOnAccount.replace("\"updatedAt\"", "\"showBadges\":false,\"showShowcase\":true,\"updatedAt\"")

        sync().sync()

        assertFalse(settings.settings.value.showBadges)
        assertTrue(settings.settings.value.showShowcase)
    }

    @Test
    fun `an account that has never saved the switches has both on`() = runBlocking {
        settings.settings.value = UserSettings(showBadges = false, showShowcase = false)

        sync().sync() // savedOnAccount has no switches in it, as an older server would answer

        assertTrue(settings.settings.value.showBadges)
        assertTrue(settings.settings.value.showShowcase)
    }

    @Test
    fun `nothing is sent when nobody is signed in`() = runBlocking {
        signedIn = false

        sync().sync()
        sync().push()

        assertTrue(seen.isEmpty())
        assertFalse(settings.syncPending)
    }
}
//------------------------------EOF------------------------------\\
