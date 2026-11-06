package com.backlogbattlers.app.viewmodels

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.backlogbattlers.app.R
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.data.remote.ProfileUnavailableException
import com.backlogbattlers.app.data.remote.SessionExpiredException
import com.backlogbattlers.app.data.repository.AuthRepository
import com.backlogbattlers.app.data.repository.ProfileCustomizationRepository
import com.backlogbattlers.app.data.repository.ProfileRepository
import com.backlogbattlers.app.domain.model.Profile
import com.backlogbattlers.app.domain.model.ProfileCustomization
import com.backlogbattlers.app.domain.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// everything the profile screen shows. the header needs only the first two, which are flows and
// so are there at once; the stats, badges, showcase and recent completions need the profile,
// which the backend works out. on a friend's profile the name and picture come from the profile itself
data class ProfileUiState(
    val user: User? = null,
    val customization: ProfileCustomization = ProfileCustomization(),
    val profile: Profile? = null,
    // true when this is a friend's profile, opened from the friends list, the leaderboard or the feed
    val viewingFriend: Boolean = false,
    // the first load, or a retry after it failed: there is no profile to show yet
    val isLoading: Boolean = true,
    // a pull to refresh is under way, so the profile stays on screen under the spinner
    val isRefreshing: Boolean = false,
    // epoch milliseconds the server last answered, while what is shown is the copy saved on this phone (the
    // phone is offline, or the server is not answering yet). null once the profile is fresh from the server
    val savedAt: Long? = null,
    // a message from strings.xml shown in place of the profile's sections, when there is no profile to show
    @StringRes val errorMessage: Int? = null,
    // a message from strings.xml for the screen to show once (a refresh failed, with a profile already on screen)
    @StringRes val message: Int? = null,
) {
    // what the header calls the person: the account's name on your own, the profile's on a friend's
    val displayName: String
        get() = if (viewingFriend) profile?.displayName.orEmpty() else user?.displayName ?: profile?.displayName.orEmpty()

    // the picture they picked: yours is kept on this phone too, a friend's comes with the profile
    val pictureId: String? get() = if (viewingFriend) profile?.pictureId else customization.pictureId
}

