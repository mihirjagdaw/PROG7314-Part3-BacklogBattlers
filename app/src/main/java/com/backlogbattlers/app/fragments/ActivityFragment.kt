package com.backlogbattlers.app.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.backlogbattlers.app.R
import com.backlogbattlers.app.adapters.ActivityFeedAdapter
import com.backlogbattlers.app.adapters.ActivityIntroAdapter
import com.backlogbattlers.app.adapters.ActivityStatusAdapter
import com.backlogbattlers.app.domain.model.ActivityEvent
import com.backlogbattlers.app.util.openProfileOf
import com.backlogbattlers.app.util.setVisible
import com.backlogbattlers.app.util.updatedAgo
import com.backlogbattlers.app.viewmodels.ActivityTap
import com.backlogbattlers.app.viewmodels.activityTapFor
import com.backlogbattlers.app.viewmodels.ActivityViewModel
import kotlinx.coroutines.launch

// the activity feed: what you and your friends have been doing, newest first and grouped by day.
// a title and a card that opens the standings sit above the feed, and a loading, empty or error
// row below it, all in the one list so they scroll together. pull down to refresh, and the next
// page loads as the end of the list comes into view
class ActivityFragment : Fragment() {

    private val viewModel: ActivityViewModel by viewModels()

    //------------------------------
    // inflates the activity fragment layout
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_activity, container, false)
    }

    // MainActivity already pads the root with the status bar inset for this destination, so what is
    // left is the settings button, the list and its adapters, pull to refresh and paging
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btn_settings).setOnClickListener {
            Log.d(TAG, "Opening settings from the activity feed")
            findNavController().navigate(R.id.action_activityFragment_to_settingsFragment)
        }

        val introAdapter = ActivityIntroAdapter(onViewStandings = {
            Log.d(TAG, "Opening the standings from the activity feed")
            findNavController().navigate(R.id.action_activityFragment_to_standingsFragment)
        })
        val feedAdapter = ActivityFeedAdapter(onEventClick = ::openEvent)
        val statusAdapter = ActivityStatusAdapter(onRetry = { viewModel.refresh() })

        val list = view.findViewById<RecyclerView>(R.id.rv_activity)
        val layoutManager = LinearLayoutManager(requireContext())
        list.layoutManager = layoutManager
        list.adapter = ConcatAdapter(introAdapter, feedAdapter, statusAdapter)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            // this if statement asks for the next page once the last few rows are on screen
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val adapter = recyclerView.adapter ?: return
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_AHEAD) {
                    viewModel.loadMore()
                }
            }
        })

        val savedLabel = view.findViewById<TextView>(R.id.tv_activity_saved)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        swipe.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.accent))
        swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(requireContext(), R.color.surface))
        swipe.setOnRefreshListener { viewModel.refresh(pulled = true) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    swipe.isRefreshing = state.isRefreshing

                    // while the feed is the copy saved on this phone, say when the server last answered
                    savedLabel.setVisible(state.savedAt != null)
                    state.savedAt?.let { savedLabel.text = getString(R.string.profile_saved_copy, requireContext().updatedAgo(it)) }
                    feedAdapter.selfUserId = state.selfUserId
                    feedAdapter.submitList(state.rows)

                    statusAdapter.errorText = state.errorMessage ?: R.string.activity_error_load
                    statusAdapter.status = when {
                        state.isLoading -> ActivityStatusAdapter.Status.LOADING
                        state.errorMessage != null -> ActivityStatusAdapter.Status.ERROR
                        state.isEmpty -> ActivityStatusAdapter.Status.EMPTY
                        state.isLoadingMore -> ActivityStatusAdapter.Status.LOADING_MORE
                        else -> ActivityStatusAdapter.Status.NONE
                    }

                    // this if statement shows a failed refresh or page as a toast, so the feed already on screen stays
                    state.message?.let {
                        Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                        viewModel.messageShown()
                    }
                }
            }
        }
    }

    //------------------------------
    // a tapped row: a completion opens its game, anything else opens the person it is about (see activityTapFor)
    private fun openEvent(event: ActivityEvent) {
        val selfUserId = viewModel.uiState.value.selfUserId
        when (val tap = activityTapFor(event, selfUserId)) {
            is ActivityTap.Game -> {
                Log.d(TAG, "Opening a game from the activity feed")
                findNavController().navigate(
                    R.id.action_activityFragment_to_gameDetailFragment,
                    bundleOf(ARG_GAME_ID to tap.gameId.toString()),
                )
            }
            is ActivityTap.Profile -> {
                Log.d(TAG, "Opening a profile from the activity feed")
                findNavController().openProfileOf(tap.userId, selfUserId)
            }
            ActivityTap.None -> Unit
        }
    }

    private companion object {
        const val TAG = "ActivityFragment"

        // the gameDetailFragment argument naming the game to show
        const val ARG_GAME_ID = "gameId"

        // rows from the end of the list at which the next page is asked for
        const val LOAD_MORE_AHEAD = 5
    }
}
//------------------------------EOF------------------------------\
