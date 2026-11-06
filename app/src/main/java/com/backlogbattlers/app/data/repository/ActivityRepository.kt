package com.backlogbattlers.app.data.repository

import android.util.Log
import com.backlogbattlers.app.data.remote.ActivityApi
import com.backlogbattlers.app.data.remote.ActivityEventDto
import com.backlogbattlers.app.data.remote.ActivityPageDto
import com.backlogbattlers.app.domain.model.ActivityEvent
import com.backlogbattlers.app.domain.model.ActivityPage
import com.backlogbattlers.app.domain.model.ActivityType
import com.backlogbattlers.app.domain.model.CachedActivity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// reads the activity feed of the signed in user and their friends. the newest page is also kept for
// offline, so the feed can show it when there is no connection
interface ActivityRepository {
    // the newest page when before is null, otherwise the page of events older than before
    suspend fun getActivity(before: Long? = null): ActivityPage

    // the newest page as it was last saved, or null when none was ever saved
    suspend fun cachedNewest(): CachedActivity?
}

// network implementation for ActivityRepository, mapping dtos to domain models. only the newest page is
// saved to [cache] (older pages are not worth keeping offline), as the server sent it
class ActivityRepositoryImpl(
    private val api: ActivityApi,
    private val cache: ResponseCache? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ActivityRepository {

    private val json = Json { ignoreUnknownKeys = true }

    //------------------------------
    // fetches a page and maps it, leaving out the kinds of event this version does not know
    override suspend fun getActivity(before: Long?): ActivityPage {
        val page = api.getActivity(before)
        if (before == null) save(page)
        return page.toDomain()
    }

    //------------------------------
    // the saved newest page, read back the way a fresh one is. one that can no longer be read counts as none
    override suspend fun cachedNewest(): CachedActivity? {
        val saved = cache?.load(NEWEST_KEY) ?: return null
        val dto = runCatching { json.decodeFromString(ActivityPageDto.serializer(), saved.json) }.getOrNull() ?: return null
        return CachedActivity(dto.toDomain(), saved.savedAt)
    }

    // saving is best effort: a feed that loaded must never fail to show because it could not be kept
    private suspend fun save(page: ActivityPageDto) {
        try {
            cache?.save(NEWEST_KEY, json.encodeToString(ActivityPageDto.serializer(), page), clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The activity feed could not be kept for offline", e)
        }
    }

    private fun ActivityPageDto.toDomain(): ActivityPage =
        ActivityPage(events.map { it.toDomain() }.filter { it.type != ActivityType.UNKNOWN }, nextBefore)

    //------------------------------
    // converts an ActivityEventDto to a domain ActivityEvent
    private fun ActivityEventDto.toDomain(): ActivityEvent = ActivityEvent(
        eventId = eventId,
        type = runCatching { ActivityType.valueOf(type) }.getOrDefault(ActivityType.UNKNOWN),
        actorId = actorId,
        actorName = actorName,
        actorAvatarUrl = actorAvatarUrl,
        actorPictureId = actorPictureId,
        gameId = gameId,
        gameTitle = gameTitle,
        subjectId = subjectId,
        subjectName = subjectName,
        count = count,
        points = points,
        detail = detail,
        createdAt = createdAt,
    )

    private companion object {
        const val TAG = "ActivityRepository"
        const val NEWEST_KEY = "activity:newest"
    }
}
//------------------------------EOF------------------------------\\
