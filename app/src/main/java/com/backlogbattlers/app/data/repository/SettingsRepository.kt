package com.backlogbattlers.app.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.backlogbattlers.app.domain.model.AppLanguage
import com.backlogbattlers.app.domain.model.HomeBackdrop
import com.backlogbattlers.app.domain.model.Platform
import com.backlogbattlers.app.domain.model.ThemeMode
import com.backlogbattlers.app.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "user_settings")

//------------------------------
// interface providing operations for observing and updating UserSettings
interface SettingsRepository {

    // observes application UserSettings as a Flow
    fun observeSettings(): Flow<UserSettings>

    // reads UserSettings once, blocking the caller.
    // only for the night mode lookup MainApplication has to make before the
    // first activity is themed, everything else observes the Flow instead
    fun getSettingsBlocking(): UserSettings

    // updates the application language setting
    suspend fun setLanguage(language: AppLanguage)

    // updates the light/dark appearance preference
    suspend fun setThemeMode(mode: ThemeMode)

    // updates the artwork shown behind the home screen header
    suspend fun setHomeBackdrop(backdrop: HomeBackdrop)

    // updates the platform games are filed under when they are on it
    suspend fun setDefaultPlatform(platform: Platform)

    // updates the biometric login preference
    suspend fun setBiometricLoginEnabled(enabled: Boolean)

    // updates whether friends see this user's badges on their profile
    suspend fun setShowBadges(show: Boolean)

    // updates whether friends see this user's showcase of rarest achievements on their profile
    suspend fun setShowShowcase(show: Boolean)

    // updates the achievement notification preference
    suspend fun setAchievementNotificationsEnabled(enabled: Boolean)

    // updates the rank change notification preference
    suspend fun setRankChangeNotificationsEnabled(enabled: Boolean)

    // updates the season reset notification preference
    suspend fun setSeasonResetNotificationsEnabled(enabled: Boolean)

    // updates the friend activity notification preference
    suspend fun setFriendActivityNotificationsEnabled(enabled: Boolean)

    // replaces everything that is saved to the account with [settings] in one write, after
    // pulling them from the API. the fingerprint lock is left alone, it belongs to this phone
    suspend fun replaceSyncedSettings(settings: UserSettings)

    // whether a change made here still has to be uploaded, e.g. because it was made offline
    suspend fun isSyncPending(): Boolean

    // marks or clears a change still waiting to be uploaded
    suspend fun setSyncPending(pending: Boolean)

    // whether this phone has already asked "unlock with your fingerprint?" after a sign-in.
    // kept on the phone only, like the fingerprint lock itself, so it is asked once per phone
    suspend fun hasOfferedBiometricUnlock(): Boolean

    // remembers that the question was asked, whatever the answer
    suspend fun setBiometricUnlockOffered()
}

