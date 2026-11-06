package com.backlogbattlers.app.data.remote

import android.util.Log
import com.backlogbattlers.app.data.repository.AccessTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

// dto representing one ranked row returned by the backend
@Serializable
data class LeaderboardEntryDto(
    val rank: Int,
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val monthlyPoints: Int,
    val pictureId: String? = null,
)

// dto representing the full GET /leaderboard/monthly response
@Serializable
data class LeaderboardResponseDto(
    val monthPeriod: String,
    val seasonEndsAt: Long,
    val entries: List<LeaderboardEntryDto>,
    val me: LeaderboardEntryDto?,
)

// http client wrapping the backend's monthly leaderboard endpoint. the board is the signed in user
// and their friends (friends on 0 points included), so the bearer header is what decides who is on it
class LeaderboardApi(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val tokens: AccessTokenSource,
) {
    //------------------------------
    // fetches this month's leaderboard, with the signed in user's own row pinned in `me`
    suspend fun getMonthlyLeaderboard(): LeaderboardResponseDto = tokens.call { token ->
        val response = httpClient.get("$baseUrl/leaderboard/monthly") {
            accept(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        response.checkedBody()
    }

    //------------------------------
    // decodes a successful response body, or throws with the servers error text.
    // a 401 is SessionExpiredException, which is what makes AccessTokenSource renew the token and retry
    private suspend inline fun <reified T> HttpResponse.checkedBody(): T {
        if (status == HttpStatusCode.Unauthorized) throw SessionExpiredException()
        if (!status.isSuccess()) {
            val errorText = runCatching { body<String>() }.getOrDefault("HTTP ${status.value}")
            Log.e(TAG, "${call.request.url.encodedPath} failed [${status.value}]: $errorText")
            throw IllegalStateException("server returned HTTP ${status.value}: $errorText")
        }
        return body()
    }

    private companion object {
        const val TAG = "LeaderboardApi"
    }
}
//------------------------------EOF------------------------------\\
