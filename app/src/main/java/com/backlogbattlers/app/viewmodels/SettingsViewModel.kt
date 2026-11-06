package com.backlogbattlers.app.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.data.repository.DeleteAccountResult
import com.backlogbattlers.app.domain.model.AppLanguage
import com.backlogbattlers.app.domain.model.HomeBackdrop
import com.backlogbattlers.app.domain.model.Platform
import com.backlogbattlers.app.domain.model.ThemeMode
import com.backlogbattlers.app.domain.model.User
import com.backlogbattlers.app.domain.model.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// shared by settings and every screen under it, so a change made on one screen
// is already on the others when they open
class SettingsViewModel : ViewModel() {

    private val settingsRepository = ServiceLocator.settingsRepository
    private val authRepository = ServiceLocator.authRepository

    val settings: StateFlow<UserSettings> = settingsRepository.observeSettings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = settingsRepository.getSettingsBlocking(),
        )

    val currentUser: StateFlow<User?> = authRepository.observeCurrentUser()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null,
        )

    //------------------------------
    // stores the light/dark choice. the caller applies it to the delegate,
    // which recreates the activity, so this write runs on the application
    // scope rather than one that the recreation could cut short
    fun setThemeMode(mode: ThemeMode) {
        saveAndSync { settingsRepository.setThemeMode(mode) }
    }

    //------------------------------
    // stores the artwork shown behind the home screen header
    fun setHomeBackdrop(backdrop: HomeBackdrop) {
        saveAndSync { settingsRepository.setHomeBackdrop(backdrop) }
    }

    //------------------------------
    // stores the language choice. like the theme, the caller applies it, which recreates the
    // activity, so the write runs on the application scope where the recreation can't cut it short
    fun setLanguage(language: AppLanguage) {
        saveAndSync { settingsRepository.setLanguage(language) }
    }

    //------------------------------
    // stores the platform games are filed under when they are on it
    fun setDefaultPlatform(platform: Platform) {
        saveAndSync { settingsRepository.setDefaultPlatform(platform) }
    }

    //------------------------------
    // notification preferences
    fun setAchievementNotifications(enabled: Boolean) {
        saveAndSync { settingsRepository.setAchievementNotificationsEnabled(enabled) }
    }

    fun setRankChangeNotifications(enabled: Boolean) {
        saveAndSync { settingsRepository.setRankChangeNotificationsEnabled(enabled) }
    }

    fun setSeasonResetNotifications(enabled: Boolean) {
        saveAndSync { settingsRepository.setSeasonResetNotificationsEnabled(enabled) }
    }

    fun setFriendActivityNotifications(enabled: Boolean) {
        saveAndSync { settingsRepository.setFriendActivityNotificationsEnabled(enabled) }
    }

    //------------------------------
    // saves a change on this phone, then uploads the settings so they follow the user to another
    // phone (see SettingsSync). on the application scope, so leaving the screen, or the activity
    // being recreated for a new theme or language, can't cut the write or the upload short
    private fun saveAndSync(write: suspend () -> Unit) {
        ServiceLocator.applicationScope.launch {
            write()
            Log.d(TAG, "Setting saved on this phone, uploading the settings")
            ServiceLocator.settingsSync.push()
        }
    }

    //------------------------------
    // biometric unlock preference, kept on this phone only: it depends on this phone's sensor
    fun setBiometricLogin(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setBiometricLoginEnabled(enabled)
            // the lock's keystore key goes with it, a fresh one is made if the lock is turned on again
            if (!enabled) ServiceLocator.unlockKey.delete()
        }
    }

    //------------------------------
    // deletes the account on the backend. once it is gone, everything it left on this device
    // goes too (library, recent searches, profile setup, the fingerprint lock) so the next person to sign in
    // starts clean. runs on the application scope so leaving the screen mid-request can't
    // leave the account deleted on the backend but still on the device. the account id is read
    // first, because deleting signs out and the library is cleared by whose it was
    fun deleteAccount(onDone: (DeleteAccountResult) -> Unit) {
        ServiceLocator.applicationScope.launch {
            val userId = ServiceLocator.tokenStorage.getUserId()
            val result = authRepository.deleteAccount()
            Log.i(TAG, "Account deletion answered $result")
            if (result == DeleteAccountResult.DELETED) {
                Log.i(TAG, "Clearing what the deleted account left on this phone")
                if (userId != null) ServiceLocator.libraryRepository.clear(userId)
                if (userId != null) ServiceLocator.searchHistoryRepository.clear(userId)
                if (userId != null) ServiceLocator.profileCustomizationRepository.clear(userId)
                settingsRepository.setBiometricLoginEnabled(false)
                ServiceLocator.unlockKey.delete()
                settingsRepository.setSyncPending(false)
            }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    //------------------------------
    // signs the user out and clears tokens. a settings change that never got uploaded belongs to
    // this account, so it is dropped rather than sent to whoever signs in next
    fun logOut(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            Log.i(TAG, "Signed out")
            settingsRepository.setSyncPending(false)
            onDone()
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
//------------------------------EOF------------------------------\\
