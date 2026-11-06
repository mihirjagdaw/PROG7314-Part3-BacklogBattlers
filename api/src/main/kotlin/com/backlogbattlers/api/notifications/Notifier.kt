package com.backlogbattlers.api.notifications

import com.backlogbattlers.api.db.Friendships
import com.backlogbattlers.api.db.Games
import com.backlogbattlers.api.db.LibraryEntries
import com.backlogbattlers.api.db.MonthlyLeaderboardEntries
import com.backlogbattlers.api.db.Users
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * Turns things that happen in the API into pushes: works out who to tell, checks each person's
 * notification switches (see [wantsPush]), and sends to every phone they are signed in on.
 *
 * Each method returns at once. The sending happens through [launch], in the background by
 * default, so a route never waits on Firebase and a failed push never fails the request.
 */
class Notifier(
    private val sender: PushSender,
    private val launch: (suspend () -> Unit) -> Unit = backgroundLauncher(),
) {

    /** [receiver] got a friend request from [sender]. */
    fun friendRequestReceived(receiver: Uuid, sender: Uuid) = later {
        val name = displayName(sender) ?: return@later
        deliver(receiver, Push(PushType.FRIEND_REQUEST_RECEIVED, actorName = name))
    }

    /** [accepter] accepted the friend request [requestSender] sent them. */
    fun friendRequestAccepted(requestSender: Uuid, accepter: Uuid) = later {
        val name = displayName(accepter) ?: return@later
        deliver(requestSender, Push(PushType.FRIEND_REQUEST_ACCEPTED, actorName = name))
    }

    /**
     * [completer] marked library entry [entryId] complete, taking their points for [monthPeriod]
     * from [pointsBefore] to [pointsAfter]. Their friends hear about the game, and anyone they
     * passed on the leaderboard hears that too.
     */
    fun gameCompleted(completer: Uuid, entryId: Uuid, pointsBefore: Int, pointsAfter: Int, monthPeriod: String) = later {
        val name = displayName(completer) ?: return@later
        val gameTitle = gameTitleFor(entryId)
        if (gameTitle != null) {
            friendIdsOf(completer).forEach { friend ->
                deliver(friend, Push(PushType.FRIEND_COMPLETED_GAME, actorName = name, gameTitle = gameTitle))
            }
        }
        overtaken(completer, pointsBefore, pointsAfter, standings(monthPeriod)).forEach { passed ->
            deliver(passed, Push(PushType.OVERTAKEN, actorName = name))
        }
    }

    /**
     * [owner] has [count] new achievements in [gameTitle] (null when they are spread over several games).
     * [rarest] is the rarest one's name, when it is known.
     */
    fun achievementsUnlocked(owner: Uuid, gameTitle: String?, count: Int, rarest: String?) = later {
        deliver(owner, Push(PushType.ACHIEVEMENT_UNLOCKED, gameTitle = gameTitle, achievementName = rarest, count = count))
    }

    /** A new season began. [user] finished [monthPeriod]'s predecessor in [lastRank] place (null: no points) with [lastPoints]. */
    fun seasonStarted(user: Uuid, monthPeriod: String, lastRank: Int?, lastPoints: Int) = later {
        deliver(user, Push(PushType.SEASON_RESET, monthPeriod = monthPeriod, lastRank = lastRank, lastPoints = lastPoints))
    }

    /**
     * Sends [push] to every phone of [recipient], unless they switched that kind off. Returns how
     * many phones it was sent to: 0 when the kind is switched off or they have no phone signed in.
     */
    suspend fun deliver(recipient: Uuid, push: Push): Int {
        if (!wantsPush(recipient, push.type)) {
            log.debug("{} is switched off for this user, not sending", push.type)
            return 0
        }
        val tokens = tokensFor(recipient)
        if (tokens.isEmpty()) return 0
        forgetTokens(sender.send(tokens, push.data(recipient)))
        return tokens.size
    }

    private fun later(block: suspend () -> Unit) = launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("A push could not be sent", e)
        }
    }

    private fun displayName(userId: Uuid): String? = transaction {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.displayName)
    }

    private fun gameTitleFor(entryId: Uuid): String? = transaction {
        val gameId = LibraryEntries.selectAll().where { LibraryEntries.id eq entryId }.singleOrNull()
            ?.get(LibraryEntries.gameId) ?: return@transaction null
        Games.selectAll().where { Games.id eq gameId }.singleOrNull()?.get(Games.title)
    }

    private fun friendIdsOf(userId: Uuid): List<Uuid> = transaction {
        Friendships.selectAll()
            .where { (Friendships.userAId eq userId) or (Friendships.userBId eq userId) }
            .map { if (it[Friendships.userAId] == userId) it[Friendships.userBId] else it[Friendships.userAId] }
    }

    private fun standings(monthPeriod: String): Map<Uuid, Int> = transaction {
        MonthlyLeaderboardEntries.selectAll()
            .where { MonthlyLeaderboardEntries.monthPeriod eq monthPeriod }
            .associate { it[MonthlyLeaderboardEntries.userId] to it[MonthlyLeaderboardEntries.monthlyPoints] }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Notifier::class.java)

        /** For route tests that are not about pushes: nothing is looked up and nothing is sent. */
        val Disabled = Notifier(sender = { _, _ -> emptyList() }, launch = { })

        /** Runs each push on its own coroutine, so a slow or failing one never holds up the others. */
        fun backgroundLauncher(): (suspend () -> Unit) -> Unit {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            return { block -> scope.launch { block() } }
        }

        /** Runs each push before the method returns, so tests can check what was sent. */
        val inline: (suspend () -> Unit) -> Unit = { block -> runBlocking { block() } }
    }
}
//------------------------------EOF------------------------------\\
