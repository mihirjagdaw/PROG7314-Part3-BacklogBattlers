package com.backlogbattlers.app.fragments

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.backlogbattlers.app.R
import com.backlogbattlers.app.adapters.AchievementAdapter
import com.backlogbattlers.app.adapters.AchievementStatusAdapter
import com.backlogbattlers.app.adapters.GameDetailHeaderAdapter
import com.backlogbattlers.app.domain.model.CompleteOutcome
import com.backlogbattlers.app.domain.model.CompletionType
import com.backlogbattlers.app.domain.model.LibraryStatus
import com.backlogbattlers.app.util.HoursInput
import com.backlogbattlers.app.util.statusChoices
import com.backlogbattlers.app.util.statusLabel
import com.backlogbattlers.app.util.completionDialogText
import com.backlogbattlers.app.util.formatHoursPlayed
import com.backlogbattlers.app.util.parseHours
import com.backlogbattlers.app.viewmodels.GameDetailUiState
import com.backlogbattlers.app.viewmodels.GameDetailViewModel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

private const val ARG_GAME_ID = "gameId"

// the game detail screen: a game's header stats and its full achievement list. Every achievement
// is tappable, unlocking it live earns its points and moves the completion bar.
//
// The whole page is one RecyclerView (see fragment_game_detail.xml): the header, the loading/
// error row and the achievement rows are three adapters joined with ConcatAdapter, rather than a
// header ScrollView wrapping a nested achievements RecyclerView, which does not reliably grow
// past its first measured height once achievements arrive asynchronously
class GameDetailFragment : Fragment() {

    private val viewModel: GameDetailViewModel by viewModels()

    private lateinit var headerAdapter: GameDetailHeaderAdapter
    private lateinit var statusAdapter: AchievementStatusAdapter
    private lateinit var achievementAdapter: AchievementAdapter

    private var isFavourite = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_game_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        headerAdapter = GameDetailHeaderAdapter(
            onBack = { findNavController().navigateUp() },
            onFavouriteToggle = ::toggleFavourite,
            onComplete = ::showCompleteSheet,
            onStatusClick = ::showStatusMenu,
            onEditHours = ::showHoursDialog,
        )
        statusAdapter = AchievementStatusAdapter(onRetry = viewModel::retryAchievements)
        achievementAdapter = AchievementAdapter(onToggle = viewModel::toggleAchievement)

