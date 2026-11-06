package com.backlogbattlers.app.viewmodels

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backlogbattlers.app.R
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.data.remote.SessionExpiredException
import com.backlogbattlers.app.data.repository.ActivityRepository
import com.backlogbattlers.app.domain.model.ActivityEvent
import com.backlogbattlers.app.domain.model.ActivityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// one row of the feed list: the day a run of events happened on, or one event
sealed interface ActivityRow {
    data class Day(val date: LocalDate) : ActivityRow
    data class Event(val event: ActivityEvent) : ActivityRow
}

// everything the activity screen needs to render
data class ActivityUiState(
    val rows: List<ActivityRow> = emptyList(),
    val selfUserId: String? = null,
    // the first load, or a retry after it failed: nothing to show yet
    val isLoading: Boolean = true,
    // a pull to refresh is under way, so the list stays on screen under the spinner
    val isRefreshing: Boolean = false,
    // the next, older page is being fetched at the bottom of the list
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    // epoch milliseconds the server last answered, while the feed on screen is the copy saved on this phone
    // (offline, or the server is not answering yet). null once the feed is fresh from the server
    val savedAt: Long? = null,
    // a message from strings.xml shown in place of the feed, when there is nothing else to show
    @StringRes val errorMessage: Int? = null,
    // a message from strings.xml for the screen to show once (a refresh or next page failed, with a feed already on screen)
    @StringRes val message: Int? = null,
) {
    val isEmpty: Boolean get() = !isLoading && errorMessage == null && rows.isEmpty()
}

