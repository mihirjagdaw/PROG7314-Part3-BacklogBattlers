package com.backlogbattlers.app.data.repository

import android.util.Log
import com.backlogbattlers.app.data.local.dao.CachedResponseDao
import com.backlogbattlers.app.data.local.entity.CachedResponseEntity

// an answer the server gave earlier, kept so a screen can open without a connection
data class SavedResponse(
    // the answer exactly as the server sent it, so it is read back the way a fresh one is
    val json: String,
    // epoch milliseconds the server answered: what "last updated" counts from
    val savedAt: Long,
)

//------------------------------
// keeps the last answer the server gave for each thing a screen shows (the profile, a friend's profile, the
// newest page of the activity feed), for the signed-in account only. the profile and the feed read from it
// when the network can't be reached, and say when it was last updated. only the newest answer for each key
// is kept: this is a copy for offline, not a history
interface ResponseCache {

    // the saved answer for [key], or null when there is none or nobody is signed in
    suspend fun load(key: String): SavedResponse?

    // saves [json] as the answer for [key], replacing the previous one. does nothing when nobody is signed in
    suspend fun save(key: String, json: String, savedAt: Long)

    // forgets everything saved for [userId], when that account is deleted
    suspend fun clear(userId: String)
}

// Room backed ResponseCache. [currentUserId] is the signed-in account (null when signed out): answers are kept
// under it, so a second account on the same phone never sees the first one's profile or feed
class RoomResponseCache(
    private val dao: CachedResponseDao,
    private val currentUserId: () -> String?,
) : ResponseCache {

    override suspend fun load(key: String): SavedResponse? {
        val viewer = currentUserId() ?: return null
        return dao.get(viewer, key)?.let { SavedResponse(it.json, it.savedAt) }
    }

    override suspend fun save(key: String, json: String, savedAt: Long) {
        val viewer = currentUserId()
        if (viewer == null) {
            Log.w(TAG, "Not saving $key for offline: nobody is signed in")
            return
        }
        dao.upsert(CachedResponseEntity(viewer, key, json, savedAt))
    }

    override suspend fun clear(userId: String) {
        dao.clear(userId)
        Log.d(TAG, "Cleared what a deleted account had saved for offline")
    }

    private companion object {
        const val TAG = "ResponseCache"
    }
}
//------------------------------EOF------------------------------\\