        view.findViewById<RecyclerView>(R.id.list_detail_content).adapter =
            ConcatAdapter(headerAdapter, statusAdapter, achievementAdapter)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }

        val gameId = arguments?.getString(ARG_GAME_ID)?.toIntOrNull()
        if (gameId != null) {
            viewModel.load(gameId)
        } else {
            Log.e(TAG, "Opened without a valid game id: ${arguments?.getString(ARG_GAME_ID)}")
        }
    }

    private fun render(state: GameDetailUiState) {
        headerAdapter.submitState(state)

        statusAdapter.setMode(
            when {
                state.achievementsLoading -> AchievementStatusAdapter.Mode.LOADING
                state.achievementsFailed -> AchievementStatusAdapter.Mode.ERROR
                else -> AchievementStatusAdapter.Mode.NONE
            },
        )
        achievementAdapter.submitList(state.achievements)

        state.message?.let {
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            viewModel.messageShown()
        }
        state.completionOutcome?.let {
            showOutcome(it)
            viewModel.outcomeShown()
        }
    }

    // the bottom sheet "Mark as complete" opens: the story, or 100%. one that is not possible is dimmed
    // and says what is missing. picking one asks to confirm first, since a completion cannot be undone
    private fun showCompleteSheet() {
        val state = viewModel.uiState.value
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(R.layout.sheet_complete)
        val story = dialog.findViewById<View>(R.id.row_complete_story) ?: return
        val full = dialog.findViewById<View>(R.id.row_complete_full) ?: return

        dialog.findViewById<TextView>(R.id.tv_complete_story_hint)?.setText(
            if (state.canCompleteStory) R.string.complete_story_hint else R.string.complete_done_hint,
        )
        story.alpha = if (state.canCompleteStory) 1f else 0.5f
        story.isEnabled = state.canCompleteStory
        if (state.canCompleteStory) story.setOnClickListener { dialog.dismiss(); confirmComplete(CompletionType.MAIN_STORY) }

        dialog.findViewById<TextView>(R.id.tv_complete_full_hint)?.text = when {
            state.hundredPercentCompleted -> getString(R.string.complete_done_hint)
            state.totalCount == 0 -> getString(R.string.complete_hundred_none)
            !state.allUnlocked -> getString(R.string.complete_hundred_locked, state.totalCount, state.totalCount - state.unlockedCount)
            else -> getString(R.string.complete_hundred_ready)
        }
        full.alpha = if (state.canCompleteHundredPercent) 1f else 0.5f
        full.isEnabled = state.canCompleteHundredPercent
        if (state.canCompleteHundredPercent) full.setOnClickListener { dialog.dismiss(); confirmComplete(CompletionType.COMPLETIONIST) }

        // the sheet draws its own rounded background, so the dialog's square one is cleared
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    // the dropdown under the status tile: not started, in progress or abandoned, the current one ticked. picking
    // one saves it on the phone straight away, and the library sync uploads it
    private fun showStatusMenu(anchor: View) {
        val current = viewModel.uiState.value.status ?: LibraryStatus.BACKLOG
        val choices = statusChoices(current)
        val menu = PopupMenu(requireContext(), anchor)
        choices.forEachIndexed { index, status ->
            menu.menu.add(0, index, index, statusLabel(status)).apply {
                isCheckable = true
                isChecked = status == current
            }
        }
        menu.menu.setGroupCheckable(0, true, true)
        menu.setOnMenuItemClickListener { item ->
            val picked = choices[item.itemId]
            Log.d(TAG, "Status picked: $picked")
            viewModel.setStatus(picked)
            true
        }
        menu.show()
    }

    // asks how many hours the player has played. the number is checked as it is saved: a field that is
    // empty, not a number, negative or past the limit says what is wrong and the dialog stays open
    private fun showHoursDialog() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val field = TextInputLayout(context).apply { hint = getString(R.string.hours_dialog_hint) }
        val input = TextInputEditText(field.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            viewModel.uiState.value.hoursPlayed?.takeIf { it > 0f }?.let { setText(formatHoursPlayed(it)) }
        }
        field.addView(input)
        val container = FrameLayout(context).apply {
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            addView(field)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.hours_dialog_title)
            .setMessage(R.string.hours_dialog_message)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.hours_dialog_save, null)
            .create()
        // the save button is wired after the dialog shows, so a bad number does not close it
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = parseHours(input.text?.toString().orEmpty())
                val error = hoursError(parsed)
                if (parsed is HoursInput.Valid && error == null) {
                    Log.d(TAG, "Logging ${parsed.hours} hours")
                    viewModel.setHours(parsed.hours)
                    dialog.dismiss()
                } else {
                    field.error = error?.let { getString(it) }
                }
            }
        }
        dialog.show()
    }

    // the message for a number that can't be saved, or null for one that can
    private fun hoursError(input: HoursInput): Int? = when (input) {
        is HoursInput.Valid -> null
        HoursInput.Empty -> R.string.hours_error_empty
        HoursInput.NotANumber -> R.string.hours_error_format
        HoursInput.Negative -> R.string.hours_error_negative
        HoursInput.TooLarge -> R.string.hours_error_too_large
    }

    private fun confirmComplete(type: CompletionType) {
        Log.d(TAG, "Asking to confirm completing the game as $type")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.complete_confirm_title)
            .setMessage(if (type == CompletionType.COMPLETIONIST) R.string.complete_confirm_hundred else R.string.complete_confirm_story)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.complete_confirm_yes) { _, _ -> viewModel.complete(type) }
            .show()
    }

    private fun showOutcome(outcome: CompleteOutcome) {
        val (title, message) = completionDialogText(requireContext(), outcome)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toggleFavourite() {
        isFavourite = !isFavourite
        headerAdapter.setFavourite(isFavourite)
        Log.d(TAG, "Favourite turned ${if (isFavourite) "on" else "off"}")
    }

    private companion object {
        const val TAG = "GameDetailFragment"
    }
}
//------------------------------EOF------------------------------\\
