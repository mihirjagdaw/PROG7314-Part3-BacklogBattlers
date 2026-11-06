package com.backlogbattlers.api.routes

import com.backlogbattlers.api.ApiError
import com.backlogbattlers.api.CurrentUser
import com.backlogbattlers.api.profile.profileFor
import com.backlogbattlers.api.profile.profileForViewer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.uuid.Uuid

/** The signed-in user's profile: level, stats, badges and showcases, all worked out from their data. */
fun Route.profileRoutes(currentUser: CurrentUser) {

    // GET /users/me/profile
    get("/users/me/profile") {
        val me = call.signedInUser(currentUser) ?: return@get
        val profile = profileFor(me)
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Account was not found"))
        call.respond(profile)
    }

    // GET /users/{id}/profile
    // A friend's profile (or your own by id): the same answer as your own, minus the badges and the
    // showcase when they switched those off, and flagged as hidden. Anyone who is not a friend, and
    // an id that does not exist, both get the same 404, so it never says who has an account
    get("/users/{id}/profile") {
        val me = call.signedInUser(currentUser) ?: return@get
        val target = runCatching { Uuid.parse(call.parameters["id"].orEmpty()) }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("User id is not valid"))
        val profile = profileForViewer(me, target)
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("Profile was not found"))
        call.respond(profile)
    }
}

// Each routes file in this package keeps its own file-private copy of this helper on purpose,
// see CompletionRoutes.kt.

/** The caller's id, or null after answering 401 (so the route just returns). */
private suspend fun ApplicationCall.signedInUser(currentUser: CurrentUser): Uuid? {
    val user = currentUser(this)
    if (user == null) respond(HttpStatusCode.Unauthorized, ApiError("Sign in to see your profile"))
    return user
}
//------------------------------EOF------------------------------\
