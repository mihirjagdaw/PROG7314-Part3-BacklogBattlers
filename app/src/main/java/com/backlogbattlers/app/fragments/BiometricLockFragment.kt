package com.backlogbattlers.app.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.backlogbattlers.app.R
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.util.BiometricLockReason
import com.backlogbattlers.app.util.UNLOCK_AUTHENTICATORS
import com.backlogbattlers.app.util.UnlockChallenge
import com.backlogbattlers.app.util.lockReasonForAvailability
import com.backlogbattlers.app.util.setVisible
import com.backlogbattlers.app.viewmodels.BiometricLockUiState
import com.backlogbattlers.app.viewmodels.BiometricLockViewModel
import kotlinx.coroutines.launch

// fingerprint lock screen. shown after a cold launch when biometric login is on, and again when
// the app comes back after a minute or more in the background (see MainActivity).
// the prompt accepts a fingerprint or face, or the phone's PIN, pattern or password. if it is
// closed without unlocking, the screen stays locked and offers to try again or sign in again.
// the lock fails closed: if the phone can no longer unlock (the fingerprint or screen lock was
// removed) the app stays shut and the screen says so, rather than opening unprotected.
// the unlock is tied to a key in the phone's keystore (see UnlockKey), so the prompt's answer is
// proven and not just believed.
// the prompt pattern follows the Android Biometric codelab and API reference:
// https://developer.android.com/identity/sign-in/biometric-auth
class BiometricLockFragment : Fragment() {

    private val viewModel: BiometricLockViewModel by viewModels()

    // true when this lock came from returning to the app, not from launching it: unlocking then goes
    // back to the screen the user was on, not to home
    private val isRelock: Boolean
        get() = arguments?.getBoolean(ARG_RELOCK, false) == true

    //------------------------------
    // inflates the biometric lock layout
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_biometric_lock, container, false)
    }

    //------------------------------
    // observes ui state for navigation, then starts the scan
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // back must not lead past the lock: on a re-lock the screens underneath are still on the back
        // stack, and on launch it would just close the app. either way, it only sends the app away
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    requireActivity().moveTaskToBack(true)
                }
            },
        )

        val body = view.findViewById<TextView>(R.id.tvBiometricLockBody)
        val retry = view.findViewById<View>(R.id.btnBiometricRetry)
        val signInAgain = view.findViewById<View>(R.id.btnBiometricSignInAgain)
        retry.setOnClickListener {
            Log.d(TAG, "Trying the unlock again")
            showBiometricPrompt()
        }
        signInAgain.setOnClickListener {
            Log.i(TAG, "Signing in again instead of unlocking")
            viewModel.onSignInAgain()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val locked = state as? BiometricLockUiState.Locked
                    // trying again makes sense unless a fingerprint change closed the lock for good: then only
                    // signing in again helps. it checks the phone again each time, so it also works once the
                    // user has set the screen lock back up
                    retry.setVisible(locked != null && locked.reason != BiometricLockReason.CHANGED)
                    signInAgain.setVisible(locked != null)
                    body.setText(if (locked != null) lockMessage(locked.reason) else R.string.biometric_lock_body)

                    when (state) {
                        is BiometricLockUiState.Success -> {
                            if (isRelock) {
                                // already signed in and synced: back to the screen the user left
                                Log.i(TAG, "Unlocked after the app was in the background, going back")
                                findNavController().popBackStack()
                            } else {
                                // the settings sync SplashFragment holds back until the user is in
                                Log.i(TAG, "Unlocked, going to home")
                                ServiceLocator.applicationScope.launch { ServiceLocator.settingsSync.sync() }
                                // and tells the API pushes for this account can come to this phone
                                ServiceLocator.applicationScope.launch { ServiceLocator.pushTokens.register() }
                                ServiceLocator.applicationScope.launch { ServiceLocator.librarySync.sync() }
                                findNavController().navigate(R.id.action_biometricLockFragment_to_homeFragment)
                            }
                        }
                        is BiometricLockUiState.SignedOut -> {
                            Log.i(TAG, "Signed out from the lock screen, going to login")
                            findNavController().navigate(R.id.action_biometricLockFragment_to_loginFragment)
                        }
                        BiometricLockUiState.Idle,
                        BiometricLockUiState.Authenticating,
                        is BiometricLockUiState.Locked -> Unit
                    }
                }
            }
        }

        // a recreated screen (e.g. after rotating) that was already showing its buttons waits for a tap
        if (viewModel.uiState.value !is BiometricLockUiState.Locked) showBiometricPrompt()
    }

    private fun lockMessage(reason: BiometricLockReason): Int = when (reason) {
        BiometricLockReason.CANCELLED -> R.string.biometric_lock_cancelled
        BiometricLockReason.LOCKED_OUT -> R.string.biometric_lock_locked_out
        BiometricLockReason.UNAVAILABLE -> R.string.biometric_lock_unavailable
        BiometricLockReason.CHANGED -> R.string.biometric_lock_changed
        BiometricLockReason.NOT_VERIFIED -> R.string.biometric_lock_not_verified
    }

    //------------------------------
    // checks the phone can still unlock, then builds and shows the system unlock popup. with the
    // screen lock allowed as a fallback, the system adds its own "Use PIN" option, so the prompt has
    // no cancel button of ours
    private fun showBiometricPrompt() {
        // fails closed: a phone that lost its fingerprint and screen lock gets an explanation, not the app
        val unavailable = lockReasonForAvailability(ServiceLocator.biometricAvailabilityChecker.check())
        if (unavailable != null) {
            viewModel.onCannotUnlock(unavailable)
            return
        }

        // the key the unlock is tied to. it is gone for good if fingerprints were added or removed
        val unlockKey = ServiceLocator.unlockKey
        val challenge = unlockKey.challenge()
        if (challenge is UnlockChallenge.Invalidated) {
            viewModel.onCannotUnlock(BiometricLockReason.CHANGED)
            return
        }

        viewModel.onAuthenticationStarted()

        val executor = ContextCompat.getMainExecutor(requireContext())
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // the prompt saying yes is not enough: the keystore has to agree, by letting the
                // prompt's cipher run. without a key (android 10 and older) the prompt alone counts
                val trusted = when (challenge) {
                    is UnlockChallenge.Ready -> unlockKey.isAuthorised(result.cryptoObject?.cipher)
                    UnlockChallenge.NotSupported -> true
                    UnlockChallenge.Invalidated -> false
                }
                if (trusted) {
                    viewModel.onAuthenticationSucceeded()
                } else {
                    viewModel.onCannotUnlock(BiometricLockReason.NOT_VERIFIED)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(TAG, "Unlock prompt closed with error $errorCode: $errString")
                viewModel.onAuthenticationError(errorCode)
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(UNLOCK_AUTHENTICATORS)
            .build()

        if (challenge is UnlockChallenge.Ready) {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(challenge.cipher))
        } else {
            prompt.authenticate(promptInfo)
        }
    }

    companion object {
        private const val TAG = "BiometricLockFragment"

        // set on the navigation to this screen when the app is being locked again after the
        // background, not launched
        const val ARG_RELOCK = "relock"
    }
}
//------------------------------EOF------------------------------\\
