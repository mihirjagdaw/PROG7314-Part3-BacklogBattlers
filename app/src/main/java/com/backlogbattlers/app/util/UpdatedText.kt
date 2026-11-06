package com.backlogbattlers.app.util

import android.content.Context
import android.text.format.DateUtils
import com.backlogbattlers.app.R

// how long ago something was last updated, for the "Updated ..." label on a screen that is showing the copy
// saved on this phone: "just now" for under a minute, then "5 minutes ago", "2 hours ago", "yesterday" and so
// on, in the language of the app. [now] is a parameter so the label can be checked
fun Context.updatedAgo(savedAt: Long, now: Long = System.currentTimeMillis()): String {
    if (now - savedAt < DateUtils.MINUTE_IN_MILLIS) return getString(R.string.updated_just_now)
    return DateUtils.getRelativeTimeSpanString(savedAt, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}
//------------------------------EOF------------------------------\\
