package com.backlogbattlers.app.profile

import android.app.Activity
import com.backlogbattlers.app.R
import com.backlogbattlers.app.data.repository.AuthRepository
import com.backlogbattlers.app.data.repository.AuthResult
import com.backlogbattlers.app.data.repository.DeleteAccountResult
import com.backlogbattlers.app.data.repository.ProfileCustomizationRepository
import com.backlogbattlers.app.data.repository.ProfileSaveResult
import com.backlogbattlers.app.domain.model.ProfileCustomization
import com.backlogbattlers.app.domain.model.User
import com.backlogbattlers.app.domain.model.UserSettings
import com.backlogbattlers.app.search.FakeSettingsRepository
import com.backlogbattlers.app.viewmodels.EditProfileEvent
import com.backlogbattlers.app.viewmodels.EditProfileViewModel
import com.backlogbattlers.app.viewmodels.MAX_DISPLAY_NAME_LENGTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditProfileViewModelTest {

    private val auth = FakeAuthRepository()
    private val customization = FakeProfileCustomizationRepository()
    private val settings = FakeSettingsRepository()
    // how many times the settings were uploaded to the account
    private var uploads = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = EditProfileViewModel(auth, customization, settings, uploadSettings = { uploads++ })

    @Test
    fun `the draft starts from the saved name and setup, with nothing to save`() = runTest {
        customization.saved.value = ProfileCustomization(pictureId = "knight")
        settings.settings.value = UserSettings(showBadges = false)

        val state = viewModel().uiState.value

        assertTrue(state.loaded)
        assertEquals("Alex", state.name)
        assertEquals("knight", state.customization.pictureId)
        assertFalse(state.showBadges)
        assertTrue(state.showShowcase)
        assertFalse(state.hasChanges)
    }

    @Test
    fun `changing anything makes the draft unsaved, and changing it back does not`() = runTest {
        val vm = viewModel()

        vm.setShowShowcase(false)
        assertTrue(vm.uiState.value.hasChanges)
        vm.setShowShowcase(true)
        assertFalse(vm.uiState.value.hasChanges)

        vm.onNameChanged("Alex  ")
        assertFalse("surrounding spaces are not a change", vm.uiState.value.hasChanges)
        vm.onNameChanged("Sam")
        assertTrue(vm.uiState.value.hasChanges)
    }

    @Test
    fun `nothing is stored until save`() = runTest {
        val vm = viewModel()

        vm.choosePicture("wizard")
        vm.setShowBadges(false)
        vm.onNameChanged("Sam")

        assertEquals(ProfileCustomization(), customization.saved.value)
        assertEquals("Alex", auth.displayName)
        assertTrue(settings.settings.value.showBadges)
        assertEquals(0, uploads)
    }

    @Test
    fun `save stores the setup and the trimmed new name, then reports it`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<EditProfileEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.choosePicture("wizard")
        vm.setShowShowcase(false)
        vm.onNameChanged("  Sam ")
        vm.save()

        assertEquals(ProfileCustomization(pictureId = "wizard"), customization.saved.value)
        assertFalse(settings.settings.value.showShowcase)
        assertEquals(1, uploads) // the account is told, so friends' views honour it
        assertEquals("Sam", auth.displayName)
        assertEquals("wizard", auth.pictureOnAccount) // the account has it too, not just this phone
        assertEquals(listOf<EditProfileEvent>(EditProfileEvent.Saved), events)
        assertFalse(vm.uiState.value.hasChanges)
    }

    @Test
    fun `an unchanged name and picture are not sent to the account again`() = runTest {
        val vm = viewModel()

        vm.setShowBadges(false)
        vm.save()

        assertEquals(0, auth.writes)
        assertFalse(settings.settings.value.showBadges)
        assertEquals(1, uploads)
    }

    @Test
    fun `settings are only uploaded when a section switch changed`() = runTest {
        val vm = viewModel()

        vm.choosePicture("wizard")
        vm.onNameChanged("Sam")
        vm.save()

        assertEquals(0, uploads)
        assertTrue(settings.settings.value.showBadges)
        assertTrue(settings.settings.value.showShowcase)
    }

    @Test
    fun `switching a section off and back on again is not a change`() = runTest {
        val vm = viewModel()

        vm.setShowBadges(false)
        vm.setShowBadges(true)
        vm.save()

        assertFalse(vm.uiState.value.hasChanges)
        assertEquals(0, uploads)
    }

    // ---- the name, as it is typed ----

    @Test
    fun `a name that is fine has no error`() = runTest {
        val vm = viewModel()

        vm.onNameChanged("Sam")

        assertNull(vm.uiState.value.nameError)
    }

    @Test
    fun `the saved name is not called out before it is touched, even when empty`() = runTest {
        auth.user = User("u1", "", "alex@example.com", null, 0, 1, 0, null)

        val vm = viewModel()

        assertNull(vm.uiState.value.nameError)
    }

    @Test
    fun `clearing the name shows the empty error at once, and typing again clears it`() = runTest {
        val vm = viewModel()

        vm.onNameChanged("")
        assertEquals(R.string.edit_profile_name_empty, vm.uiState.value.nameError)
        vm.onNameChanged("   ")
        assertEquals(R.string.edit_profile_name_empty, vm.uiState.value.nameError)
        vm.onNameChanged("S")
        assertNull(vm.uiState.value.nameError)
    }

    @Test
    fun `a name past the limit shows the too long error, and exactly the limit is fine`() = runTest {
        val vm = viewModel()

        vm.onNameChanged("x".repeat(MAX_DISPLAY_NAME_LENGTH))
        assertNull(vm.uiState.value.nameError)
        vm.onNameChanged("x".repeat(MAX_DISPLAY_NAME_LENGTH + 1))
        assertEquals(R.string.edit_profile_name_too_long, vm.uiState.value.nameError)
    }

    @Test
    fun `spaces around a name at the limit do not count towards it`() = runTest {
        val vm = viewModel()

        vm.onNameChanged("  " + "x".repeat(MAX_DISPLAY_NAME_LENGTH) + "  ")

        assertNull(vm.uiState.value.nameError)
    }

    @Test
    fun `only what changed is sent to the account`() = runTest {
        customization.saved.value = ProfileCustomization(pictureId = "knight")

        viewModel().apply { onNameChanged("Sam"); save() }
        assertEquals("Sam" to null, auth.lastSent) // the picture did not change

        viewModel().apply { choosePicture("wizard"); save() }
        assertEquals(null to "wizard", auth.lastSent) // the name did not change

        viewModel().apply { removePicture(); save() }
        assertEquals(null to "", auth.lastSent) // an empty id takes it away
        assertNull(auth.pictureOnAccount)
    }

    @Test
    fun `when the account cannot be reached nothing is kept on this phone, and the screen says so`() = runTest {
        auth.result = ProfileSaveResult.NO_CONNECTION
        val vm = viewModel()
        val events = mutableListOf<EditProfileEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.choosePicture("wizard")
        vm.onNameChanged("Sam")
        vm.save()

        assertEquals(listOf<EditProfileEvent>(EditProfileEvent.Rejected(R.string.edit_profile_no_connection)), events)
        assertEquals(ProfileCustomization(), customization.saved.value)
        assertEquals("Alex", auth.displayName)
        assertFalse(vm.uiState.value.saving)
        assertTrue("the draft is kept, so the user can try again", vm.uiState.value.hasChanges)

        // and once the account answers, the same draft saves
        auth.result = ProfileSaveResult.SAVED
        vm.save()
        assertEquals("Sam", auth.displayName)
        assertEquals("wizard", customization.saved.value.pictureId)
    }

    @Test
    fun `a refusal or failure from the account is its own message`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<EditProfileEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.onNameChanged("Sam")

        auth.result = ProfileSaveResult.REJECTED
        vm.save()
        auth.result = ProfileSaveResult.FAILED
        vm.save()
        auth.result = ProfileSaveResult.NOT_SIGNED_IN
        vm.save()

        assertEquals(
            listOf(
                EditProfileEvent.Rejected(R.string.edit_profile_save_failed),
                EditProfileEvent.Rejected(R.string.edit_profile_save_failed),
                EditProfileEvent.Rejected(R.string.edit_profile_signed_out),
            ),
            events,
        )
        assertEquals("Alex", auth.displayName)
    }

    @Test
    fun `removing the picture goes back to the initial`() = runTest {
        customization.saved.value = ProfileCustomization(pictureId = "knight")
        val vm = viewModel()

        vm.removePicture()
        vm.save()

        assertNull(customization.saved.value.pictureId)
    }

    @Test
    fun `an empty or too long name is refused and nothing is stored`() = runTest {
        val vm = viewModel()
        val events = mutableListOf<EditProfileEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.choosePicture("wizard")
        vm.onNameChanged("   ")
        vm.save()
        vm.onNameChanged("x".repeat(21))
        vm.save()

        assertEquals(
            listOf(
                EditProfileEvent.Rejected(R.string.edit_profile_name_empty),
                EditProfileEvent.Rejected(R.string.edit_profile_name_too_long),
            ),
            events,
        )
        assertEquals(ProfileCustomization(), customization.saved.value)
        assertEquals("Alex", auth.displayName)
    }

    @Test
    fun `signed out, saving is refused`() = runTest {
        auth.user = null
        val vm = viewModel()
        val events = mutableListOf<EditProfileEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.onNameChanged("Sam")
        vm.save()

        assertEquals(listOf<EditProfileEvent>(EditProfileEvent.Rejected(R.string.edit_profile_signed_out)), events)
        assertEquals(ProfileCustomization(), customization.observeCustomization().first())
    }

    private class FakeAuthRepository : AuthRepository {
        var user: User? = User("u1", "Alex", "alex@example.com", null, 0, 1, 0, null)
        // what the account holds, what was last sent to it as (name, picture), and how it answers
        var displayName = "Alex"
        var pictureOnAccount: String? = null
        var writes = 0
        var lastSent: Pair<String?, String?>? = null
        var result = ProfileSaveResult.SAVED

        override fun observeCurrentUser(): Flow<User?> = MutableStateFlow(user)
        override suspend fun getCurrentUser(): User? = user
        override suspend fun signInWithGoogle(activity: Activity): AuthResult = AuthResult.Failure(R.string.sign_in_failed)
        override suspend fun updateProfile(displayName: String?, pictureId: String?): ProfileSaveResult {
            if (user == null) return ProfileSaveResult.NOT_SIGNED_IN
            writes++
            lastSent = displayName to pictureId
            if (result == ProfileSaveResult.SAVED) {
                displayName?.let { this.displayName = it }
                pictureId?.let { pictureOnAccount = it.ifEmpty { null } }
            }
            return result
        }
        override suspend fun signOut() = Unit
        override suspend fun freshAccessToken(): String? = null
        override suspend fun renewAccessToken(): String? = null
        override suspend fun endSession() = Unit
        override suspend fun deleteAccount(): DeleteAccountResult = DeleteAccountResult.FAILED
    }

    private class FakeProfileCustomizationRepository : ProfileCustomizationRepository {
        val saved = MutableStateFlow(ProfileCustomization())

        override fun observeCustomization(): Flow<ProfileCustomization> = saved
        override suspend fun save(customization: ProfileCustomization) {
            saved.value = customization
        }
        override suspend fun clear(userId: String) {
            saved.value = ProfileCustomization()
        }
    }
}