//------------------------------
// implementation of SettingsRepository using DataStore Preferences
class SettingsRepositoryImpl(
    private val context: Context,
) : SettingsRepository {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HOME_BACKDROP = stringPreferencesKey("home_backdrop")
        val DEFAULT_PLATFORM = stringPreferencesKey("default_platform")
        val BIOMETRIC_LOGIN_ENABLED = booleanPreferencesKey("biometric_login_enabled")
        val NOTIF_ACHIEVEMENT = booleanPreferencesKey("notif_achievement")
        val NOTIF_RANK_CHANGE = booleanPreferencesKey("notif_rank_change")
        val NOTIF_SEASON_RESET = booleanPreferencesKey("notif_season_reset")
        val NOTIF_FRIEND_ACTIVITY = booleanPreferencesKey("notif_friend_activity")
        val SHOW_BADGES = booleanPreferencesKey("show_badges")
        val SHOW_SHOWCASE = booleanPreferencesKey("show_showcase")
        val SYNC_PENDING = booleanPreferencesKey("settings_sync_pending")
        val BIOMETRIC_OFFERED = booleanPreferencesKey("biometric_unlock_offered")
    }

    //------------------------------
    // observes UserSettings from DataStore with fallback defaults
    override fun observeSettings(): Flow<UserSettings> {
        return context.dataStore.data.map { prefs ->
            val languageString = prefs[Keys.LANGUAGE]
            val language = languageString?.let {
                runCatching { AppLanguage.valueOf(it) }.getOrNull()
            } ?: AppLanguage.ENGLISH

            val themeMode = prefs[Keys.THEME_MODE]?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()
            } ?: ThemeMode.DARK

            val homeBackdrop = prefs[Keys.HOME_BACKDROP]?.let {
                runCatching { HomeBackdrop.valueOf(it) }.getOrNull()
            } ?: HomeBackdrop.HILLS

            val defaultPlatform = prefs[Keys.DEFAULT_PLATFORM]?.let {
                runCatching { Platform.valueOf(it) }.getOrNull()
            } ?: Platform.PC

            UserSettings(
                language = language,
                themeMode = themeMode,
                homeBackdrop = homeBackdrop,
                defaultPlatform = defaultPlatform,
                biometricLoginEnabled = prefs[Keys.BIOMETRIC_LOGIN_ENABLED] ?: false,
                achievementNotificationsEnabled = prefs[Keys.NOTIF_ACHIEVEMENT] ?: true,
                rankChangeNotificationsEnabled = prefs[Keys.NOTIF_RANK_CHANGE] ?: true,
                seasonResetNotificationsEnabled = prefs[Keys.NOTIF_SEASON_RESET] ?: true,
                friendActivityNotificationsEnabled = prefs[Keys.NOTIF_FRIEND_ACTIVITY] ?: true,
                showBadges = prefs[Keys.SHOW_BADGES] ?: true,
                showShowcase = prefs[Keys.SHOW_SHOWCASE] ?: true,
            )
        }
    }

    //------------------------------
    // reads the stored settings once. MainApplication needs the theme before
    // the first activity inflates, which is earlier than a Flow can deliver
    override fun getSettingsBlocking(): UserSettings {
        return runCatching { runBlocking { observeSettings().first() } }
            .getOrDefault(UserSettings())
    }

    //------------------------------
    // saves selected AppLanguage preference to DataStore
    override suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = language.name
        }
    }

    //------------------------------
    // saves the selected ThemeMode preference to DataStore
    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    //------------------------------
    // saves the selected HomeBackdrop preference to DataStore
    override suspend fun setHomeBackdrop(backdrop: HomeBackdrop) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOME_BACKDROP] = backdrop.name
        }
    }

    //------------------------------
    // saves the default Platform preference to DataStore
    override suspend fun setDefaultPlatform(platform: Platform) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_PLATFORM] = platform.name
        }
    }

    //------------------------------
    // saves biometric login boolean preference to DataStore
    override suspend fun setBiometricLoginEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BIOMETRIC_LOGIN_ENABLED] = enabled
        }
    }

    //------------------------------
    // saves whether friends see this user's badges
    override suspend fun setShowBadges(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SHOW_BADGES] = show }
    }

    //------------------------------
    // saves whether friends see this user's showcase
    override suspend fun setShowShowcase(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.SHOW_SHOWCASE] = show }
    }

    //------------------------------
    // saves achievement notification preference to DataStore
    override suspend fun setAchievementNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIF_ACHIEVEMENT] = enabled
        }
    }

    //------------------------------
    // saves rank change notification preference to DataStore
    override suspend fun setRankChangeNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIF_RANK_CHANGE] = enabled
        }
    }

    //------------------------------
    // saves season reset notification preference to DataStore
    override suspend fun setSeasonResetNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIF_SEASON_RESET] = enabled
        }
    }

    //------------------------------
    // saves friend activity notification preference to DataStore
    override suspend fun setFriendActivityNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIF_FRIEND_ACTIVITY] = enabled
        }
    }

    //------------------------------
    // writes every synced setting in a single DataStore edit, so the screens see one change
    override suspend fun replaceSyncedSettings(settings: UserSettings) {
        Log.d(TAG, "Replacing the local settings with the synced copy")
        context.dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = settings.language.name
            prefs[Keys.THEME_MODE] = settings.themeMode.name
            prefs[Keys.HOME_BACKDROP] = settings.homeBackdrop.name
            prefs[Keys.DEFAULT_PLATFORM] = settings.defaultPlatform.name
            prefs[Keys.NOTIF_ACHIEVEMENT] = settings.achievementNotificationsEnabled
            prefs[Keys.NOTIF_RANK_CHANGE] = settings.rankChangeNotificationsEnabled
            prefs[Keys.NOTIF_SEASON_RESET] = settings.seasonResetNotificationsEnabled
            prefs[Keys.NOTIF_FRIEND_ACTIVITY] = settings.friendActivityNotificationsEnabled
            prefs[Keys.SHOW_BADGES] = settings.showBadges
            prefs[Keys.SHOW_SHOWCASE] = settings.showShowcase
        }
    }

    override suspend fun isSyncPending(): Boolean {
        return context.dataStore.data.first()[Keys.SYNC_PENDING] ?: false
    }

    override suspend fun setSyncPending(pending: Boolean) {
        Log.d(TAG, "Settings upload pending: $pending")
        context.dataStore.edit { prefs -> prefs[Keys.SYNC_PENDING] = pending }
    }

    override suspend fun hasOfferedBiometricUnlock(): Boolean {
        return context.dataStore.data.first()[Keys.BIOMETRIC_OFFERED] ?: false
    }

    override suspend fun setBiometricUnlockOffered() {
        context.dataStore.edit { prefs -> prefs[Keys.BIOMETRIC_OFFERED] = true }
    }

    private companion object {
        const val TAG = "SettingsRepository"
    }
}
