package com.backlogbattlers.app.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.backlogbattlers.app.R
import com.backlogbattlers.app.adapters.BadgeAdapter
import com.backlogbattlers.app.adapters.ShowcaseAdapter
import com.backlogbattlers.app.domain.model.BadgeProgress
import com.backlogbattlers.app.domain.model.Profile
import com.backlogbattlers.app.domain.model.ShowcaseAchievement
import com.backlogbattlers.app.util.badgeInfo
import com.backlogbattlers.app.util.bindProfileAvatar
import com.backlogbattlers.app.util.daysSince
import com.backlogbattlers.app.util.formatPoints
import com.backlogbattlers.app.util.loadLandscapeArt
import com.backlogbattlers.app.util.setVisible
import com.backlogbattlers.app.util.updatedAgo
import com.backlogbattlers.app.viewmodels.ProfileViewModel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale

// the profile screen: the signed in user's name and picture, their level and XP, a preview of their
// badges (See all opens the rest), the rarest achievements, and the games they
// unlocked achievements in lately. the backend works the numbers out (see ProfileViewModel); this
// shows them, with a spinner while they load and a retry if they cannot. pull down to reload. the
// pencil opens edit profile and the gear opens settings
class ProfileFragment : Fragment() {

    // set when this is a friend's profile, opened from the friends list, the leaderboard or the feed; absent on
    // the profile tab, which is the signed in user's own
    private val friendId: String? get() = arguments?.getString(ARG_USER_ID)

    private val viewModel: ProfileViewModel by viewModels { ProfileViewModel.factory(friendId) }

    //------------------------------
    // inflates the profile fragment layout
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    //------------------------------
    // reloads a profile that is getting old each time the user comes back to this screen
    override fun onStart() {
        super.onStart()
        viewModel.refreshIfStale()
    }

