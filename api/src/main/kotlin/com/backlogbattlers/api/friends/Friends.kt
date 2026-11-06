package com.backlogbattlers.api.friends

import com.backlogbattlers.api.activity.recordActivity
import com.backlogbattlers.api.db.ActivityType
import com.backlogbattlers.api.db.FriendRequests
import com.backlogbattlers.api.db.Friendships
import com.backlogbattlers.api.db.LibraryEntries
import com.backlogbattlers.api.db.UnlockedAchievements
import com.backlogbattlers.api.db.Users
import com.backlogbattlers.api.profile.pictureIdsFor
import com.backlogbattlers.api.scoring.monthlyPointsFor
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import kotlin.uuid.Uuid

// how the app is told the relationship between the signed in user and someone found by search
@Serializable
enum class FriendRelationshipStatus { NONE, PENDING_SENT, PENDING_RECEIVED, FRIENDS }

// one row of the signed in users friend list
@Serializable
data class FriendDto(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val monthlyPoints: Int,
    val achievementCount: Int,
    val isOnline: Boolean,
    // the picture they picked, by the app's id
    val pictureId: String? = null,
)

// an incoming, unanswered friend request
@Serializable
data class FriendRequestDto(
    val requestId: String,
    val fromUserId: String,
    val fromDisplayName: String,
    val fromAvatarUrl: String?,
    val createdAt: Long,
    val fromPictureId: String? = null,
)

// one person found by a friend search, tagged with the caller's relationship to them
@Serializable
data class FriendSearchResultDto(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val status: FriendRelationshipStatus,
    val pictureId: String? = null,
)

// body of POST /users/me/friends/requests
@Serializable
data class CreateFriendRequestBody(val targetUserId: String)

// response body after successfully sending a friend request
@Serializable
data class SendRequestResponseDto(val requestId: String, val targetUserId: String, val createdAt: Long)

private const val MAX_SEARCH_RESULTS = 20

//------------------------------
// counts unlocked achievements across all library entries for specific user
private fun achievementCountFor(userId: Uuid): Int {
    val entryIds = LibraryEntries.selectAll().where { LibraryEntries.userId eq userId }.map { it[LibraryEntries.id] }
    // this if statement avoids an empty query
    if (entryIds.isEmpty()) return 0
    return UnlockedAchievements.selectAll().where { UnlockedAchievements.libraryEntryId inList entryIds }.count().toInt()
}

//------------------------------
// returns a pair of ordered uuids by their string representation
private fun canonicalPair(a: Uuid, b: Uuid): Pair<Uuid, Uuid> {
    return if (a.toString() < b.toString()) a to b else b to a
}

//------------------------------
// checks whether a friendship exists between two users. call it inside a transaction
fun areFriends(a: Uuid, b: Uuid): Boolean {
    val (userAId, userBId) = canonicalPair(a, b)
    return Friendships.selectAll().where { (Friendships.userAId eq userAId) and (Friendships.userBId eq userBId) }.any()
}

//------------------------------
// the user ids of everyone the specified user is friends with. call it inside a transaction
fun friendIdsOf(userId: Uuid): List<Uuid> {
    return Friendships.selectAll()
        .where { (Friendships.userAId eq userId) or (Friendships.userBId eq userId) }
        .map { if (it[Friendships.userAId] == userId) it[Friendships.userBId] else it[Friendships.userAId] }
}

//------------------------------
// determines the relationship status between two users from the perspective of the owner
private fun relationshipStatus(owner: Uuid, other: Uuid): FriendRelationshipStatus {

    // this if statement checks the friendship table first
    if (areFriends(owner, other)) return FriendRelationshipStatus.FRIENDS
    val sentByOwner = FriendRequests.selectAll().where { (FriendRequests.senderId eq owner) and (FriendRequests.receiverId eq other) }.any()

    // this if statement checks if the owner sent a pending request to the other user
    if (sentByOwner) return FriendRelationshipStatus.PENDING_SENT
    val sentByOther = FriendRequests.selectAll().where { (FriendRequests.senderId eq other) and (FriendRequests.receiverId eq owner) }.any()

    // this if statement checks if the other user sent a pending request to the owner
    if (sentByOther) return FriendRelationshipStatus.PENDING_RECEIVED
    return FriendRelationshipStatus.NONE
}

//------------------------------
// returns the friend list for the specified owner user id
fun listFriends(owner: Uuid): List<FriendDto> = transaction {
    val friendships = Friendships.selectAll().where { (Friendships.userAId eq owner) or (Friendships.userBId eq owner) }.toList()

    val pictures = pictureIdsFor(friendships.map { if (it[Friendships.userAId] == owner) it[Friendships.userBId] else it[Friendships.userAId] })

    // this loop maps each friendship row to a FriendDto by loading the other user's details
    val friendDtos = friendships.map { row ->
        val userA = row[Friendships.userAId]
        val userB = row[Friendships.userBId]
        val friendId = if (userA == owner) userB else userA
        val friendUser = Users.selectAll().where { Users.id eq friendId }.single()

        FriendDto(
            userId = friendId.toString(),
            displayName = friendUser[Users.displayName],
            avatarUrl = friendUser[Users.avatarUrl],
            monthlyPoints = monthlyPointsFor(friendId),
            achievementCount = achievementCountFor(friendId),
            isOnline = friendUser[Users.lastLoginDate] == LocalDate.now(),
            pictureId = pictures[friendId],
        )
    }

    // sorts friends by monthly points descending
    friendDtos.sortedWith(compareByDescending<FriendDto> { it.monthlyPoints }.thenBy { it.displayName })
}

