package com.backlogbattlers.app.data.repository

import android.util.Log
import com.backlogbattlers.app.data.remote.SettingsApi
import com.backlogbattlers.app.data.remote.SettingsDto
import com.backlogbattlers.app.domain.model.AppLanguage
import com.backlogbattlers.app.domain.model.HomeBackdrop
import com.backlogbattlers.app.domain.model.ThemeMode
import com.backlogbattlers.app.domain.model.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

//------------------------------
// keeps the settings on this phone and the ones saved to the account in step, so they follow
// the user to another phone. the newest change wins:
//   - a change made here is uploaded straight away (push). if that fails, e.g. offline, it is
//     marked pending and uploaded the next time the app syncs
//   - on sign-in and on launch the app syncs: a pending change is uploaded, otherwise the
//     account's settings are pulled down and applied, picking up changes made on another phone
// the fingerprint lock is never synced, it belongs to this phone's sensor
class SettingsSync(
    private val settingsRepository: SettingsRepository,
    private val settingsApi: SettingsApi,
    // a valid access token, or null when nobody is signed in
    private val accessToken: suspend () -> String?,
    // shows pulled settings that the app can't just re-read, i.e. the theme and the language,
    // which are applied to the whole app. given the settings from before and after the pull
    private val applyToApp: suspend (before: UserSettings, after: UserSettings) -> Unit,
) {

    //------------------------------
    // what the app does on sign-in, on launch and when the connection comes back. returns whether
    // the phone and the account ended up in step
    suspend fun sync(): Boolean = if (settingsRepository.isSyncPending()) push() else pull()

    //------------------------------
    // true when a settings change is waiting to be uploaded
    suspend fun hasPendingChanges(): Boolean = settingsRepository.isSyncPending()

    //------------------------------
    // uploads this phone's settings. returns whether they reached the account
    suspend fun push(): Boolean {
        return try {
            val token = accessToken() ?: return false
            settingsApi.putSettings(token, settingsRepository.observeSettings().first().toDto())
            settingsRepository.setSyncPending(false)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Settings could not be uploaded, will retry on the next sync: ${e.message}")
            settingsRepository.setSyncPending(true)
            false
        }
    }

    //------------------------------
    // downloads the account's settings and applies them here. an account that has never saved
    // any (a new user, or one from before settings were synced) gets this phone's instead
    // returns whether the settings were downloaded (or uploaded, for a new account)
    suspend fun pull(): Boolean {
        return try {
            val token = accessToken() ?: return false
            val saved = settingsApi.getSettings(token)
            if (saved == null) {
                return push()
            }
            val before = settingsRepository.observeSettings().first()
            val after = saved.toSettings(keeping = before)
            if (after != before) {
                // applied before it is stored: the settings screen treats the app's language as
                // the truth, so it must already be showing the new one when the stored value changes
                applyToApp(before, after)
                settingsRepository.replaceSyncedSettings(after)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // nothing to undo: the phone simply keeps the settings it has
            Log.w(TAG, "Settings could not be downloaded: ${e.message}")
            false
        }
    }

    private fun UserSettings.toDto() = SettingsDto(
        language = language.tag,
        themeMode = themeMode.name,
        homeBackdrop = homeBackdrop.name,
        defaultPlatform = defaultPlatform,
        achievementNotifications = achievementNotificationsEnabled,
        rankChangeNotifications = rankChangeNotificationsEnabled,
        seasonResetNotifications = seasonResetNotificationsEnabled,
        friendActivityNotifications = friendActivityNotificationsEnabled,
        showBadges = showBadges,
        showShowcase = showShowcase,
    )

    // the account's settings on top of this phone's, which supply anything that is not synced
    // (the fingerprint lock) and stand in for a value this version of the app doesn't know
    private fun SettingsDto.toSettings(keeping: UserSettings) = keeping.copy(
        language = AppLanguage.fromTag(language),
        themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(keeping.themeMode),
        homeBackdrop = runCatching { HomeBackdrop.valueOf(homeBackdrop) }.getOrDefault(keeping.homeBackdrop),
        defaultPlatform = defaultPlatform,
        achievementNotificationsEnabled = achievementNotifications,
        rankChangeNotificationsEnabled = rankChangeNotifications,
        seasonResetNotificationsEnabled = seasonResetNotifications,
        friendActivityNotificationsEnabled = friendActivityNotifications,
        showBadges = showBadges,
        showShowcase = showShowcase,
    )

    private companion object {
        const val TAG = "SettingsSync"
    }
}
//------------------------------EOF------------------------------\\
