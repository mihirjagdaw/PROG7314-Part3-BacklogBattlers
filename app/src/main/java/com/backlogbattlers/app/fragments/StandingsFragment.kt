package com.backlogbattlers.app.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.backlogbattlers.app.R
import com.backlogbattlers.app.adapters.LeaderboardAdapter
import com.backlogbattlers.app.util.openProfileOf
import com.backlogbattlers.app.viewmodels.StandingsViewModel
import kotlinx.coroutines.launch

// basic prototype screen for the monthly leaderboard: a plain ranked list, reached from Home.
// no podium graphic or group filter yet - see the Part 1 design doc for the fuller version.
class StandingsFragment : Fragment() {

    private val viewModel: StandingsViewModel by viewModels()
    private lateinit var adapter: LeaderboardAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_standings, container, false)
    }

    //------------------------------
    // initializes views, the adapter, the back button, and collects ui state updates
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnBack = view.findViewById<ImageButton>(R.id.btn_back)
        val rvStandings = view.findViewById<RecyclerView>(R.id.rv_standings)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar_standings)
        val tvMessage = view.findViewById<TextView>(R.id.tv_standings_message)

        adapter = LeaderboardAdapter(onClick = { entry ->
            Log.d(TAG, "Opening a player's profile from the standings")
            findNavController().openProfileOf(entry.userId, viewModel.uiState.value.selfUserId)
        })
        rvStandings.layoutManager = LinearLayoutManager(requireContext())
        rvStandings.adapter = adapter

        btnBack.setOnClickListener { findNavController().navigateUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                    // this if statement shows an error as a toast rather than replacing the whole screen for it
                    if (state.errorMessage != null) {
                        Log.w(TAG, "Showing the standings error: ${resources.getResourceEntryName(state.errorMessage)}")
                        Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_SHORT).show()
                    }

                    adapter.currentUserId = state.selfUserId
                    adapter.submitList(state.entries)

                    // this if statement shows the empty-state message only once loading is done and there really is nothing to rank
                    if (state.isEmpty) {
                        rvStandings.visibility = View.GONE
                        tvMessage.visibility = View.VISIBLE
                        tvMessage.text = getString(R.string.standings_empty_body)
                    } else {
                        rvStandings.visibility = View.VISIBLE
                        tvMessage.visibility = View.GONE
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "StandingsFragment"
    }
}
//------------------------------EOF------------------------------\\