// viewmodel for the profile screen: the signed in user's name and picture, and the profile the
// backend works out (level and XP, stats, badges, rarest achievements, recent completions). with a
// [viewedUserId] it is that friend's profile instead. the last profile the server sent is kept on the phone and
// shown first, with a "last updated" label, so the screen opens offline; the server's answer replaces it
class ProfileViewModel(
    private val authRepository: AuthRepository = ServiceLocator.authRepository,
    private val customizationRepository: ProfileCustomizationRepository = ServiceLocator.profileCustomizationRepository,
    private val profileRepository: ProfileRepository = ServiceLocator.profileRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    // the friend whose profile this is, or null for the signed in user's own
    private val viewedUserId: String? = null,
) : ViewModel() {

    // the loading half of the state, kept apart from the two flows it is combined with
    private data class Load(
        val profile: Profile? = null,
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val savedAt: Long? = null,
        val errorMessage: Int? = null,
        val message: Int? = null,
    )

    private val load = MutableStateFlow(Load())
    private var loadedAt = 0L
    private var loadJob: Job? = null

    private val viewingFriend = viewedUserId != null

    // a friend's profile has no account or phone setup behind it: only what the server sent
    private val userFlow: Flow<User?> = if (viewingFriend) flowOf(null) else authRepository.observeCurrentUser()
    private val customizationFlow: Flow<ProfileCustomization> =
        if (viewingFriend) flowOf(ProfileCustomization()) else customizationRepository.observeCustomization()

    val uiState: StateFlow<ProfileUiState> =
        combine(userFlow, customizationFlow, load) { user, customization, load ->
            ProfileUiState(
                user, customization, load.profile, viewingFriend, load.isLoading, load.isRefreshing,
                load.savedAt, load.errorMessage, load.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ProfileUiState(viewingFriend = viewingFriend))

    init {
        showSavedCopy()
        refresh()
    }

    //------------------------------
    // puts the copy saved by the last successful load on screen straight away, so the screen is not
    // empty while the server is asked, and still has something when it can't be reached. a profile that
    // has already arrived is never replaced by it
    private fun showSavedCopy() {
        viewModelScope.launch {
            val saved = try {
                profileRepository.cachedProfile(viewedUserId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The saved profile could not be read", e)
                null
            } ?: return@launch
            load.update {
                if (it.profile != null) it
                else it.copy(profile = saved.profile, savedAt = saved.savedAt, isLoading = false, errorMessage = null)
            }
        }
    }

    //------------------------------
    // loads the profile again. pulled is true when the user dragged the screen down, so the pull
    // spinner shows instead of the loading one. a failure keeps a profile that is already on screen
    // (the saved copy, if that is all there is)
    fun refresh(pulled: Boolean = false) {
        loadJob?.cancel()
        load.update { it.copy(isLoading = !pulled && it.profile == null, isRefreshing = pulled, errorMessage = null) }
        loadJob = viewModelScope.launch {
            try {
                val profile = if (viewedUserId != null) profileRepository.getFriendProfile(viewedUserId) else profileRepository.getProfile()
                Log.d(TAG, "Loaded ${if (viewingFriend) "a friend's" else "the"} profile: level ${profile.level}, ${profile.badgesEarned} badges earned")
                loadedAt = clock()
                load.update { it.copy(profile = profile, isLoading = false, isRefreshing = false, savedAt = null) }
                if (!viewingFriend) syncPicture(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                // the app is already on its way back to login (MainActivity), so no error message on top of that
                Log.w(TAG, "The session ended while loading the profile", e)
                load.update { it.copy(isLoading = false, isRefreshing = false) }
            } catch (e: ProfileUnavailableException) {
                // no longer a friend, or the account is gone: the saved copy would show someone who can no longer be seen
                Log.w(TAG, "That profile is not available")
                load.update { Load(isLoading = false, errorMessage = R.string.profile_error_unavailable) }
            } catch (e: Exception) {
                Log.e(TAG, "Loading the profile failed", e)
                val text = if (viewingFriend) R.string.profile_error_load_friend else R.string.profile_error_load
                load.update {
                    if (it.profile == null) it.copy(isLoading = false, isRefreshing = false, errorMessage = text)
                    else it.copy(isLoading = false, isRefreshing = false, message = text)
                }
            }
        }
    }

    //------------------------------
    // the picture is kept on the account too, so friends see it and a new phone has it. one that only the
    // account has is taken here; one that only this phone has (picked before pictures were saved to the
    // account) is sent up. best effort: a failure just leaves it for the next time
    private fun syncPicture(profile: Profile) {
        viewModelScope.launch {
            try {
                val mine = customizationRepository.observeCustomization().first()
                val onAccount = profile.pictureId
                when {
                    onAccount != null && mine.pictureId == null -> customizationRepository.save(mine.copy(pictureId = onAccount))
                    onAccount == null && mine.pictureId != null -> authRepository.updateProfile(pictureId = mine.pictureId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The profile picture could not be synced", e)
            }
        }
    }

    //------------------------------
    // called each time the screen comes back: reloads quietly when what is shown is older than
    // STALE_AFTER_MS, so a game completed elsewhere is in the stats when the user returns here.
    // a saved copy counts as old, so coming back online brings the real profile
    fun refreshIfStale() {
        val current = load.value
        if (current.isLoading || current.isRefreshing || loadJob?.isActive == true) return
        if (current.profile == null || current.savedAt != null || clock() - loadedAt > STALE_AFTER_MS) refresh()
    }

    //------------------------------
    // the screen has shown the one-off message
    fun messageShown() {
        load.update { it.copy(message = null) }
    }

    companion object {
        private const val TAG = "ProfileViewModel"
        private const val STALE_AFTER_MS = 30_000L

        //------------------------------
        // builds the view model for one friend's profile (or the signed in user's own, with null), which a
        // plain viewModels() cannot do because it needs the id
        fun factory(viewedUserId: String?): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(viewedUserId = viewedUserId) as T
        }
    }
}
//------------------------------EOF------------------------------\\