// viewmodel for the activity screen: the newest events of the user and their friends, paged
class ActivityViewModel(
    private val repository: ActivityRepository = ServiceLocator.activityRepository,
    // who is signed in, so the feed can say "You" for their own events
    private val selfUserId: suspend () -> String? = { ServiceLocator.authRepository.getCurrentUser()?.userId },
    // the days events are grouped under are the phone's days
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    private var events: List<ActivityEvent> = emptyList()
    private var nextBefore: Long? = null
    private var loadJob: Job? = null

    // the server has answered at least once, so a saved copy must not replace what it said
    private var serverAnswered = false

    init {
        showSavedCopy()
        refresh()
    }

    //------------------------------
    // puts the newest page saved by the last successful load on screen straight away, so the feed is not
    // empty while the server is asked, and is still there when it can't be reached. a feed that has already
    // arrived is never replaced by it. a saved copy has no further pages: older events need the connection
    private fun showSavedCopy() {
        viewModelScope.launch {
            val saved = try {
                repository.cachedNewest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The saved activity feed could not be read", e)
                null
            } ?: return@launch
            // the server's answer may have got here first (even an empty one), and it wins
            if (events.isNotEmpty() || serverAnswered) return@launch
            events = saved.page.events
            nextBefore = null
            val self = runCatching { selfUserId() }.getOrNull()
            _uiState.update {
                it.copy(rows = buildRows(events, zone), selfUserId = self ?: it.selfUserId, isLoading = false, hasMore = false, savedAt = saved.savedAt, errorMessage = null)
            }
        }
    }

    //------------------------------
    // loads the newest page again, replacing what is shown. pulled is true when the user dragged the
    // list down, so the pull spinner shows instead of the loading one
    fun refresh(pulled: Boolean = false) {
        loadJob?.cancel()
        _uiState.update {
            it.copy(isLoading = !pulled && events.isEmpty(), isRefreshing = pulled, isLoadingMore = false, errorMessage = null)
        }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.getActivity(before = null)
                Log.d(TAG, "Loaded ${page.events.size} events, ${if (page.nextBefore != null) "more" else "no more"} after them")
                events = page.events
                nextBefore = page.nextBefore
                serverAnswered = true
                val self = runCatching { selfUserId() }.getOrNull()
                _uiState.update {
                    it.copy(
                        rows = buildRows(events, zone),
                        selfUserId = self ?: it.selfUserId,
                        isLoading = false,
                        isRefreshing = false,
                        hasMore = nextBefore != null,
                        savedAt = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                // the app is already on its way back to login (MainActivity), so no error message on top of that
                Log.w(TAG, "The session ended while loading the activity feed", e)
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Loading the activity feed failed", e)
                _uiState.update {
                    // with a feed already shown, a failed refresh must not blank it out
                    if (events.isEmpty()) it.copy(isLoading = false, isRefreshing = false, errorMessage = R.string.activity_error_load)
                    else it.copy(isLoading = false, isRefreshing = false, message = R.string.activity_error_load)
                }
            }
        }
    }

    //------------------------------
    // fetches the next, older page and adds it to the end of the list. does nothing on the last
    // page, or while another load is under way, so the list can call it on every scroll
    fun loadMore() {
        val before = nextBefore ?: return
        if (loadJob?.isActive == true) return
        _uiState.update { it.copy(isLoadingMore = true) }
        loadJob = viewModelScope.launch {
            try {
                val page = repository.getActivity(before = before)
                Log.d(TAG, "Loaded ${page.events.size} older events")
                // a new event can push one onto the next page again, so an id already shown is not added twice
                val seen = events.mapTo(HashSet()) { it.eventId }
                events = events + page.events.filter { it.eventId !in seen }
                nextBefore = page.nextBefore
                _uiState.update {
                    it.copy(rows = buildRows(events, zone), isLoadingMore = false, hasMore = nextBefore != null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                Log.w(TAG, "The session ended while loading older activity", e)
                _uiState.update { it.copy(isLoadingMore = false) }
            } catch (e: Exception) {
                // nextBefore is kept, so scrolling to the bottom again tries the same page again
                Log.e(TAG, "Loading older activity failed", e)
                _uiState.update { it.copy(isLoadingMore = false, message = R.string.activity_error_load) }
            }
        }
    }

    //------------------------------
    // the screen has shown the one-off message
    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        const val TAG = "ActivityViewModel"
    }
}

//------------------------------
// where tapping a feed row goes
sealed interface ActivityTap {
    // the game a completion was for
    data class Game(val gameId: Int) : ActivityTap

    // a person's profile (the signed in user's own profile tab, if it is them)
    data class Profile(val userId: String) : ActivityTap

    // nowhere: an event this version does not know, or one that names nobody to open
    data object None : ActivityTap
}

// what tapping [event] opens, given who is signed in [selfUserId]:
//  - a completion opens the game, or the player if the event has no game
//  - unlocked achievements open the player's profile, where the recent activity lists them
//  - a new friendship or a passed rank opens the other person in it: the actor, or the subject when the
//    actor is the signed in user, so tapping your own row opens the friend you made and not yourself
fun activityTapFor(event: ActivityEvent, selfUserId: String?): ActivityTap = when (event.type) {
    ActivityType.GAME_COMPLETED -> event.gameId?.let { ActivityTap.Game(it) } ?: ActivityTap.Profile(event.actorId)
    ActivityType.ACHIEVEMENT_UNLOCKED -> ActivityTap.Profile(event.actorId)
    ActivityType.FRIEND_ADDED, ActivityType.RANK_PASSED -> {
        val other = if (event.actorId == selfUserId) event.subjectId else event.actorId
        if (other.isNullOrBlank()) ActivityTap.None else ActivityTap.Profile(other)
    }
    ActivityType.UNKNOWN -> ActivityTap.None
}

//------------------------------
// the events in the order they came (newest first), with a Day row before each run of events that
// happened on the same day
fun buildRows(events: List<ActivityEvent>, zone: ZoneId): List<ActivityRow> {
    val rows = ArrayList<ActivityRow>(events.size + 4)
    var day: LocalDate? = null
    for (event in events) {
        val date = Instant.ofEpochMilli(event.createdAt).atZone(zone).toLocalDate()
        // this if statement starts a new day group whenever the day changes
        if (date != day) {
            rows += ActivityRow.Day(date)
            day = date
        }
        rows += ActivityRow.Event(event)
    }
    return rows
}
//------------------------------EOF------------------------------\\
