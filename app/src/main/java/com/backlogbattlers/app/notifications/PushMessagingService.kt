package com.backlogbattlers.app.notifications

import android.util.Log
import com.backlogbattlers.app.core.ServiceLocator
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

//------------------------------
// receives pushes from Firebase Cloud Messaging. the API sends data messages only, so this runs
// for every push, whether the app is open, in the background or closed, and builds the
// notification itself in the user's language.
// follows the Firebase Cloud Messaging guide for Android clients: a FirebaseMessagingService with
// onNewToken and onMessageReceived, registered in the manifest
// https://firebase.google.com/docs/cloud-messaging/android/client
// https://firebase.google.com/docs/cloud-messaging/android/receive
class PushMessagingService : FirebaseMessagingService() {

    //------------------------------
    // Firebase gave this phone a new token (first launch, reinstall, or it rotated it)
    override fun onNewToken(token: String) {
        Log.d(TAG, "New push token")
        ServiceLocator.init(applicationContext)
        ServiceLocator.applicationScope.launch { ServiceLocator.pushTokens.register(token) }
    }

    //------------------------------
    // runs on a background thread Firebase owns, which waits for this to return, so the
    // settings can be read synchronously here
    override fun onMessageReceived(message: RemoteMessage) {
        ServiceLocator.init(applicationContext)
        val push = Push.from(message.data)
        if (push == null) {
            Log.w(TAG, "Ignoring a push this version doesn't understand: ${message.data["type"]}")
            return
        }

        val signedInUserId = ServiceLocator.tokenStorage.getUserId()
        val settings = runBlocking { ServiceLocator.settingsRepository.observeSettings().first() }
        if (!push.shouldShow(signedInUserId, settings, appOpen = AppVisibility.isOpen)) {
            Log.i(TAG, "Not showing ${push.type}: switched off, meant for another account, or about your own action while the app is open")
            return
        }
        PushNotifications.show(applicationContext, push)
    }

    private companion object {
        const val TAG = "PushMessagingService"
    }
}
//------------------------------EOF------------------------------\\
