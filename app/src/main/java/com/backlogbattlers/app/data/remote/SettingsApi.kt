package com.backlogbattlers.app.data.remote

import android.util.Log
import com.backlogbattlers.app.domain.model.Platform
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The settings saved to the account, as `GET`/`PUT /users/me/settings` send them. The language is
 * a locale tag (en, af, zu); the theme and backdrop are the app's enum names. The fingerprint lock
 * is not here: it belongs to one phone, not the account.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SettingsDto(
    val language: String,
    val themeMode: String,
    val homeBackdrop: String,
    val defaultPlatform: Platform,
    val achievementNotifications: Boolean,
    val rankChangeNotifications: Boolean,
    val seasonResetNotifications: Boolean,
    val friendActivityNotifications: Boolean,
    // whether friends see this user's badges and showcase when they open their profile. always sent,
    // even when on (the default), so turning one back on reaches the account. an older server that does
    // not send them leaves both on
    @EncodeDefault val showBadges: Boolean = true,
    @EncodeDefault val showShowcase: Boolean = true,
    // epoch milliseconds of the last save, set by the server
    val updatedAt: Long? = null,
)

/**
 * API client for the settings that follow the user from phone to phone.
 */
class SettingsApi(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {

    //------------------------------
    // GETs the saved settings, or null when the account has never saved any (a 404)
    suspend fun getSettings(accessToken: String): SettingsDto? {
        val response = httpClient.get("$baseUrl/users/me/settings") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            accept(ContentType.Application.Json)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        return response.bodyOrThrow()
    }

    //------------------------------
    // PUTs every setting at once, replacing what the account had, and returns what was stored
    suspend fun putSettings(accessToken: String, settings: SettingsDto): SettingsDto {
        val response = httpClient.put("$baseUrl/users/me/settings") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(settings)
        }
        return response.bodyOrThrow()
    }

    //------------------------------
    // decodes a successful response; a 401 means the session is no longer valid
    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (status == HttpStatusCode.Unauthorized) throw SessionExpiredException()
        if (!status.isSuccess()) {
            val errorText = runCatching { body<String>() }.getOrDefault("HTTP ${status.value}")
            Log.e("SettingsApi", "Settings request failed [${status.value}]: $errorText")
            throw IllegalStateException("Server returned HTTP ${status.value}: $errorText")
        }
        return body()
    }
}
//------------------------------EOF------------------------------\\
