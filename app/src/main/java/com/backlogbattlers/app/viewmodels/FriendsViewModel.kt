package com.backlogbattlers.app.viewmodels

import android.util.Log
import androidx.annotation.StringRes
import com.backlogbattlers.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.data.remote.SessionExpiredException
import com.backlogbattlers.app.data.repository.AuthRepository
import com.backlogbattlers.app.data.repository.ProfileCustomizationRepository
import com.backlogbattlers.app.data.repository.FriendsRepository
import com.backlogbattlers.app.domain.model.Friend
import com.backlogbattlers.app.domain.model.FriendRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// which of the two tabs is currently selected
enum class FriendsTab { ALL_FRIENDS, REQUESTS }

// everything the friends screen needs to render, for whichever tab is selected
data class FriendsUiState(
    val selectedTab: FriendsTab = FriendsTab.ALL_FRIENDS,
    val friends: List<Friend> = emptyList(),
    val filteredFriends: List<Friend> = emptyList(),
    val requests: List<FriendRequest> = emptyList(),
    val selfUserId: String? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    // a message from strings.xml for the screen to show once, never the raw exception text
    @StringRes val errorMessage: Int? = null,
    val hasNoFriends: Boolean = false,
)

// viewmodel for the friends screen friend list and incoming requests
class FriendsViewModel(
    private val friendsRepository: FriendsRepository = ServiceLocator.friendsRepository,
    private val authRepository: AuthRepository = ServiceLocator.authRepository,
    private val customizationRepository: ProfileCustomizationRepository = ServiceLocator.profileCustomizationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FriendsUiState())
    val uiState: StateFlow<FriendsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    //------------------------------
    // loads the signed in user's profile, friends and incoming requests, then merges the
    // signed in user into the friend list so the screen can show them ranked alongside friends
    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val self = authRepository.getCurrentUser()
                val friends = friendsRepository.getFriends()
                val requests = friendsRepository.getIncomingRequests()
                Log.d(TAG, "Loaded ${friends.size} friends and ${requests.size} requests")

                // this if statement builds the signed in user's own row, with achievementCount
                val selfFriend = self?.let {
                    Friend(
                        it.userId, it.displayName, it.avatarUrl, monthlyPoints = 0, achievementCount = 0, isOnline = true,
                        pictureId = customizationRepository.observeCustomization().first().pictureId,
                    )
                }
                val combined = if (selfFriend != null) friends + selfFriend else friends
                val sorted = combined.sortedWith(compareByDescending<Friend> { friend -> friend.monthlyPoints }.thenBy { friend -> friend.displayName })
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        friends = sorted,
                        filteredFriends = filterFriends(sorted, current.searchQuery),
                        requests = requests,
                        selfUserId = self?.userId,
                        hasNoFriends = sorted.size <= 1,
                    )
                }
            } catch (e: SessionExpiredException) {
                // the app is already on its way back to login (MainActivity), so no error message on top of that
                Log.w(TAG, "The session ended while loading friends", e)
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Loading friends failed", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = R.string.friends_error_load) }
            }
        }
    }

    //------------------------------
    // switches the visible tab, does not trigger a network call
    fun selectTab(tab: FriendsTab) {
        Log.d(TAG, "Friends tab $tab selected")
        _uiState.update { it.copy(selectedTab = tab) }
    }

    //------------------------------
    // filters the already loaded friend list by name, client side
    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query, filteredFriends = filterFriends(it.friends, query)) }
    }

    //------------------------------
    // accepts an incoming request, then reloads so both lists reflect the change
    fun acceptRequest(requestId: String) {
        viewModelScope.launch {
            try {
                friendsRepository.acceptRequest(requestId)
                Log.i(TAG, "Friend request accepted")
                refresh()
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "The session ended while accepting a friend request", e)
            } catch (e: Exception) {
                Log.e(TAG, "Accepting a friend request failed", e)
                _uiState.update { it.copy(errorMessage = R.string.friends_error_accept) }
            }
        }
    }

    //------------------------------
    // rejects an incoming request, then reloads so the requests list reflects the change
    fun rejectRequest(requestId: String) {
        viewModelScope.launch {
            try {
                friendsRepository.rejectRequest(requestId)
                Log.i(TAG, "Friend request rejected")
                refresh()
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "The session ended while rejecting a friend request", e)
            } catch (e: Exception) {
                Log.e(TAG, "Rejecting a friend request failed", e)
                _uiState.update { it.copy(errorMessage = R.string.friends_error_reject) }
            }
        }
    }

    //------------------------------
    // returns friends whose display name contains the query, or all friends when the query is blank
    private fun filterFriends(friends: List<Friend>, query: String): List<Friend> {

        // this if statement skips filtering entirely when there's nothing typed
        if (query.isBlank()) return friends
        return friends.filter { it.displayName.contains(query, ignoreCase = true) }
    }

    private companion object {
        const val TAG = "FriendsViewModel"
    }
}
//------------------------------EOF------------------------------\\