package com.backlogbattlers.app.fragments

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.backlogbattlers.app.R
import com.backlogbattlers.app.adapters.ProfilePictureAdapter
import com.backlogbattlers.app.util.PROFILE_PICTURES
import com.backlogbattlers.app.util.bindProfileAvatar
import com.backlogbattlers.app.util.bindSettingsHeader
import com.backlogbattlers.app.util.bindSettingsToggle
import com.backlogbattlers.app.util.setSettingsToggleChecked
import com.backlogbattlers.app.util.setVisible
import com.backlogbattlers.app.viewmodels.EditProfileEvent
import com.backlogbattlers.app.viewmodels.EditProfileUiState
import com.backlogbattlers.app.viewmodels.EditProfileViewModel
import com.backlogbattlers.app.viewmodels.MAX_DISPLAY_NAME_LENGTH
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

// edit profile, opened from the profile screen's header and from settings: the user's picture
// (picked from the app's own set, there is no camera yet), their display name and which profile
// sections friends see. it is all a draft until Save, so leaving with changes asks first
class EditProfileFragment : Fragment() {

    private val viewModel: EditProfileViewModel by viewModels()

    private lateinit var nameField: EditText
    private lateinit var nameCount: TextView
    private lateinit var nameHelper: TextView
    private lateinit var saveButton: TextView
    private lateinit var removeButton: TextView
    private lateinit var avatar: View
    private lateinit var toggles: List<Pair<View, (EditProfileUiState) -> Boolean>>

    private var draftShown = false

    // only catches back while there is something to lose
    private val discardGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = confirmDiscard()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_edit_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, discardGuard)

        nameField = view.findViewById(R.id.etDisplayName)
        nameCount = view.findViewById(R.id.tvDisplayNameCount)
        nameHelper = view.findViewById(R.id.tvDisplayNameHelper)
        saveButton = view.findViewById(R.id.btnSaveProfile)
        removeButton = view.findViewById(R.id.btnRemovePhoto)
        avatar = view.findViewById(R.id.avatarEditProfile)

        view.findViewById<View>(R.id.btnEditProfileBack).setOnClickListener { leave() }
        saveButton.setOnClickListener {
            Log.d(TAG, "Save tapped")
            viewModel.save()
        }
        view.findViewById<View>(R.id.btnChangePhoto).setOnClickListener { showPicturePicker() }
        view.findViewById<View>(R.id.btnAvatarCamera).setOnClickListener { showPicturePicker() }
        removeButton.setOnClickListener { viewModel.removePicture() }

        view.findViewById<View>(R.id.headerDisplayName).bindSettingsHeader(R.string.edit_profile_display_name)
        view.findViewById<View>(R.id.headerShownOnProfile).bindSettingsHeader(R.string.edit_profile_section_shown)

        nameField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.onNameChanged(s?.toString().orEmpty())
            }
        })

        toggles = listOf(
            bindVisibilityToggle(view, R.id.toggleShowBadges, R.string.profile_show_badges, R.string.profile_show_badges_body, viewModel::setShowBadges) { it.showBadges },
            bindVisibilityToggle(view, R.id.toggleShowShowcase, R.string.profile_show_showcase, R.string.profile_show_showcase_body, viewModel::setShowShowcase) { it.showShowcase },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.events.collect { onEvent(it) } }
            }
        }
    }

    //------------------------------
    // one "shown on your profile" row. these have no icon, and a bold title as in the design
    private fun bindVisibilityToggle(
        root: View,
        id: Int,
        @StringRes title: Int,
        @StringRes body: Int,
        onChange: (Boolean) -> Unit,
        current: (EditProfileUiState) -> Boolean,
    ): Pair<View, (EditProfileUiState) -> Boolean> {
        val row = root.findViewById<View>(id)
        row.bindSettingsToggle(icon = null, title = title, body = body, checked = current(viewModel.uiState.value), onCheckedChange = onChange)
        row.findViewById<TextView>(R.id.tvSettingsToggleTitle).setTypeface(null, Typeface.BOLD)
        return row to current
    }

    private fun render(state: EditProfileUiState) {
        // the saved name goes into the field once; after that the field is the source of the draft
        if (state.loaded && !draftShown) {
            nameField.setText(state.name)
            nameField.setSelection(nameField.text.length)
            draftShown = true
        }

        avatar.bindProfileAvatar(state.customization.pictureId, state.name.ifBlank { state.savedName })
        nameCount.text = getString(R.string.edit_profile_name_count, state.name.length, MAX_DISPLAY_NAME_LENGTH)

        // the helper line turns into the problem with the name, in red, while there is one
        val nameError = state.nameError
        nameHelper.setText(nameError ?: R.string.edit_profile_name_helper)
        nameHelper.setTextColor(requireContext().getColor(if (nameError != null) R.color.destructive else R.color.text_tertiary))
        nameCount.setTextColor(requireContext().getColor(if (nameError != null) R.color.destructive else R.color.text_tertiary))
        toggles.forEach { (row, current) -> row.setSettingsToggleChecked(current(state)) }

        val canRemove = state.customization.pictureId != null
        removeButton.isEnabled = canRemove
        removeButton.alpha = if (canRemove) 1f else 0.5f

        val canSave = state.loaded && state.hasChanges && !state.saving && nameError == null
        saveButton.isEnabled = canSave
        saveButton.alpha = if (canSave) 1f else 0.5f

        discardGuard.isEnabled = state.hasChanges && !state.saving
    }

    private fun onEvent(event: EditProfileEvent) {
        when (event) {
            EditProfileEvent.Saved -> {
                hideKeyboard()
                Toast.makeText(requireContext(), R.string.edit_profile_saved, Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
            is EditProfileEvent.Rejected -> {
                Log.d(TAG, "Showing ${resources.getResourceEntryName(event.message)}")
                Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    //------------------------------
    // the bottom sheet of pictures to pick from. picking one closes it; nothing is saved until Save
    private fun showPicturePicker() {
        Log.d(TAG, "Opening the picture picker with ${PROFILE_PICTURES.size} pictures")
        hideKeyboard()
        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(R.layout.sheet_profile_pictures)
        val list = dialog.findViewById<RecyclerView>(R.id.listProfilePictures) ?: return

        list.layoutManager = GridLayoutManager(requireContext(), PICKER_COLUMNS)
        list.adapter = ProfilePictureAdapter(
            pictures = PROFILE_PICTURES,
            selectedId = viewModel.uiState.value.customization.pictureId,
        ) { picture ->
            viewModel.choosePicture(picture.id)
            dialog.dismiss()
        }
        list.setVisible(PROFILE_PICTURES.isNotEmpty())
        dialog.findViewById<View>(R.id.tvProfilePicturesEmpty)?.setVisible(PROFILE_PICTURES.isEmpty())

        // the sheet draws its own rounded background, so the dialog's square one is cleared
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun leave() {
        if (viewModel.uiState.value.hasChanges) confirmDiscard() else findNavController().navigateUp()
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_profile_discard_title)
            .setMessage(R.string.edit_profile_discard_body)
            .setNegativeButton(R.string.edit_profile_keep_editing, null)
            .setPositiveButton(R.string.edit_profile_discard) { _, _ ->
                Log.d(TAG, "Unsaved profile changes discarded")
                hideKeyboard()
                findNavController().navigateUp()
            }
            .show()
    }

    private fun hideKeyboard() {
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(nameField.windowToken, 0)
        nameField.clearFocus()
    }

    private companion object {
        const val TAG = "EditProfileFragment"
        const val PICKER_COLUMNS = 4
    }
}
//------------------------------EOF------------------------------\\
