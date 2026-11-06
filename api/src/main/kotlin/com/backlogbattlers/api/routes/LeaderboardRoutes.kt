package com.backlogbattlers.api.routes

import com.backlogbattlers.api.ApiError
import com.backlogbattlers.api.CurrentUser
import com.backlogbattlers.api.scoring.MAX_LEADERBOARD_LIMIT
import com.backlogbattlers.api.scoring.monthlyLeaderboard
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * This month's standings between the signed-in caller and their friends, including friends who
 * have not scored yet. The caller's own row is also pinned in `me`, even when it falls outside
 * the returned page. With nobody signed in the board is simply empty.
 */
fun Route.leaderboardRoutes(currentUser: CurrentUser) {

    // GET /leaderboard/monthly?limit=20
    get("/leaderboard/monthly") {
        val limitParam = call.request.queryParameters["limit"]
        val limit = if (limitParam == null) null else limitParam.toIntOrNull()
        if (limitParam != null && (limit == null || limit < 1)) {
            return@get call.respond(HttpStatusCode.BadRequest, ApiError("limit must be a whole number of 1 or more"))
        }

        val caller = currentUser(call) // null (not signed in) gets an empty board rather than an error
        call.respond(monthlyLeaderboard(caller, limit = (limit ?: 20).coerceAtMost(MAX_LEADERBOARD_LIMIT)))
    }
}
