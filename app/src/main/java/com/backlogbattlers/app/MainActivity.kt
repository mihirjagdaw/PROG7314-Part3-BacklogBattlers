package com.backlogbattlers.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.setupWithNavController
import com.backlogbattlers.app.core.ServiceLocator
import com.backlogbattlers.app.fragments.BiometricLockFragment
import com.backlogbattlers.app.util.shouldRelock
import com.backlogbattlers.app.data.repository.SyncBanner
import com.backlogbattlers.app.notifications.NotificationPermission
import com.backlogbattlers.app.notifications.PushNotifications
import com.backlogbattlers.app.notifications.PushType
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// main activity of the application hosting fragment navigation and bottom navigation bar
class MainActivity : AppCompatActivity() {

    private val fullBleedDestinations = setOf(R.id.homeFragment)
    private var contentDrawsBehindStatusBar = false
    private var bannerVisible = false

    // when the app last went out of sight (SystemClock.elapsedRealtime), null while it is in front
    private var backgroundedAt: Long? = null

    // screens shown before the user is signed in and unlocked. a tapped notification waits until
    // the user is past them, then opens its screen
    private val beforeSignedIn = setOf(R.id.splashFragment, R.id.loginFragment, R.id.biometricLockFragment)

    private lateinit var root: View
    private lateinit var navController: NavController

    // the push whose notification was tapped, until its screen has been opened
    private var pendingPush: PushType? = null
    private var notificationPermissionChecked = false

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "Notification permission ${if (granted) "granted" else "refused"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // has to run before super.onCreate so the launch theme hands over to the app theme
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // only on a fresh start: recreating the activity (a theme or language change) must not
        // open the notification's screen a second time
        if (savedInstanceState == null) pendingPush = PushNotifications.typeFrom(intent)
        PushNotifications.clear(intent)

        root = findViewById(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // the banner sits at the very top, so it needs the status bar's room even on a screen that
            // normally draws behind it
            val top = if (contentDrawsBehindStatusBar && !bannerVisible) 0 else systemBars.top
            v.setPadding(systemBars.left, top, systemBars.right, systemBars.bottom)
            insets
        }

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        // the logo stays up while SplashFragment works out whether to go to login,
        // the fingerprint lock or home, so the user never sees an empty screen in between
        splashScreen.setKeepOnScreenCondition {
            navController.currentDestination?.id == R.id.splashFragment
        }
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val navDivider = findViewById<View>(R.id.navDivider)

        bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            Log.d(TAG, "Showing ${resources.getResourceEntryName(destination.id)}")
            contentDrawsBehindStatusBar = destination.id in fullBleedDestinations
            ViewCompat.requestApplyInsets(root)

            when (destination.id) {
                R.id.splashFragment,
                R.id.loginFragment,
                // no tab bar on the lock screen: its tabs would walk straight past the lock
                R.id.biometricLockFragment,
                R.id.settingsFragment,
                R.id.editProfileFragment,
                R.id.notificationSettingsFragment,
                R.id.appearanceFragment,
                R.id.privacyDataFragment,
                R.id.helpSupportFragment,
                R.id.aboutFragment,
                R.id.searchFragment,
                R.id.gameDetailFragment,
                -> {
                    bottomNav.visibility = View.GONE
                    navDivider.visibility = View.GONE
                }
                else -> {
                    bottomNav.visibility = View.VISIBLE
                    navDivider.visibility = View.VISIBLE
                }
            }

