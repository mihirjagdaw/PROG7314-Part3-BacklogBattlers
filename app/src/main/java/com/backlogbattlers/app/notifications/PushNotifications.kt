package com.backlogbattlers.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.IdRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import com.backlogbattlers.app.MainActivity
import com.backlogbattlers.app.R
import com.backlogbattlers.app.util.formatPoints

//------------------------------
// builds and shows the notifications for pushes, and tells MainActivity which screen a tapped
// one should open.
// channels, the POST_NOTIFICATIONS permission and the tap's PendingIntent follow the Android
// notification guides:
// https://developer.android.com/develop/ui/views/notifications/channels
// https://developer.android.com/develop/ui/views/notifications/notification-permission
// https://developer.android.com/develop/ui/views/notifications/navigation
object PushNotifications {

    private const val TAG = "PushNotifications"

    // the intent extra a tapped notification carries: the PushType name
    private const val EXTRA_PUSH_TYPE = "com.backlogbattlers.app.extra.PUSH_TYPE"

    //------------------------------
    // registers the channels. safe to call on every launch: creating an existing channel only
    // updates its name and description, never the user's own choices for it
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                channel(context, PushChannel.FRIENDS, R.string.channel_friend_activity, R.string.channel_friend_activity_body),
                channel(context, PushChannel.COMPETITION, R.string.channel_competition, R.string.channel_competition_body),
                channel(context, PushChannel.ACHIEVEMENTS, R.string.channel_achievements, R.string.channel_achievements_body),
            ),
        )
    }

    private fun channel(context: Context, channel: PushChannel, name: Int, description: Int) =
        NotificationChannel(channel.id, context.getString(name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            this.description = context.getString(description)
        }

    //------------------------------
    // shows [push] as a notification that opens its screen when tapped
    fun show(context: Context, push: Push) {
        if (!canPost(context)) {
            Log.i(TAG, "Notifications are turned off for the app, not showing ${push.type}")
            return
        }
        // one per person (or game, or month) and kind, so two friend requests stack but a repeat replaces itself
        val notificationId = 31 * push.type.ordinal + (push.actorName.ifEmpty { push.gameTitle.orEmpty() }).hashCode()

        val open = Intent(context, MainActivity::class.java).apply {
            // brings the running app forward (onNewIntent) instead of starting a second copy
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PUSH_TYPE, push.type.name)
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            notificationId,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val (title, body) = text(context, push)
        val notification = NotificationCompat.Builder(context, push.type.channel.id)
            .setSmallIcon(R.drawable.ic_bell)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // the permission was taken away between the check and now
            Log.w(TAG, "Could not show the notification: ${e.message}")
        }
    }

    private fun text(context: Context, push: Push): Pair<String, String> = when (push.type) {
        PushType.FRIEND_REQUEST_RECEIVED ->
            context.getString(R.string.push_friend_request_title) to context.getString(R.string.push_friend_request_body, push.actorName)
        PushType.FRIEND_REQUEST_ACCEPTED ->
            context.getString(R.string.push_friend_accepted_title) to context.getString(R.string.push_friend_accepted_body, push.actorName)
        PushType.FRIEND_COMPLETED_GAME ->
            context.getString(R.string.push_friend_completed_title, push.actorName) to
                context.getString(R.string.push_friend_completed_body, push.actorName, push.gameTitle.orEmpty())
        PushType.OVERTAKEN ->
            context.getString(R.string.push_overtaken_title) to context.getString(R.string.push_overtaken_body, push.actorName)
        PushType.ACHIEVEMENT_UNLOCKED -> context.getString(R.string.push_achievement_title) to achievementBody(context, push)
        PushType.SEASON_RESET -> context.getString(R.string.push_season_title) to seasonBody(context, push)
    }

    // what was unlocked: the one achievement by name, or how many in the game, and the rarest of them
    private fun achievementBody(context: Context, push: Push): String {
        val count = push.count ?: 1
        val game = push.gameTitle
        val rarest = push.achievementName
        return when {
            game == null -> context.resources.getQuantityString(R.plurals.push_achievement_summary, count, count)
            count == 1 && rarest != null -> context.getString(R.string.push_achievement_one, rarest, game)
            else -> {
                val many = context.resources.getQuantityString(R.plurals.push_achievement_many, count, count, game)
                if (rarest != null) many + " " + context.getString(R.string.push_achievement_rarest, rarest) else many
            }
        }
    }

    // how last month ended, or that everyone starts level when the user scored nothing
    private fun seasonBody(context: Context, push: Push): String {
        val rank = push.lastRank
        val points = push.lastPoints ?: 0
        return if (rank != null && points > 0) {
            context.getString(R.string.push_season_result, rank, formatPoints(points))
        } else {
            context.getString(R.string.push_season_none)
        }
    }

    //------------------------------
    // whether the app may post: the user hasn't blocked it, and on android 13+ granted the permission
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    //------------------------------
    // the kind of push a tapped notification was for, or null for any other launch
    fun typeFrom(intent: Intent?): PushType? = PushType.fromName(intent?.getStringExtra(EXTRA_PUSH_TYPE))

    //------------------------------
    // takes the push off the intent once it has been handled, so recreating the activity
    // (a theme or language change) doesn't open the screen again
    fun clear(intent: Intent?) {
        intent?.removeExtra(EXTRA_PUSH_TYPE)
    }

    //------------------------------
    // the screen a tapped notification opens, and the arguments it opens with
    @IdRes
    fun destinationOf(type: PushType): Int = when (type) {
        PushType.FRIEND_REQUEST_RECEIVED, PushType.FRIEND_REQUEST_ACCEPTED -> R.id.friendsFragment
        PushType.FRIEND_COMPLETED_GAME -> R.id.activityFragment
        PushType.OVERTAKEN, PushType.SEASON_RESET -> R.id.standingsFragment
        // the profile's recent activity lists what was unlocked
        PushType.ACHIEVEMENT_UNLOCKED -> R.id.profileFragment
    }

    fun argumentsOf(type: PushType): Bundle? = type.friendsTab?.let { bundleOf(ARG_OPEN_TAB to it) }

    // the friendsFragment argument naming the tab to open on
    const val ARG_OPEN_TAB = "openTab"
}
//------------------------------EOF------------------------------\\