    //------------------------------
    // wires the buttons, the two grids and pull to refresh, then shows each state of the screen
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val viewingFriend = friendId != null
        val editButton = view.findViewById<View>(R.id.btn_edit_profile)
        val settingsButton = view.findViewById<View>(R.id.btn_settings)
        val backButton = view.findViewById<View>(R.id.btn_profile_back)
        // a friend's profile has no pencil or gear, which are about your own, and a back arrow to where it came from
        editButton.setVisible(!viewingFriend)
        settingsButton.setVisible(!viewingFriend)
        backButton.setVisible(viewingFriend)
        backButton.setOnClickListener { findNavController().navigateUp() }
        editButton.setOnClickListener {
            Log.d(TAG, "Opening edit profile from the profile header")
            findNavController().navigate(R.id.action_profileFragment_to_editProfileFragment)
        }
        settingsButton.setOnClickListener {
            Log.d(TAG, "Opening settings from the profile header")
            findNavController().navigate(R.id.action_profileFragment_to_settingsFragment)
        }
        view.findViewById<View>(R.id.btn_badges_see_all).setOnClickListener {
            viewModel.uiState.value.profile?.let { showAllBadges(it) }
        }
        view.findViewById<View>(R.id.btn_profile_retry).setOnClickListener { viewModel.refresh() }

        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        swipe.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.accent))
        swipe.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(requireContext(), R.color.surface))
        swipe.setOnRefreshListener { viewModel.refresh(pulled = true) }

        val badgeAdapter = BadgeAdapter(onClick = ::showBadge)
        view.findViewById<RecyclerView>(R.id.rv_badges).apply {
            layoutManager = GridLayoutManager(requireContext(), BADGE_COLUMNS)
            adapter = badgeAdapter
        }
        val showcaseAdapter = ShowcaseAdapter(onClick = ::showAchievement)
        view.findViewById<RecyclerView>(R.id.rv_showcase).apply {
            layoutManager = GridLayoutManager(requireContext(), ShowcaseAdapter.COLUMNS)
            adapter = showcaseAdapter
        }

        val avatar = view.findViewById<View>(R.id.avatarProfile)
        val name = view.findViewById<TextView>(R.id.tvProfileName)
        val progress = view.findViewById<ProgressBar>(R.id.progress_profile)
        val errorCard = view.findViewById<View>(R.id.card_profile_error)
        val errorText = view.findViewById<TextView>(R.id.tv_profile_error)
        val content = view.findViewById<View>(R.id.content_profile)
        val xpGroup = view.findViewById<View>(R.id.group_xp)
        val savedLabel = view.findViewById<TextView>(R.id.tv_profile_saved)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    swipe.isRefreshing = state.isRefreshing

                    // your own name comes from the account at once; before anyone is known the header stays blank
                    name.text = state.displayName
                    avatar.bindProfileAvatar(state.pictureId, state.displayName)

                    // while what is shown is the copy saved on this phone, say when the server last answered
                    savedLabel.setVisible(state.savedAt != null)
                    state.savedAt?.let { savedLabel.text = getString(R.string.profile_saved_copy, requireContext().updatedAgo(it)) }

                    progress.setVisible(state.isLoading)
                    errorCard.setVisible(state.errorMessage != null)
                    state.errorMessage?.let { errorText.setText(it) }

                    val profile = state.profile
                    content.setVisible(profile != null)
                    xpGroup.setVisible(profile != null)
                    if (profile != null) {
                        bindProfile(view, profile, badgeAdapter, showcaseAdapter)
                    }

                    // this if statement shows a failed reload as a toast, so the profile already on screen stays
                    state.message?.let {
                        Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                        viewModel.messageShown()
                    }
                }
            }
        }
    }

    //------------------------------
    // fills every part of the screen that the profile decides
    private fun bindProfile(view: View, profile: Profile, badgeAdapter: BadgeAdapter, showcaseAdapter: ShowcaseAdapter) {
        view.findViewById<TextView>(R.id.tv_level).text = getString(R.string.profile_level, profile.level)
        view.findViewById<ProgressBar>(R.id.progress_xp).progress =
            if (profile.xpForNextLevel > 0) profile.xpIntoLevel * 100 / profile.xpForNextLevel else 0
        view.findViewById<TextView>(R.id.tv_xp_progress).text =
            getString(R.string.profile_xp_progress, formatPoints(profile.xpIntoLevel), formatPoints(profile.xpForNextLevel))

        // a friend who keeps their badges or showcase private: the section says so instead of looking empty
        view.findViewById<TextView>(R.id.tv_badges_earned).apply {
            setVisible(!profile.badgesHidden)
            text = getString(R.string.profile_badges_earned, profile.badgesEarned, profile.badges.size)
        }
        view.findViewById<View>(R.id.btn_badges_see_all).setVisible(!profile.badgesHidden)
        view.findViewById<View>(R.id.rv_badges).setVisible(!profile.badgesHidden)
        view.findViewById<View>(R.id.tv_badges_hidden).setVisible(profile.badgesHidden)
        badgeAdapter.submitList(sortedBadges(profile).take(BADGE_PREVIEW))

        view.findViewById<View>(R.id.rv_showcase).setVisible(!profile.showcaseHidden)
        view.findViewById<View>(R.id.tv_showcase_hidden).setVisible(profile.showcaseHidden)
        showcaseAdapter.achievements = profile.rarestAchievements

        bindRecentActivity(view, profile)
    }

    //------------------------------
    // earned badges first, then the ones closest to being earned
    private fun sortedBadges(profile: Profile): List<BadgeProgress> = profile.badges.sortedWith(
        compareByDescending<BadgeProgress> { it.earned }
            .thenByDescending { it.progress.toFloat() / it.target }
            .thenBy { it.id.ordinal },
    )

    //------------------------------
    // the bottom sheet See all opens: every badge, in the same order as the preview
    private fun showAllBadges(profile: Profile) {
        Log.d(TAG, "Opening all ${profile.badges.size} badges")
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(R.layout.sheet_badges)
        val list = dialog.findViewById<RecyclerView>(R.id.list_badges) ?: return
        dialog.findViewById<TextView>(R.id.tv_sheet_badges_earned)?.text =
            getString(R.string.profile_badges_earned, profile.badgesEarned, profile.badges.size)

        list.layoutManager = GridLayoutManager(requireContext(), BADGE_COLUMNS)
        list.adapter = BadgeAdapter(onClick = ::showBadge).also { it.submitList(sortedBadges(profile)) }

        // the sheet draws its own rounded background, so the dialog's square one is cleared
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    //------------------------------
    // one card for each game unlocked in lately, or the empty note
    private fun bindRecentActivity(view: View, profile: Profile) {
        view.findViewById<TextView>(R.id.tv_recent_window).text = getString(R.string.profile_recent_window, profile.unlockedRecently)
        val list = view.findViewById<LinearLayout>(R.id.list_recent)
        list.removeAllViews()
        list.setVisible(profile.recentActivity.isNotEmpty())
        view.findViewById<View>(R.id.row_recent_empty).setVisible(profile.recentActivity.isEmpty())

        val inflater = LayoutInflater.from(requireContext())
        val now = System.currentTimeMillis()
        profile.recentActivity.forEach { game ->
            val card = inflater.inflate(R.layout.item_recent_activity, list, false)
            card.findViewById<ImageView>(R.id.iv_recent_art).apply {
                clipToOutline = true
                loadLandscapeArt(game.artUrls)
            }
            card.findViewById<TextView>(R.id.tv_recent_title).text = game.gameTitle ?: getString(R.string.activity_unknown_game)
            card.findViewById<TextView>(R.id.tv_recent_subtitle).text =
                resources.getQuantityString(R.plurals.profile_recent_unlocked, game.unlockedCount, game.unlockedCount, whenText(game.lastUnlockedAt, now))
            card.findViewById<ProgressBar>(R.id.progress_recent).progress =
                if (game.achievementsTotal > 0) (game.achievementsUnlocked * 100 / game.achievementsTotal).coerceIn(0, 100) else 0
            card.findViewById<TextView>(R.id.tv_recent_progress).text =
                getString(R.string.profile_recent_progress_value, game.achievementsUnlocked, game.achievementsTotal)
            list.addView(card)
        }
    }

    // Today, Yesterday, or how many days ago
    private fun whenText(then: Long, now: Long): String = when (val days = daysSince(then, now)) {
        0 -> getString(R.string.profile_recent_today)
        1 -> getString(R.string.profile_recent_yesterday)
        else -> resources.getQuantityString(R.plurals.profile_recent_days_ago, days, days)
    }

    //------------------------------
    // what a tapped badge is, what earns it and how far along the user is
    private fun showBadge(badge: BadgeProgress) {
        val info = badgeInfo(badge.id)
        val progress = if (badge.earned) getString(R.string.profile_badge_earned)
        else getString(R.string.profile_badge_progress, badge.progress, badge.target)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(info.name)
            .setMessage(getString(info.description) + "\n\n" + progress)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    //------------------------------
    // a tapped showcase achievement: its name, the game and how rare it is
    private fun showAchievement(achievement: ShowcaseAchievement) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(achievement.name)
            .setMessage(getString(R.string.profile_showcase_detail, achievement.gameTitle ?: getString(R.string.activity_unknown_game), percentText(achievement.rarityPercent)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // 0.4 is "0.4", and 12.0 is "12", so a whole percentage has no trailing ".0"
    private fun percentText(percent: Double): String =
        if (percent % 1.0 == 0.0) percent.toInt().toString() else String.format(Locale.getDefault(), "%.1f", percent)

    companion object {
        private const val TAG = "ProfileFragment"
        private const val BADGE_COLUMNS = 4

        // the navigation argument holding the friend whose profile this is
        const val ARG_USER_ID = "userId"

        // the badges the card shows before See all
        private const val BADGE_PREVIEW = 4
    }
}
//------------------------------EOF------------------------------\
