package com.backlogbattlers.app.notifications

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

//------------------------------
// whether the app is on screen right now, so a push about something the player is doing and
// watching (see PushType.hiddenWhileAppOpen) is not shown on top of the screen that already says it.
// counts the activities that are started: one or more means the app is in front, none means it
// is in the background or closed. read from the push service's own thread, hence the atomic counter.
// pattern: https://developer.android.com/reference/android/app/Application.ActivityLifecycleCallbacks
object AppVisibility {

    private val startedActivities = AtomicInteger(0)

    // true while at least one screen of the app is in front of the user
    val isOpen: Boolean
        get() = startedActivities.get() > 0

    //------------------------------
    // starts counting. called once, from MainApplication
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.incrementAndGet()
            }

            override fun onActivityStopped(activity: Activity) {
                // never below zero, even if a callback is missed
                startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
//------------------------------EOF------------------------------\\
