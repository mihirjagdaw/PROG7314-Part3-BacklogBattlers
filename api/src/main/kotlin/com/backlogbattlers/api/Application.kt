package com.backlogbattlers.api

import com.backlogbattlers.api.auth.GoogleIdTokenVerifier
import com.backlogbattlers.api.auth.JwtService
import com.backlogbattlers.api.auth.bearerCurrentUser
import com.backlogbattlers.api.db.DatabaseFactory
import com.backlogbattlers.api.logging.configureRequestLogging
import com.backlogbattlers.api.games.IgdbClient
import com.backlogbattlers.api.games.SteamClient
import com.backlogbattlers.api.games.igdbHttpClient
import com.backlogbattlers.api.notifications.Notifier
import com.backlogbattlers.api.notifications.PushSender
import com.backlogbattlers.api.notifications.SeasonAnnouncer
import com.backlogbattlers.api.routes.accountRoutes
import com.backlogbattlers.api.routes.activityRoutes
import com.backlogbattlers.api.routes.achievementRoutes
import com.backlogbattlers.api.routes.authRoutes
import com.backlogbattlers.api.routes.friendRoutes
import com.backlogbattlers.api.routes.gameRoutes
import com.backlogbattlers.api.routes.completionRoutes
import com.backlogbattlers.api.routes.debugPushRoutes
import com.backlogbattlers.api.routes.deviceRoutes
import com.backlogbattlers.api.routes.healthRoutes
import com.backlogbattlers.api.routes.leaderboardRoutes
import com.backlogbattlers.api.routes.meRoutes
import com.backlogbattlers.api.routes.libraryRoutes
import com.backlogbattlers.api.routes.profileRoutes
import com.backlogbattlers.api.routes.settingsRoutes
import com.backlogbattlers.api.routes.steamRoutes
import com.backlogbattlers.api.routes.steamImportRoutes
import com.backlogbattlers.api.steam.SteamImporter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing

/**
 * Ktor application module entry point loading database, routes, and environment configuration.
 */
fun Application.module() {
    DatabaseFactory.init()
    // Configure request ID tracing and structured request logging across all routes
    configureRequestLogging()

    install(ContentNegotiation) { json() }
    configureErrors()

    // jwt throw states
    val jwtSecret = Config.default.get("JWT_SECRET").orEmpty().ifBlank {
        throw IllegalStateException("JWT_SECRET environment variable not set. The server won't start without a JWT secret.")
    }
    val jwtIssuer = Config.default.get("JWT_ISSUER").orEmpty().ifBlank {
        throw IllegalStateException("JWT_ISSUER environment variable is not set. The server won't start without explicit JWT issuer.")
    }
    val googleClientId = Config.default.get("GOOGLE_OAUTH_CLIENT_ID").orEmpty().ifBlank {
        throw IllegalStateException("GOOGLE_OAUTH_CLIENT_ID environment variable not set. The server won't start with hardcoded fallback client ID")
    }

    val httpClient = igdbHttpClient()
    val googleVerifier = GoogleIdTokenVerifier(httpClient, googleClientId)
    val jwtService = JwtService(jwtSecret, jwtIssuer)
    val igdb = IgdbClient.fromConfig()
    val steam = SteamClient.fromConfig()
    val notifier = Notifier(PushSender.fromConfig())
    // tells everyone when a new month, and so a new season, begins
    SeasonAnnouncer(notifier).startIn(this)

    // @Dylan, @Andre
    // To add a feature, create : routes/YourFeatureRoutes.kt with `fun Route.yourFeatureRoutes()`,
    // and call it below.
    routing {
        healthRoutes()
        gameRoutes(igdb, steam)
        achievementRoutes(igdb, steam)
        authRoutes(googleVerifier, jwtService)
        libraryRoutes(igdb, currentUser = bearerCurrentUser(jwtService))
        friendRoutes(notifier, currentUser = bearerCurrentUser(jwtService))
        completionRoutes(igdb, steam, notifier, currentUser = bearerCurrentUser(jwtService))
        steamRoutes(currentUser = bearerCurrentUser(jwtService))
        steamImportRoutes(SteamImporter(igdb, steam, notifier = notifier), currentUser = bearerCurrentUser(jwtService))
        leaderboardRoutes(currentUser = bearerCurrentUser(jwtService))
        accountRoutes(currentUser = bearerCurrentUser(jwtService))
        meRoutes(currentUser = bearerCurrentUser(jwtService))
        settingsRoutes(currentUser = bearerCurrentUser(jwtService))
        deviceRoutes(currentUser = bearerCurrentUser(jwtService))
        activityRoutes(currentUser = bearerCurrentUser(jwtService))
        profileRoutes(currentUser = bearerCurrentUser(jwtService))
        // off unless DEBUG_PUSH_ENABLED=true is set on the server, e.g. while recording the demo video
        debugPushRoutes(
            notifier,
            enabled = Config.default.get("DEBUG_PUSH_ENABLED").equals("true", ignoreCase = true),
            currentUser = bearerCurrentUser(jwtService),
        )
    }
}
//------------------------------EOF------------------------------\\