//------------------------------
// returns incoming friend requests for the specified owner user id
fun listIncomingRequests(owner: Uuid): List<FriendRequestDto> = transaction {
    val requests = FriendRequests.selectAll()
        .where { FriendRequests.receiverId eq owner }
        .orderBy(FriendRequests.createdAt, SortOrder.DESC)
        .toList()

    val pictures = pictureIdsFor(requests.map { it[FriendRequests.senderId] })

    // this loop maps incoming request rows to FriendRequestDto with sender details
    requests.map { row ->
        val senderId = row[FriendRequests.senderId]
        val sender = Users.selectAll().where { Users.id eq senderId }.single()
        FriendRequestDto(
            requestId = row[FriendRequests.id].toString(),
            fromUserId = senderId.toString(),
            fromDisplayName = sender[Users.displayName],
            fromAvatarUrl = sender[Users.avatarUrl],
            createdAt = row[FriendRequests.createdAt].toEpochMilli(),
            fromPictureId = pictures[senderId],
        )
    }
}

//------------------------------
// searches for users by display name excluding the owner, capped at max search results
fun searchUsers(owner: Uuid, query: String): List<FriendSearchResultDto> = transaction {
    val matches = Users.selectAll()
        .where { (Users.displayName like "%$query%") and (Users.id neq owner) }
        .limit(MAX_SEARCH_RESULTS)
        .toList()

    val pictures = pictureIdsFor(matches.map { it[Users.id] })

    // this loop converts each matching user into a FriendSearchResultDto tagged with relationship status
    matches.map { user ->
        val userId = user[Users.id]
        FriendSearchResultDto(
            userId = userId.toString(),
            displayName = user[Users.displayName],
            avatarUrl = user[Users.avatarUrl],
            status = relationshipStatus(owner, userId),
            pictureId = pictures[userId],
        )
    }
}

// result of attempting to send a friend request
sealed interface SendRequestResult {

    // friend request was sent successfully
    data class Sent(val request: SendRequestResponseDto) : SendRequestResult

    // target user is the caller
    data object CannotAddSelf : SendRequestResult

    // target user does not exist
    data object UserNotFound : SendRequestResult

    // users are already friends
    data object AlreadyFriends : SendRequestResult

    // a request between these users already exists
    data object RequestAlreadyExists : SendRequestResult
}

//------------------------------
// sends a friend request from owner to targetUserId
fun sendRequest(owner: Uuid, targetUserId: Uuid): SendRequestResult = transaction {

    // this if statement stops a user from sending a request to themself
    if (owner == targetUserId) return@transaction SendRequestResult.CannotAddSelf
    val targetExists = Users.selectAll().where { Users.id eq targetUserId }.any()

    // this if statement rejects a target user id that doesn't exist
    if (!targetExists) return@transaction SendRequestResult.UserNotFound

    // this if statement stops a duplicate request between two people who are already friends
    if (areFriends(owner, targetUserId)) return@transaction SendRequestResult.AlreadyFriends
    val existingEitherDirection = FriendRequests.selectAll().where {
        ((FriendRequests.senderId eq owner) and (FriendRequests.receiverId eq targetUserId)) or
            ((FriendRequests.senderId eq targetUserId) and (FriendRequests.receiverId eq owner))
    }.any()

    // this if statement stops a second pending request in either direction between the same two people
    if (existingEitherDirection) return@transaction SendRequestResult.RequestAlreadyExists

    val newId = FriendRequests.insert {
        it[FriendRequests.senderId] = owner
        it[FriendRequests.receiverId] = targetUserId
    }[FriendRequests.id]
    val createdAt = FriendRequests.selectAll().where { FriendRequests.id eq newId }.single()[FriendRequests.createdAt]
    SendRequestResult.Sent(SendRequestResponseDto(newId.toString(), targetUserId.toString(), createdAt.toEpochMilli()))
}

// result of accepting or rejecting a friend request
sealed interface RespondToRequestResult {

    // friend request was accepted
    data class Accepted(val friend: FriendDto) : RespondToRequestResult

    // friend request was rejected
    data object Rejected : RespondToRequestResult

    // friend request was not found
    data object NotFound : RespondToRequestResult
}

//------------------------------
// accepts a pending friend request for the specified owner
fun acceptRequest(owner: Uuid, requestId: Uuid): RespondToRequestResult = transaction {
    val request = FriendRequests.selectAll()
        .where { (FriendRequests.id eq requestId) and (FriendRequests.receiverId eq owner) }
        .singleOrNull() ?: return@transaction RespondToRequestResult.NotFound

    val sender = request[FriendRequests.senderId]
    val (userAId, userBId) = canonicalPair(sender, owner)
    Friendships.insert {
        it[Friendships.userAId] = userAId
        it[Friendships.userBId] = userBId
    }
    FriendRequests.deleteWhere { FriendRequests.id eq requestId }
    recordActivity(owner, ActivityType.FRIEND_ADDED, subject = sender)

    val senderUser = Users.selectAll().where { Users.id eq sender }.single()

    RespondToRequestResult.Accepted(
        FriendDto(
            userId = sender.toString(),
            displayName = senderUser[Users.displayName],
            avatarUrl = senderUser[Users.avatarUrl],
            monthlyPoints = monthlyPointsFor(sender),
            achievementCount = achievementCountFor(sender),
            isOnline = senderUser[Users.lastLoginDate] == LocalDate.now(),
            pictureId = pictureIdsFor(listOf(sender))[sender],
        ),
    )
}

//------------------------------
// rejects a pending friend request for the specified owner
fun rejectRequest(owner: Uuid, requestId: Uuid): RespondToRequestResult = transaction {
    val deleted = FriendRequests.deleteWhere { (FriendRequests.id eq requestId) and (FriendRequests.receiverId eq owner) }
    return@transaction if (deleted > 0) RespondToRequestResult.Rejected else RespondToRequestResult.NotFound
}
//------------------------------EOF------------------------------\\