package com.backlogbattlers.app.fragments

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.backlogbattlers.app.R
import com.backlogbattlers.app.adapters.FriendAdapter
import com.backlogbattlers.app.util.openProfileOf
import com.backlogbattlers.app.adapters.RequestAdapter
import com.backlogbattlers.app.notifications.OPEN_TAB_REQUESTS
import com.backlogbattlers.app.notifications.PushNotifications
import com.backlogbattlers.app.viewmodels.FriendsTab
import com.backlogbattlers.app.viewmodels.FriendsViewModel
import kotlinx.coroutines.launch

// fragment displaying all friends, following (to do...) and incoming requests
class FriendsFragment : Fragment() {

    private val viewModel: FriendsViewModel by viewModels()

    private lateinit var friendAdapter: FriendAdapter
    private lateinit var requestAdapter: RequestAdapter

    //------------------------------
    // inflates the friends fragment layout
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_friends, container, false)
    }

    //------------------------------
    // initializes views, adapters, click listeners and collects ui state updates
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnAddFriend = view.findViewById<ImageButton>(R.id.btn_add_friend)
        val btnToggleSearch = view.findViewById<ImageButton>(R.id.btn_toggle_search)
        val etFriendSearch = view.findViewById<EditText>(R.id.et_friend_search)

        val btnTabAllFriends = view.findViewById<TextView>(R.id.btn_tab_all_friends)
        val btnTabFollowing = view.findViewById<TextView>(R.id.btn_tab_following)
        val btnTabRequests = view.findViewById<TextView>(R.id.btn_tab_requests)
        val tvRequestsBadge = view.findViewById<TextView>(R.id.tv_requests_badge)

        val rvFriends = view.findViewById<RecyclerView>(R.id.rv_friends)
        val rvRequests = view.findViewById<RecyclerView>(R.id.rv_requests)

        val includeEmptyState = view.findViewById<View>(R.id.include_empty_state)
        val tvEmptyTitle = includeEmptyState.findViewById<TextView>(R.id.tv_empty_title)
        val tvEmptyBody = includeEmptyState.findViewById<TextView>(R.id.tv_empty_body)
        val btnShareInvite = includeEmptyState.findViewById<Button>(R.id.btn_share_invite)
        val btnFindByUsername = includeEmptyState.findViewById<Button>(R.id.btn_find_by_username)

        val progressBarFriends = view.findViewById<ProgressBar>(R.id.progress_bar_friends)

        friendAdapter = FriendAdapter(onClick = { friend ->
            Log.d(TAG, "Opening a friend's profile from the friends list")
            findNavController().openProfileOf(friend.userId, friendAdapter.currentUserId)
        })
        requestAdapter = RequestAdapter(
            onAccept = { viewModel.acceptRequest(it.requestId) },
            onReject = { viewModel.rejectRequest(it.requestId) },
        )

        rvFriends.layoutManager = LinearLayoutManager(requireContext())
        rvFriends.adapter = friendAdapter

        rvRequests.layoutManager = LinearLayoutManager(requireContext())
        rvRequests.adapter = requestAdapter

        btnAddFriend.setOnClickListener {
            findNavController().navigate(R.id.action_friendsFragment_to_findFriendsFragment)
        }

        btnFindByUsername.setOnClickListener {
            findNavController().navigate(R.id.action_friendsFragment_to_findFriendsFragment)
        }

        btnShareInvite.setOnClickListener {
            Log.d(TAG, "Sharing a friend invite from friends")
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, getString(R.string.invite_share_text))
            }
            startActivity(Intent.createChooser(shareIntent, null))
        }

        btnToggleSearch.setOnClickListener {

            // this if statement toggles the search input field visibility
            if (etFriendSearch.visibility == View.VISIBLE) {
                etFriendSearch.visibility = View.GONE
                etFriendSearch.setText("")
            } else {
                etFriendSearch.visibility = View.VISIBLE
                etFriendSearch.requestFocus()
            }
        }

        etFriendSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.onSearchQueryChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnTabAllFriends.setOnClickListener { viewModel.selectTab(FriendsTab.ALL_FRIENDS) }
        btnTabFollowing.setOnClickListener { viewModel.selectTab(FriendsTab.FOLLOWING) }
        btnTabRequests.setOnClickListener { viewModel.selectTab(FriendsTab.REQUESTS) }

        // a tapped friend request notification opens straight on the requests. the argument is
        // removed once used, so coming back to this screen later starts on the usual tab
        if (arguments?.getString(PushNotifications.ARG_OPEN_TAB) == OPEN_TAB_REQUESTS) {
            arguments?.remove(PushNotifications.ARG_OPEN_TAB)
            viewModel.selectTab(FriendsTab.REQUESTS)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    progressBarFriends.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                    // this if statement displays a toast when an error message is present
                    if (state.errorMessage != null) {
                        Log.w(TAG, "Showing the friends error: ${resources.getResourceEntryName(state.errorMessage)}")
                        Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_SHORT).show()
                    }

                    // this if statement updates the incoming requests badge visibility and count
                    if (state.requests.isNotEmpty()) {
                        tvRequestsBadge.visibility = View.VISIBLE
                        tvRequestsBadge.text = state.requests.size.toString()
                    } else {
                        tvRequestsBadge.visibility = View.GONE
                    }

                    // this when expression updates tab backgrounds and text colors according to selectedTab
                    when (state.selectedTab) {
                        FriendsTab.ALL_FRIENDS -> {
                            btnTabAllFriends.setBackgroundResource(R.drawable.bg_pill_accent)
                            btnTabAllFriends.setTextColor(ContextCompat.getColor(requireContext(), R.color.page_background))
                            btnTabFollowing.setBackgroundResource(R.drawable.bg_pill_outline)
                            btnTabFollowing.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                            btnTabRequests.setBackgroundResource(R.drawable.bg_pill_outline)
                            btnTabRequests.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                        }
                        FriendsTab.FOLLOWING -> {
                            btnTabAllFriends.setBackgroundResource(R.drawable.bg_pill_outline)
                            btnTabAllFriends.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                            btnTabFollowing.setBackgroundResource(R.drawable.bg_pill_accent)
                            btnTabFollowing.setTextColor(ContextCompat.getColor(requireContext(), R.color.page_background))
                            btnTabRequests.setBackgroundResource(R.drawable.bg_pill_outline)
                            btnTabRequests.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                        }
                        FriendsTab.REQUESTS -> {
                            btnTabAllFriends.setBackgroundResource(R.drawable.bg_pill_outline)
                            btnTabAllFriends.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                            btnTabFollowing.setBackgroundResource(R.drawable.bg_pill_outline)
                            btnTabFollowing.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                            btnTabRequests.setBackgroundResource(R.drawable.bg_pill_accent)
                            btnTabRequests.setTextColor(ContextCompat.getColor(requireContext(), R.color.page_background))
                        }
                    }

                    // this when expression toggles recyclerviews and configures empty state views for the selected tab
                    when (state.selectedTab) {
                        FriendsTab.ALL_FRIENDS -> {
                            rvFriends.visibility = View.VISIBLE
                            rvRequests.visibility = View.GONE
                            friendAdapter.currentUserId = state.selfUserId
                            friendAdapter.showRank = state.friends.size > 1
                            friendAdapter.submitList(state.filteredFriends)

                            // this if statement shows the empty state card below the list when user has no real friends
                            if (state.hasNoFriends) {
                                includeEmptyState.visibility = View.VISIBLE
                                tvEmptyTitle.text = getString(R.string.no_friends_title_empty)
                                tvEmptyBody.text = getString(R.string.no_friends_body_empty)
                                btnShareInvite.visibility = View.VISIBLE
                                btnFindByUsername.visibility = View.VISIBLE
                            } else {
                                includeEmptyState.visibility = View.GONE
                            }
                        }
                        FriendsTab.FOLLOWING -> {
                            rvFriends.visibility = View.VISIBLE
                            rvRequests.visibility = View.GONE
                            friendAdapter.submitList(emptyList())

                            includeEmptyState.visibility = View.VISIBLE
                            tvEmptyTitle.text = getString(R.string.following_empty_title)
                            tvEmptyBody.text = getString(R.string.following_empty_body)
                            btnShareInvite.visibility = View.GONE
                            btnFindByUsername.visibility = View.GONE
                        }
                        FriendsTab.REQUESTS -> {
                            rvFriends.visibility = View.GONE
                            rvRequests.visibility = View.VISIBLE
                            requestAdapter.submitList(state.requests)
                            includeEmptyState.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "FriendsFragment"
    }
}
//------------------------------EOF------------------------------\\