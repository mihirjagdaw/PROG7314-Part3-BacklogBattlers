package com.backlogbattlers.app.util

import android.util.Log
import androidx.core.os.bundleOf
import androidx.navigation.NavController
import com.backlogbattlers.app.R
import com.backlogbattlers.app.fragments.ProfileFragment

private const val TAG = "ProfileNavigation"

//------------------------------
// opens whose profile a tapped name or avatar belongs to: a friend's profile screen for a friend, and the profile
// tab for the signed in user themself (their own profile is the one with the pencil and the gear, not a friend's
// view of it). does nothing for a blank id, which an event that names nobody has
fun NavController.openProfileOf(userId: String?, signedInUserId: String?) {
    if (userId.isNullOrBlank()) return
    if (userId == signedInUserId) {
        Log.d(TAG, "Opening the profile tab, the tapped person is the signed in user")
        navigate(R.id.profileFragment)
    } else {
        Log.d(TAG, "Opening a friend's profile")
        navigate(R.id.action_global_friendProfileFragment, bundleOf(ProfileFragment.ARG_USER_ID to userId))
    }
}
//------------------------------EOF------------------------------\\
