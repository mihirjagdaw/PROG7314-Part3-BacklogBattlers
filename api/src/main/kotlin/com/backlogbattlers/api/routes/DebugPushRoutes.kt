package com.backlogbattlers.api.routes

import com.backlogbattlers.api.ApiError
import com.backlogbattlers.api.CurrentUser
import com.backlogbattlers.api.notifications.Notifier
import com.backlogbattlers.api.notifications.PushType
import com.backlogbattlers.api.notifications.samplePush
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

private val log = LoggerFactory.getLogger("com.backlogbattlers.api.routes.DebugPushRoutes")

/** What to send: one [PushType] by name, or `ALL` (the default) for one of each kind. */
@Serializable
data class DebugPushRequest(val type: String = "ALL")

/** One push the route sent, and how many of the caller's phones it went to (0: switched off, or no phone signed in). */
@Serializable
data class DebugPushResult(val type: String, val phones: Int)

/**
 * A way to see every kind of push arrive on demand, for testing and for recording the demo video,
 * without waiting for a friend to act or for the month to change.
 *
 * Only registered when [enabled] is true, which the server sets with the DEBUG_PUSH_ENABLED
 * setting. Otherwise there is no such route (404). It sends only to the signed-in caller's own
 * phones, with made-up names and numbers, and goes through the same [Notifier.deliver] as a real
 * push, so the Settings switches are still honoured: switch Achievements off and that push is not
 * sent.
 */
fun Route.debugPushRoutes(notifier: Notifier, enabled: Boolean, currentUser: CurrentUser) {
    if (!enabled) return
    log.warn("DEBUG_PUSH_ENABLED is on: POST /debug/push sends test pushes. Turn it off after testing")

    // POST /debug/push   {"type": "SEASON_RESET"}   or   {"type": "ALL"} (the default)
    post("/debug/push") {
        val me = call.signedInUser(currentUser) ?: return@post
        val requested = call.receive<DebugPushRequest>().type.trim().uppercase()

        val types = when (requested) {
            "ALL" -> PushType.entries
            else -> listOfNotNull(PushType.entries.firstOrNull { it.name == requested })
        }
        if (types.isEmpty()) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("type must be ALL or one of ${PushType.entries.joinToString { it.name }}"),
            )
        }

        val results = types.map { type -> DebugPushResult(type.name, notifier.deliver(me, samplePush(type))) }
        log.info("Sent test pushes to {}: {}", me, results.joinToString { "${it.type}=${it.phones}" })
        call.respond(results)
    }
}

// Each routes file in this package keeps its own file-private copy of this helper on purpose,
// see CompletionRoutes.kt.

/** The caller's id, or null after answering 401 (so the route just returns). */
private suspend fun ApplicationCall.signedInUser(currentUser: CurrentUser): Uuid? {
    val user = currentUser(this)
    if (user == null) respond(HttpStatusCode.Unauthorized, ApiError("Sign in to send test pushes"))
    return user
}
//------------------------------EOF------------------------------\\
