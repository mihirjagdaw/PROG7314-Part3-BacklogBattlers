package com.backlogbattlers.app.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.data.repository.AuthRepository
import com.backlogbattlers.app.data.repository.SettingsRepository
import com.backlogbattlers.app.util.BiometricLockReason
import com.backlogbattlers.app.util.lockReasonFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// what the fingerprint lock screen is doing right now
sealed class BiometricLockUiState {

    // waiting to show prompt
    data object Idle : BiometricLockUiState()

    // biometric prompt is actively showing
    data object Authenticating : BiometricLockUiState()

    // biometric check succeeded
    data object Success : BiometricLockUiState()

    // the prompt closed without unlocking: the screen offers to try again or sign in again
    data class Locked(val reason: BiometricLockReason) : BiometricLockUiState()

    // the user chose to sign in again instead, and was signed out
    data object SignedOut : BiometricLockUiState()
}

// viewmodel behind the fingerprint lock screen shown after a cold launch
class BiometricLockViewModel(
    private val authRepository: AuthRepository = ServiceLocator.authRepository,
    private val settingsRepository: SettingsRepository = ServiceLocator.settingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BiometricLockUiState>(BiometricLockUiState.Idle)
    val uiState: StateFlow<BiometricLockUiState> = _uiState.asStateFlow()

    //------------------------------
    // marks the prompt as showing called right before biometricPrompt
    fun onAuthenticationStarted() {
        _uiState.value = BiometricLockUiState.Authenticating
    }

    //------------------------------
    // the prompt can't even be shown, or its answer can't be trusted: the screen stays locked and
    // says why. never unlocks, so a phone that lost its fingerprint or screen lock fails closed
    fun onCannotUnlock(reason: BiometricLockReason) {
        _uiState.value = BiometricLockUiState.Locked(reason)
        Log.w(TAG, "Staying locked: $reason")
    }

    //------------------------------
    // the scan (or the phone's PIN) matched, the caller can now navigate to home
    fun onAuthenticationSucceeded() {
        _uiState.value = BiometricLockUiState.Success
    }

    //------------------------------
    // the prompt closed without unlocking, from the user cancelling or from an error. nothing is
    // signed out: the screen stays locked and offers to try again or to sign in again
    fun onAuthenticationError(errorCode: Int) {
        _uiState.value = BiometricLockUiState.Locked(lockReasonFor(errorCode))
        Log.d(TAG, "Unlock prompt closed, now ${_uiState.value}")
    }

    //------------------------------
    // the user would rather sign in with Google than unlock: the session ends here. fingerprint
    // unlock stays on, so after signing in it guards the next launch as before
    fun onSignInAgain() {
        viewModelScope.launch {
            authRepository.signOut()
            Log.i(TAG, "Signed out from the lock screen to sign in again")
            settingsRepository.setSyncPending(false)
            _uiState.value = BiometricLockUiState.SignedOut
        }
    }

    private companion object {
        const val TAG = "BiometricLockViewModel"
    }
}
//------------------------------EOF------------------------------\\
