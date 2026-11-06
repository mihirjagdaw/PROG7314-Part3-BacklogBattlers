package com.backlogbattlers.api.scoring

import com.backlogbattlers.api.db.DatabaseFactory
import com.backlogbattlers.api.db.Friendships
import com.backlogbattlers.api.db.MonthlyLeaderboardEntries
import com.backlogbattlers.api.db.Users
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class LeaderboardTest {

    @Test
    fun `competition ranking gives a two way tie the same rank and skips the rank after it`() {
        assertEquals(listOf(1, 2, 2, 4), competitionRanks(listOf(100, 80, 80, 50)))
    }

    @Test
    fun `a run of everybody tied gives everybody rank 1`() {
        assertEquals(listOf(1, 1, 1), competitionRanks(listOf(50, 50, 50)))
    }

    @Test
    fun `no ties at all is a plain 1, 2, 3`() {
        assertEquals(listOf(1, 2, 3), competitionRanks(listOf(30, 20, 10)))
    }

    @Test
    fun `an empty list has no ranks`() {
        assertEquals(emptyList(), competitionRanks(emptyList()))
    }

    // The tests below need a database, since monthlyLeaderboard and monthlyPointsFor read one.
    private val db = DatabaseFactory.init(jdbcUrl = null, memoryName = "leaderboard-${Uuid.random()}")
    private val month = "2026-09"

    private fun newUser(name: String): Uuid = transaction(db) {
        Users.insert {
            it[googleSubjectId] = "google-${Uuid.random()}"
            it[displayName] = name
            it[email] = "$name@example.com"
            it[lastLoginDate] = LocalDate.now()
        }[Users.id]
    }

    private fun givePoints(user: Uuid, points: Int, monthPeriod: String = month) = transaction(db) {
        MonthlyLeaderboardEntries.insert {
            it[MonthlyLeaderboardEntries.userId] = user
            it[MonthlyLeaderboardEntries.monthPeriod] = monthPeriod
            it[monthlyPoints] = points
        }
    }

    // friendships are stored once per pair, with the smaller id (as a string) first
    private fun befriend(a: Uuid, b: Uuid) = transaction(db) {
        val (first, second) = if (a.toString() < b.toString()) a to b else b to a
        Friendships.insert {
            it[userAId] = first
            it[userBId] = second
        }
    }

    @Test
    fun `monthlyPointsFor is 0 for someone with no score this month`() {
        val user = newUser("Nobody")
        assertEquals(0, monthlyPointsFor(user, month))
    }

    @Test
    fun `monthlyPointsFor reads back what was stored`() {
        val user = newUser("Scorer")
        givePoints(user, 340)
        assertEquals(340, monthlyPointsFor(user, month))
    }

    @Test
    fun `the board is the caller and their friends, highest first, with the real ranking rule applied`() {
        val me = newUser("Me")
        val alice = newUser("Alice")
        val bob = newUser("Bob")
        val cara = newUser("Cara")
        listOf(alice, bob, cara).forEach { befriend(me, it) }
        givePoints(alice, 500)
        givePoints(bob, 300)
        givePoints(cara, 300) // ties with Bob
        givePoints(me, 100)

        val board = monthlyLeaderboard(caller = me, monthPeriod = month)

        assertEquals(listOf("Alice", "Bob", "Cara", "Me"), board.entries.map { it.displayName })
        assertEquals(listOf(1, 2, 2, 4), board.entries.map { it.rank })
        assertEquals(month, board.monthPeriod)
    }

    @Test
    fun `people who are not your friends are never on your board, however many points they have`() {
        val me = newUser("Me")
        val friend = newUser("Friend")
        val stranger = newUser("Stranger")
        val friendOfFriend = newUser("FriendOfFriend")
        befriend(me, friend)
        befriend(friend, friendOfFriend)
        givePoints(stranger, 9_000)
        givePoints(friendOfFriend, 5_000)
        givePoints(friend, 10)

        val board = monthlyLeaderboard(caller = me, monthPeriod = month)

        assertEquals(listOf("Friend", "Me"), board.entries.map { it.displayName })
    }

    @Test
    fun `friends who have not scored this month are listed at zero, sharing the last rank, by name`() {
        val me = newUser("Me")
        val scorer = newUser("Scorer")
        val zed = newUser("Zed")
        val amy = newUser("amy") // lower case, still sorted as A
        listOf(scorer, zed, amy).forEach { befriend(me, it) }
        givePoints(scorer, 200)
        givePoints(zed, 999, monthPeriod = "2026-08") // last month's points don't count

        val board = monthlyLeaderboard(caller = me, monthPeriod = month)

        assertEquals(listOf("Scorer", "amy", "Me", "Zed"), board.entries.map { it.displayName })
        assertEquals(listOf(200, 0, 0, 0), board.entries.map { it.monthlyPoints })
        assertEquals(listOf(1, 2, 2, 2), board.entries.map { it.rank })
    }

    @Test
    fun `the caller's own row is pinned even when it falls outside the returned page`() {
        val leader = newUser("Leader")
        val last = newUser("LastPlace")
        befriend(leader, last)
        givePoints(leader, 1000)
        givePoints(last, 1)

        val board = monthlyLeaderboard(caller = last, monthPeriod = month, limit = 1)

        assertEquals(listOf("Leader"), board.entries.map { it.displayName })
        assertEquals("LastPlace", board.me?.displayName)
        assertEquals(2, board.me?.rank)
        assertEquals(1, board.me?.monthlyPoints)
    }

    @Test
    fun `someone with no friends yet sees just themselves`() {
        val user = newUser("EarlyBird")

        val board = monthlyLeaderboard(caller = user, monthPeriod = "2020-01")

        assertEquals(listOf("EarlyBird"), board.entries.map { it.displayName })
        assertEquals(1, board.me?.rank)
        assertEquals(0, board.me?.monthlyPoints)
    }

    @Test
    fun `nobody signed in means an empty board and no me row`() {
        givePoints(newUser("Someone"), 50)

        val board = monthlyLeaderboard(caller = null, monthPeriod = month)

        assertNull(board.me)
        assertEquals(emptyList(), board.entries)
    }
}
