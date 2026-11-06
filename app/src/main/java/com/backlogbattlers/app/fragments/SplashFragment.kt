package com.backlogbattlers.app.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.backlogbattlers.app.R
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.util.shouldRequireBiometricUnlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// first destination on launch. it has nothing to show of its own: the system splash
// (installSplashScreen in MainActivity) stays on screen until this has routed the user
// to login, the fingerprint lock or home
class SplashFragment : Fragment() {

    //------------------------------
    // inflates the fragment_splash layout
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_splash, container, false)
    }

    //------------------------------
    // checks authentication state and navigates to loginFragment or homeFragment
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            // the first use of authRepository builds the encrypted token storage and the HTTP client, which
            // read from disk, so it happens off the main thread and the splash never freezes on it
            val currentUser = withContext(Dispatchers.IO) { ServiceLocator.authRepository.getCurrentUser() }

            // this if statement only looks at biometric state once a session already exist
            if (currentUser != null) {
                val settings = ServiceLocator.settingsRepository.observeSettings().first()

                // this if statement decides between the fingerprint gate and going straight. the gate
                // closes whenever the lock is on, even if the phone can no longer unlock: the lock screen
                // then says so and offers to sign in again, instead of letting the app open unprotected
                if (shouldRequireBiometricUnlock(settings.biometricLoginEnabled)) {
                    Log.i(TAG, "Signed in with the fingerprint lock on, asking to unlock")
                    findNavController().navigate(R.id.action_splashFragment_to_biometricLockFragment)
                } else {
                    // uploads a settings change that didn't make it last time, or else picks up
                    // changes made on another phone. in the background: launch never waits on the
                    // network. behind the fingerprint gate this waits for the unlock instead, so a
                    // pulled theme or language can't recreate the screen under the prompt
                    ServiceLocator.applicationScope.launch { ServiceLocator.settingsSync.sync() }
                    ServiceLocator.applicationScope.launch { ServiceLocator.librarySync.sync() }
                    // and tells the API pushes for this account can come to this phone
                    ServiceLocator.applicationScope.launch { ServiceLocator.pushTokens.register() }
                    Log.i(TAG, "Signed in, going to home")
                    findNavController().navigate(R.id.action_splashFragment_to_homeFragment)
                }
            } else {
                Log.i(TAG, "Nobody is signed in, going to login")
                findNavController().navigate(R.id.action_splashFragment_to_loginFragment)
            }
        }
    }

    private companion object {
        const val TAG = "SplashFragment"
    }
}
//------------------------------EOF------------------------------\\