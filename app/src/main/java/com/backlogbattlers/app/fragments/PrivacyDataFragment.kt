package com.backlogbattlers.app.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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
import com.backlogbattlers.app.util.BiometricAvailability
import com.backlogbattlers.app.util.UNLOCK_AUTHENTICATORS
import com.backlogbattlers.app.util.shouldConfirmBeforeTurningOff
import com.backlogbattlers.app.util.bindSettingsHeader
import com.backlogbattlers.app.util.bindSettingsToggle
import com.backlogbattlers.app.util.setSettingsToggleChecked
import com.backlogbattlers.app.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

// biometric unlock and what the app stores
class PrivacyDataFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()

    //------------------------------
    // inflates the privacy and data layout
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_privacy_data, container, false)
    }

    //------------------------------
    // binds the biometric switch
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bar = view.findViewById<View>(R.id.barPrivacy)
        bar.findViewById<TextView>(R.id.tvSettingsBarTitle).setText(R.string.title_privacy)
        bar.findViewById<View>(R.id.btnSettingsBack).setOnClickListener {
            findNavController().navigateUp()
        }

        view.findViewById<View>(R.id.headerSecurity).bindSettingsHeader(R.string.privacy_section_security)
        view.findViewById<View>(R.id.headerData).bindSettingsHeader(R.string.privacy_section_data)

        val biometric = view.findViewById<View>(R.id.toggleBiometric)
        biometric.bindSettingsToggle(
            icon = R.drawable.ic_lock,
            title = R.string.privacy_biometric,
            body = R.string.privacy_biometric_body,
            checked = viewModel.settings.value.biometricLoginEnabled,
        ) { enabled ->
            // turning it on only works on a phone that can unlock with something. otherwise it
            // says why and stays off, rather than being a switch that quietly does nothing
            val availability = ServiceLocator.biometricAvailabilityChecker.check()
            if (enabled && availability != BiometricAvailability.Available) {
                biometric.setSettingsToggleChecked(false)
                Log.w(TAG, "Biometric unlock can't be turned on: $availability")
                Toast.makeText(requireContext(), unavailableMessage(availability), Toast.LENGTH_LONG).show()
            } else if (!enabled && shouldConfirmBeforeTurningOff(availability)) {
                // switching the lock off is itself behind the lock, so whoever holds an unlocked phone
                // can't just remove it. the switch waits for the answer
                confirmThenTurnOff(biometric)
            } else {
                Log.i(TAG, "Biometric unlock turned ${if (enabled) "on" else "off"}")
                viewModel.setBiometricLogin(enabled)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settings.collect { settings ->
                    biometric.setSettingsToggleChecked(settings.biometricLoginEnabled)
                }
            }
        }
    }

    //------------------------------
    // asks for the fingerprint, face or screen lock before the lock is turned off. only a success
    // turns it off: cancelling or failing puts the switch back on
    private fun confirmThenTurnOff(row: View) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.i(TAG, "Confirmed, biometric unlock turned off")
                viewModel.setBiometricLogin(false)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w(TAG, "Turning biometric unlock off was not confirmed ($errorCode): $errString")
                row.setSettingsToggleChecked(true)
                Toast.makeText(requireContext(), R.string.biometric_turn_off_not_confirmed, Toast.LENGTH_SHORT).show()
            }
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_turn_off_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(UNLOCK_AUTHENTICATORS)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(requireContext()), callback).authenticate(promptInfo)
    }

    private fun unavailableMessage(availability: BiometricAvailability): Int = when (availability) {
        BiometricAvailability.NoHardware -> R.string.biometric_unavailable_no_hardware
        BiometricAvailability.NotEnrolled -> R.string.biometric_unavailable_not_enrolled
        BiometricAvailability.Unavailable,
        BiometricAvailability.Available -> R.string.biometric_unavailable_other
    }

    private companion object {
        const val TAG = "PrivacyDataFragment"
    }
}
//------------------------------EOF------------------------------\\
