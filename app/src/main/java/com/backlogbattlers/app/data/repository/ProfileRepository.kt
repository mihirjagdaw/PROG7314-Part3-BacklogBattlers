package com.backlogbattlers.app.data.repository

import android.util.Log
import com.backlogbattlers.app.data.remote.ProfileApi
import com.backlogbattlers.app.data.remote.ProfileDto
import com.backlogbattlers.app.domain.model.BadgeId
import com.backlogbattlers.app.domain.model.BadgeProgress
import com.backlogbattlers.app.domain.model.CachedProfile
import com.backlogbattlers.app.domain.model.Profile
import com.backlogbattlers.app.domain.model.RecentActivity
import com.backlogbattlers.app.domain.model.ShowcaseAchievement
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// reads profiles from the backend: the signed in user's own, and their friends'. each successful answer is
// also kept for offline, so the screen can show the last one it got when there is no connection
interface ProfileRepository {

    // the signed in user's profile, from the backend
    suspend fun getProfile(): Profile

    // a friend's profile, from the backend, without the sections they chose not to show.
    // throws ProfileUnavailableException when they are not a friend (any more) or the account is gone
    suspend fun getFriendProfile(userId: String): Profile

    // the profile saved by the last successful load, or null when none was ever saved. userId is a friend's,
    // or null for the signed in user's own
    suspend fun cachedProfile(userId: String? = null): CachedProfile?
}

// network implementation for ProfileRepository, mapping the dto to domain models. the server knows
// no pictures, so artFor supplies a game's from the phone's own cache (empty when it has none).
// [cache] keeps each answer for offline, as the server sent it
class ProfileRepositoryImpl(
    private val api: ProfileApi,
    private val artFor: suspend (gameId: Int) -> List<String> = { emptyList() },
    private val cache: ResponseCache? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProfileRepository {

    private val json = Json { ignoreUnknownKeys = true }

    //------------------------------
    // fetches the profile and maps it, leaving out any badge this version does not know. the
    // badges come back in the order the app lists them in, whatever order the backend used
    override suspend fun getProfile(): Profile = api.getProfile().also { save(OWN_KEY, it) }.toDomain()

    //------------------------------
    // fetches a friend's profile, which the server has already trimmed to what they let friends see
    override suspend fun getFriendProfile(userId: String): Profile =
        api.getFriendProfile(userId).also { save(friendKey(userId), it) }.toDomain()

    //------------------------------
    // the saved copy, read back the way a fresh answer is. one that can no longer be read (saved by an
    // older version of the app) counts as none
    override suspend fun cachedProfile(userId: String?): CachedProfile? {
        val saved = cache?.load(if (userId == null) OWN_KEY else friendKey(userId)) ?: return null
        val dto = runCatching { json.decodeFromString(ProfileDto.serializer(), saved.json) }.getOrNull() ?: return null
        return CachedProfile(dto.toDomain(), saved.savedAt)
    }

    // saving is best effort: a profile that loaded must never fail to show because it could not be kept
    private suspend fun save(key: String, dto: ProfileDto) {
        try {
            cache?.save(key, json.encodeToString(ProfileDto.serializer(), dto), clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The profile could not be kept for offline", e)
        }
    }

    private fun friendKey(userId: String) = "profile:$userId"

    //------------------------------
    // converts a ProfileDto to the domain Profile
    private suspend fun ProfileDto.toDomain(): Profile {
        val known = badges.mapNotNull { dto ->
            val id = runCatching { BadgeId.valueOf(dto.id) }.getOrNull() ?: return@mapNotNull null
            BadgeProgress(id, dto.progress, dto.target, dto.earned)
        }.sortedBy { it.id.ordinal }
        return Profile(
            userId = userId,
            displayName = displayName,
            avatarUrl = avatarUrl,
            pictureId = pictureId,
            xp = xp,
            level = level,
            xpIntoLevel = xpIntoLevel,
            xpForNextLevel = xpForNextLevel,
            monthlyPoints = monthlyPoints,
            monthRank = monthRank,
            gamesInLibrary = gamesInLibrary,
            gamesCompleted = gamesCompleted,
            achievementsUnlocked = achievementsUnlocked,
            badges = known,
            rarestAchievements = rarestAchievements.map {
                ShowcaseAchievement(it.achievementId, it.name, it.iconUrl, it.rarityPercent, it.gameId, it.gameTitle)
            },
            unlockedRecently = unlockedRecently,
            recentActivity = recentActivity.map {
                RecentActivity(
                    gameId = it.gameId,
                    gameTitle = it.gameTitle,
                    artUrls = artFor(it.gameId),
                    unlockedCount = it.unlockedCount,
                    lastUnlockedAt = it.lastUnlockedAt,
                    achievementsUnlocked = it.achievementsUnlocked,
                    achievementsTotal = it.achievementsTotal,
                )
            },
            badgesHidden = badgesHidden,
            showcaseHidden = showcaseHidden,
        )
    }

    private companion object {
        const val TAG = "ProfileRepository"
        const val OWN_KEY = "profile:me"
    }
}
//------------------------------EOF------------------------------\\
