package com.backlogbattlers.app.viewmodels

import android.app.Activity
import androidx.biometric.BiometricPrompt
import com.backlogbattlers.app.R
import com.backlogbattlers.app.data.repository.AuthRepository
import com.backlogbattlers.app.data.repository.DeleteAccountResult
import com.backlogbattlers.app.data.repository.ProfileSaveResult
import com.backlogbattlers.app.data.repository.AuthResult
import com.backlogbattlers.app.data.repository.SettingsRepository
import com.backlogbattlers.app.domain.model.AppLanguage
import com.backlogbattlers.app.domain.model.HomeBackdrop
import com.backlogbattlers.app.domain.model.Platform
import com.backlogbattlers.app.domain.model.ThemeMode
import com.backlogbattlers.app.domain.model.User
import com.backlogbattlers.app.domain.model.UserSettings
import com.backlogbattlers.app.util.BiometricLockReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

// unit tests for BiometricLockViewModel state transitions and repositories interactions
class BiometricLockViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    //------------------------------
    // sets up test main dispatcher before each test
    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    //------------------------------
    // resets main dispatcher after each test
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    //------------------------------
    // verifies initial ui state is Idle
    fun `initial uiState is Idle`() = runTest {
        val viewModel = BiometricLockViewModel(FakeAuthRepository(), FakeSettingsRepository())
        assertEquals(BiometricLockUiState.Idle, viewModel.uiState.value)
    }

    @Test
    //------------------------------
    // verifies onAuthenticationStarted updates uiState to Authenticating
    fun `onAuthenticationStarted updates state to Authenticating`() = runTest {
        val viewModel = BiometricLockViewModel(FakeAuthRepository(), FakeSettingsRepository())
        viewModel.onAuthenticationStarted()
        assertEquals(BiometricLockUiState.Authenticating, viewModel.uiState.value)
    }

    @Test
    //------------------------------
    // verifies onAuthenticationSucceeded updates uiState to Success
    fun `onAuthenticationSucceeded updates state to Success`() = runTest {
        val viewModel = BiometricLockViewModel(FakeAuthRepository(), FakeSettingsRepository())
        viewModel.onAuthenticationSucceeded()
        assertEquals(BiometricLockUiState.Success, viewModel.uiState.value)
    }

    @Test
    //------------------------------
    // verifies cancelling the prompt keeps the session and offers to try again or sign in again
    fun `cancelling the prompt keeps the user signed in and locked`() = runTest {
        val fakeAuth = FakeAuthRepository()
        val viewModel = BiometricLockViewModel(fakeAuth, FakeSettingsRepository())

        viewModel.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED)

        assertFalse(fakeAuth.signOutCalled)
        assertEquals(BiometricLockUiState.Locked(BiometricLockReason.CANCELLED), viewModel.uiState.value)
    }

    @Test
    //------------------------------
    // verifies too many failed scans explains the lockout instead of signing out
    fun `too many failed scans locks the screen with the lockout reason`() = runTest {
        val fakeAuth = FakeAuthRepository()
        val viewModel = BiometricLockViewModel(fakeAuth, FakeSettingsRepository())

        viewModel.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT)

        assertFalse(fakeAuth.signOutCalled)
        assertEquals(BiometricLockUiState.Locked(BiometricLockReason.LOCKED_OUT), viewModel.uiState.value)
    }

    @Test
    //------------------------------
    // verifies a phone that can't unlock keeps the screen locked and says why, without signing out
    fun `a phone that cannot unlock stays locked with the reason`() = runTest {
        val fakeAuth = FakeAuthRepository()
        val viewModel = BiometricLockViewModel(fakeAuth, FakeSettingsRepository())

        viewModel.onCannotUnlock(BiometricLockReason.UNAVAILABLE)

        assertFalse(fakeAuth.signOutCalled)
        assertEquals(BiometricLockUiState.Locked(BiometricLockReason.UNAVAILABLE), viewModel.uiState.value)
    }

    @Test
    //------------------------------
    // verifies a changed fingerprint, or an unconfirmed unlock, never counts as success
    fun `a changed fingerprint or an unconfirmed unlock does not unlock`() = runTest {
        val viewModel = BiometricLockViewModel(FakeAuthRepository(), FakeSettingsRepository())

        viewModel.onCannotUnlock(BiometricLockReason.CHANGED)
        assertEquals(BiometricLockUiState.Locked(BiometricLockReason.CHANGED), viewModel.uiState.value)

        viewModel.onCannotUnlock(BiometricLockReason.NOT_VERIFIED)
        assertEquals(BiometricLockUiState.Locked(BiometricLockReason.NOT_VERIFIED), viewModel.uiState.value)
    }

    @Test
    //------------------------------
    // verifies a locked screen can still be unlocked by trying again once the phone can
    fun `a locked screen unlocks when a retry succeeds`() = runTest {
        val viewModel = BiometricLockViewModel(FakeAuthRepository(), FakeSettingsRepository())
        viewModel.onCannotUnlock(BiometricLockReason.UNAVAILABLE)

        viewModel.onAuthenticationStarted()
        viewModel.onAuthenticationSucceeded()

        assertEquals(BiometricLockUiState.Success, viewModel.uiState.value)
    }

    @Test
    //------------------------------
    // verifies choosing sign in again ends the session but leaves fingerprint unlock on
    fun `signing in again signs out and keeps fingerprint unlock on`() = runTest {
        val fakeAuth = FakeAuthRepository()
        val fakeSettings = FakeSettingsRepository()
        val viewModel = BiometricLockViewModel(fakeAuth, fakeSettings)
        viewModel.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED)

        viewModel.onSignInAgain()

        assertTrue(fakeAuth.signOutCalled)
        assertNull(fakeSettings.lastBiometricLoginEnabled)
        assertEquals(BiometricLockUiState.SignedOut, viewModel.uiState.value)
    }

    // fake AuthRepository for testing
    private class FakeAuthRepository : AuthRepository {
        var signOutCalled = false

        override fun observeCurrentUser(): Flow<User?> = flowOf(null)
        override suspend fun getCurrentUser(): User? = null
        override suspend fun signInWithGoogle(activity: Activity): AuthResult = AuthResult.Failure(R.string.sign_in_failed)
        override suspend fun updateProfile(displayName: String?, pictureId: String?): ProfileSaveResult = ProfileSaveResult.NOT_SIGNED_IN
        override suspend fun signOut() {
            signOutCalled = true
        }
        override suspend fun deleteAccount(): DeleteAccountResult = DeleteAccountResult.FAILED
        override suspend fun freshAccessToken(): String? = null
        override suspend fun renewAccessToken(): String? = null
        override suspend fun endSession() = Unit
    }

    // fake SettingsRepository for testing
    private class FakeSettingsRepository : SettingsRepository {
        var lastBiometricLoginEnabled: Boolean? = null

        override fun observeSettings(): Flow<UserSettings> = flowOf(UserSettings())
        override fun getSettingsBlocking(): UserSettings = UserSettings()
        override suspend fun setLanguage(language: AppLanguage) {}
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun setHomeBackdrop(backdrop: HomeBackdrop) {}
        override suspend fun setDefaultPlatform(platform: Platform) {}
        override suspend fun setAchievementNotificationsEnabled(enabled: Boolean) {}
        override suspend fun setRankChangeNotificationsEnabled(enabled: Boolean) {}
        override suspend fun setSeasonResetNotificationsEnabled(enabled: Boolean) {}
        override suspend fun setFriendActivityNotificationsEnabled(enabled: Boolean) {}
        override suspend fun replaceSyncedSettings(settings: UserSettings) {}
        override suspend fun isSyncPending(): Boolean = false
        override suspend fun setSyncPending(pending: Boolean) {}
        override suspend fun hasOfferedBiometricUnlock(): Boolean = false
        override suspend fun setBiometricUnlockOffered() {}
        override suspend fun setBiometricLoginEnabled(enabled: Boolean) {
            lastBiometricLoginEnabled = enabled
        }
    }
}
//------------------------------EOF------------------------------\\