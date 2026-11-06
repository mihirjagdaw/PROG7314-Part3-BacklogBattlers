package com.backlogbattlers.app.viewmodels

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backlogbattlers.app.R
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.data.repository.AuthRepository
import com.backlogbattlers.app.data.repository.ProfileCustomizationRepository
import com.backlogbattlers.app.data.repository.ProfileSaveResult
import com.backlogbattlers.app.data.repository.SettingsRepository
import com.backlogbattlers.app.domain.model.ProfileCustomization
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// longest display name the field accepts
const val MAX_DISPLAY_NAME_LENGTH = 20

// the edit profile screen: what is saved now, and the draft being edited on top of it
data class EditProfileUiState(
    val loaded: Boolean = false,
    val signedIn: Boolean = false,
    val savedName: String = "",
    val savedCustomization: ProfileCustomization = ProfileCustomization(),
    val name: String = "",
    val customization: ProfileCustomization = ProfileCustomization(),
    // which sections of the profile friends get to see, as saved to the account and as being edited
    val savedShowBadges: Boolean = true,
    val savedShowShowcase: Boolean = true,
    val showBadges: Boolean = true,
    val showShowcase: Boolean = true,
    val saving: Boolean = false,
) {
    // true once anything differs from what is saved, so leaving asks before throwing it away
    val hasChanges: Boolean
        get() = name.trim() != savedName || customization != savedCustomization ||
            showBadges != savedShowBadges || showShowcase != savedShowShowcase

    // what is wrong with the name as it stands, from strings.xml, or null when it is fine. an empty name is
    // only called out once the player has changed it, so the screen doesn't open with an error on it
    @get:StringRes
    val nameError: Int?
        get() = nameProblem(name, edited = name.trim() != savedName)
}

// what is wrong with a display name, or null when it is fine. [edited] is whether the player has touched
// it, because a name that is empty before they type anything is not their mistake
@StringRes
fun nameProblem(name: String, edited: Boolean): Int? = when {
    name.trim().length > MAX_DISPLAY_NAME_LENGTH -> R.string.edit_profile_name_too_long
    name.trim().isEmpty() && edited -> R.string.edit_profile_name_empty
    else -> null
}

// how a save attempt ended, for the screen to show once
sealed class EditProfileEvent {
    data object Saved : EditProfileEvent()
    data class Rejected(@StringRes val message: Int) : EditProfileEvent()
}

// viewmodel behind the edit profile screen. nothing is stored until save(): the picture, the
// name and the section switches are a draft, so backing out leaves the profile as it was
class EditProfileViewModel(
    private val authRepository: AuthRepository = ServiceLocator.authRepository,
    private val customizationRepository: ProfileCustomizationRepository = ServiceLocator.profileCustomizationRepository,
    private val settingsRepository: SettingsRepository = ServiceLocator.settingsRepository,
    // uploads the settings once the switches are saved. on the application scope, so leaving the screen
    // right after saving can't cut the upload short
    private val uploadSettings: () -> Unit = { ServiceLocator.applicationScope.launch { ServiceLocator.settingsSync.push() } },
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EditProfileEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<EditProfileEvent> = _events.asSharedFlow()

    init {
        load()
    }

    //------------------------------
    // reads the signed-in user's name and saved setup once, as the starting draft
    private fun load() {
        viewModelScope.launch {
            val user = authRepository.getCurrentUser()
            val customization = customizationRepository.observeCustomization().first()
            val settings = settingsRepository.observeSettings().first()
            val name = user?.displayName.orEmpty()
            _uiState.value = EditProfileUiState(
                loaded = true,
                signedIn = user != null,
                savedName = name,
                savedCustomization = customization,
                name = name,
                customization = customization,
                savedShowBadges = settings.showBadges,
                savedShowShowcase = settings.showShowcase,
                showBadges = settings.showBadges,
                showShowcase = settings.showShowcase,
            )
        }
    }

    fun onNameChanged(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun choosePicture(pictureId: String) {
        Log.d(TAG, "Picture $pictureId chosen")
        updateCustomization { it.copy(pictureId = pictureId) }
    }

    fun removePicture() {
        Log.d(TAG, "Picture removed")
        updateCustomization { it.copy(pictureId = null) }
    }

    fun setShowBadges(show: Boolean) = _uiState.update { it.copy(showBadges = show) }

    fun setShowShowcase(show: Boolean) = _uiState.update { it.copy(showShowcase = show) }

    //------------------------------
    // checks the name, then saves what changed of the name and picture on the account (nothing is kept
    // here unless the account took it), then stores the setup on this phone
    fun save() {
        val state = _uiState.value
        if (state.saving) return
        val name = state.name.trim()

        val problem = when {
            !state.signedIn -> R.string.edit_profile_signed_out
            // an empty name is always a problem when saving, even one that was never touched
            name.isEmpty() -> R.string.edit_profile_name_empty
            else -> nameProblem(name, edited = true)
        }
        if (problem != null) {
            Log.d(TAG, "Not saving the profile: ${if (state.signedIn) "the name is not valid" else "nobody is signed in"}")
            _events.tryEmit(EditProfileEvent.Rejected(problem))
            return
        }

        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val nameChanged = name != state.savedName
            val pictureChanged = state.customization.pictureId != state.savedCustomization.pictureId
            if (nameChanged || pictureChanged) {
                val result = authRepository.updateProfile(
                    displayName = name.takeIf { nameChanged },
                    // an empty id takes the picture away
                    pictureId = if (pictureChanged) state.customization.pictureId.orEmpty() else null,
                )
                if (result != ProfileSaveResult.SAVED) {
                    Log.w(TAG, "The profile could not be saved: $result")
                    _uiState.update { it.copy(saving = false) }
                    _events.emit(EditProfileEvent.Rejected(problemFor(result)))
                    return@launch
                }
            }
            customizationRepository.save(state.customization)
            // the two section switches are settings: kept here, then uploaded so the account has them and
            // friends' views honour them (the server hides a section from friends when it is off)
            if (state.showBadges != state.savedShowBadges || state.showShowcase != state.savedShowShowcase) {
                settingsRepository.setShowBadges(state.showBadges)
                settingsRepository.setShowShowcase(state.showShowcase)
                uploadSettings()
            }
            Log.i(TAG, "Profile saved")
            _uiState.update {
                it.copy(
                    saving = false,
                    savedName = name,
                    name = name,
                    savedCustomization = state.customization,
                    savedShowBadges = state.showBadges,
                    savedShowShowcase = state.showShowcase,
                )
            }
            _events.emit(EditProfileEvent.Saved)
        }
    }

    @StringRes
    private fun problemFor(result: ProfileSaveResult): Int = when (result) {
        ProfileSaveResult.NOT_SIGNED_IN -> R.string.edit_profile_signed_out
        ProfileSaveResult.NO_CONNECTION -> R.string.edit_profile_no_connection
        ProfileSaveResult.REJECTED, ProfileSaveResult.FAILED, ProfileSaveResult.SAVED -> R.string.edit_profile_save_failed
    }

    private fun updateCustomization(change: (ProfileCustomization) -> ProfileCustomization) {
        _uiState.update { it.copy(customization = change(it.customization)) }
    }

    private companion object {
        const val TAG = "EditProfileViewModel"
    }
}
//------------------------------EOF------------------------------\\
