package com.backlogbattlers.api.settings

import com.backlogbattlers.api.db.Platform
import com.backlogbattlers.api.db.ProfileVisibilities
import com.backlogbattlers.api.db.UserSettings
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import kotlin.uuid.Uuid

// The values the app offers. They mirror the Android app's AppLanguage tags, ThemeMode and
// HomeBackdrop, so a typo or an out-of-date app can't store something no phone can show.
val LANGUAGES = setOf("en", "af", "zu")
val THEME_MODES = setOf("DARK", "LIGHT")
val HOME_BACKDROPS = setOf("HILLS", "MOUNTAIN", "MEADOW", "FOREST", "COAST")

/** OTHER is what a game falls back to, not a platform anyone picks as their own. */
val DEFAULT_PLATFORMS = Platform.entries.toSet() - Platform.OTHER

/**
 * The user's saved app preferences, the body of `PUT /users/me/settings` and the answer to both
 * settings routes. Names match the app's `UserSettings`. The fingerprint lock is deliberately not
 * here: it belongs to one phone's sensor, not to the account.
 */
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
    /**
     * Whether friends who open this user's profile see their badges, and their showcase of rarest
     * achievements. On unless the user turned them off; an app that doesn't send them leaves them on.
     */
    val showBadges: Boolean = true,
    val showShowcase: Boolean = true,
    /** Epoch milliseconds of the last save. Set by the server; anything the app sends is ignored. */
    val updatedAt: Long? = null,
)

/** Which optional sections of their profile a user lets friends see. */
data class ProfileVisibility(val showBadges: Boolean = true, val showShowcase: Boolean = true)

/** What [userId] lets friends see of their profile. Both sections are shown until they say otherwise. */
fun profileVisibilityFor(userId: Uuid): ProfileVisibility = transaction {
    ProfileVisibilities.selectAll().where { ProfileVisibilities.userId eq userId }.singleOrNull()
        ?.let { ProfileVisibility(it[ProfileVisibilities.showBadges], it[ProfileVisibilities.showShowcase]) }
        ?: ProfileVisibility()
}

/** What is wrong with [settings], or null when every value is one the app offers. */
fun problemWith(settings: SettingsDto): String? = when {
    settings.language !in LANGUAGES -> "language must be one of ${LANGUAGES.joinToString()}"
    settings.themeMode !in THEME_MODES -> "themeMode must be one of ${THEME_MODES.joinToString()}"
    settings.homeBackdrop !in HOME_BACKDROPS -> "homeBackdrop must be one of ${HOME_BACKDROPS.joinToString()}"
    settings.defaultPlatform !in DEFAULT_PLATFORMS -> "defaultPlatform must be one of ${DEFAULT_PLATFORMS.joinToString()}"
    else -> null
}

/** The user's saved settings, or null if the app has never saved any for them. */
fun settingsFor(userId: Uuid): SettingsDto? = transaction {
    UserSettings.selectAll().where { UserSettings.userId eq userId }.singleOrNull()?.toDto(profileVisibilityFor(userId))
}

/** Replaces the user's settings with [settings] (already checked with [problemWith]) and returns what was stored. */
fun saveSettings(userId: Uuid, settings: SettingsDto): SettingsDto = transaction {
    UserSettings.upsert {
        it[UserSettings.userId] = userId
        it[language] = settings.language
        it[themeMode] = settings.themeMode
        it[homeBackdrop] = settings.homeBackdrop
        it[defaultPlatform] = settings.defaultPlatform
        it[achievementNotifications] = settings.achievementNotifications
        it[rankChangeNotifications] = settings.rankChangeNotifications
        it[seasonResetNotifications] = settings.seasonResetNotifications
        it[friendActivityNotifications] = settings.friendActivityNotifications
        it[updatedAt] = Instant.now()
    }
    ProfileVisibilities.upsert {
        it[ProfileVisibilities.userId] = userId
        it[showBadges] = settings.showBadges
        it[showShowcase] = settings.showShowcase
    }
    UserSettings.selectAll().where { UserSettings.userId eq userId }.single().toDto(profileVisibilityFor(userId))
}

private fun ResultRow.toDto(visibility: ProfileVisibility) = SettingsDto(
    language = this[UserSettings.language],
    themeMode = this[UserSettings.themeMode],
    homeBackdrop = this[UserSettings.homeBackdrop],
    defaultPlatform = this[UserSettings.defaultPlatform],
    achievementNotifications = this[UserSettings.achievementNotifications],
    rankChangeNotifications = this[UserSettings.rankChangeNotifications],
    seasonResetNotifications = this[UserSettings.seasonResetNotifications],
    friendActivityNotifications = this[UserSettings.friendActivityNotifications],
    showBadges = visibility.showBadges,
    showShowcase = visibility.showShowcase,
    updatedAt = this[UserSettings.updatedAt].toEpochMilli(),
)
//------------------------------EOF------------------------------\\