            if (destination.id == R.id.homeFragment) askForNotificationPermissionOnce()
            openPendingPush()
        }

        // the backend stopped accepting the session (its refresh token was rejected): back to login
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ServiceLocator.sessionEnded.collect { backToLogin(navController) } }
                launch { ServiceLocator.syncCoordinator.banner.collect { showSyncBanner(it) } }

                // a session can also end while the app is in the background, when nothing is listening.
                // login and splash are the only screens a signed out user belongs on; the storage is
                // only touched once past them, because it is slow to build and splash avoids that
                val destination = navController.currentDestination?.id
                if (destination != R.id.loginFragment && destination != R.id.splashFragment &&
                    ServiceLocator.tokenStorage.getUserId() == null
                ) {
                    backToLogin(navController)
                }
            }
        }
    }

    //------------------------------
    // the app went out of sight: note when, so coming back can tell how long it was away.
    // not for a rotation or a theme change, which recreate the activity in a blink
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }

    //------------------------------
    // the app is back in front. after a minute or more away it locks again, when fingerprint unlock
    // is on and the user is past the first unlock, so a phone left open on the table or handed to
    // someone doesn't keep the app open. a notification tap that brought the app back waits behind
    // the lock too (see openPendingPush)
    override fun onStart() {
        super.onStart()
        val left = backgroundedAt ?: return
        backgroundedAt = null
        // the nav controller is not set up on the very first start
        if (!::navController.isInitialized) return
        val current = navController.currentDestination?.id ?: return
        val now = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            val enabled = ServiceLocator.settingsRepository.observeSettings().first().biometricLoginEnabled
            val relock = shouldRelock(
                biometricEnabled = enabled,
                alreadyLocked = current in beforeSignedIn,
                backgroundedAt = left,
                now = now,
            )
            // checked again: the user may have moved on while the setting was read
            if (relock && navController.currentDestination?.id !in beforeSignedIn) {
                Log.i(TAG, "Back after ${(now - left) / 1000}s in the background, locking again")
                navController.navigate(R.id.biometricLockFragment, bundleOf(BiometricLockFragment.ARG_RELOCK to true))
            }
        }
    }

    //------------------------------
    // shows the strip across the top: offline, syncing what was changed offline, or done.
    // grey while waiting, green for done, and gone when there is nothing to say
    private fun showSyncBanner(state: SyncBanner) {
        val banner = findViewById<TextView>(R.id.syncBanner)
        when (state) {
            SyncBanner.HIDDEN -> Unit
            SyncBanner.OFFLINE -> {
                banner.setText(R.string.sync_banner_offline)
                banner.setBackgroundColor(ContextCompat.getColor(this, R.color.scrim_slate))
            }
            SyncBanner.SYNCING -> {
                banner.setText(R.string.sync_banner_syncing)
                banner.setBackgroundColor(ContextCompat.getColor(this, R.color.scrim_slate))
            }
            SyncBanner.SYNCED -> {
                banner.setText(R.string.sync_banner_synced)
                banner.setBackgroundColor(ContextCompat.getColor(this, R.color.success))
            }
        }
        val visible = state != SyncBanner.HIDDEN
        banner.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible != bannerVisible) {
            bannerVisible = visible
            ViewCompat.requestApplyInsets(root)
        }
    }

    //------------------------------
    // a notification was tapped while the app was already running
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val push = PushNotifications.typeFrom(intent) ?: return
        PushNotifications.clear(intent)
        pendingPush = push
        openPendingPush()
    }

    //------------------------------
    // opens the screen for the tapped notification, once the user is signed in and past the
    // fingerprint lock. home stays underneath, so back returns there
    private fun openPendingPush() {
        val push = pendingPush ?: return
        val current = navController.currentDestination?.id ?: return
        if (current in beforeSignedIn) return
        pendingPush = null

        Log.i(TAG, "Opening the screen for a tapped $push notification")
        // posted, because this can run from inside a navigation callback
        root.post {
            navController.navigate(
                PushNotifications.destinationOf(push),
                PushNotifications.argumentsOf(push),
                navOptions {
                    popUpTo(R.id.homeFragment) { inclusive = false }
                    launchSingleTop = true
                },
            )
        }
    }

    //------------------------------
    // on android 13+, the first time home is reached, explains what notifications are for and
    // then shows the system prompt. never asked again after that (see NotificationPermission)
    private fun askForNotificationPermissionOnce() {
        if (notificationPermissionChecked || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        notificationPermissionChecked = true

        lifecycleScope.launch {
            val shouldAsk = withContext(Dispatchers.IO) { NotificationPermission.shouldAsk(this@MainActivity) }
            if (!shouldAsk) return@launch
            withContext(Dispatchers.IO) { NotificationPermission.markAsked(this@MainActivity) }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_body)
                .setNegativeButton(R.string.notification_permission_decline) { _, _ ->
                    Log.i(TAG, "Notifications declined before the system prompt")
                }
                .setPositiveButton(R.string.notification_permission_allow) { _, _ ->
                    notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .show()
        }
    }

    //------------------------------
    // clears the back stack down to the login screen and says why the user is there
    private fun backToLogin(navController: NavController) {
        val destination = navController.currentDestination?.id
        if (destination == R.id.loginFragment || destination == R.id.splashFragment) return

        Log.i(TAG, "The session has ended, going back to login")
        Toast.makeText(this, R.string.session_expired, Toast.LENGTH_LONG).show()
        navController.navigate(R.id.action_global_loginFragment)
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
//------------------------------EOF------------------------------\\
