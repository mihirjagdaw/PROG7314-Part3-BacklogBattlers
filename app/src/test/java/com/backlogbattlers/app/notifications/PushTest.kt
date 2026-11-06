package com.backlogbattlers.app.notifications

import com.backlogbattlers.app.R
import com.backlogbattlers.app.domain.model.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// reading pushes from the API's data payload, and deciding whether to show them
class PushTest {

    private val me = "6f1c0d2e-8a4b-4c7d-9e10-2b3c4d5e6f70"
    private val everythingOn = UserSettings()

    private fun payload(type: String, vararg extra: Pair<String, String>) =
        mapOf("type" to type, "userId" to me, "actorName" to "Ana") + extra

    @Test
    fun `reads each of the four friend and rank pushes the API sends`() {
        assertEquals(PushType.FRIEND_REQUEST_RECEIVED, Push.from(payload("FRIEND_REQUEST_RECEIVED"))?.type)
        assertEquals(PushType.FRIEND_REQUEST_ACCEPTED, Push.from(payload("FRIEND_REQUEST_ACCEPTED"))?.type)
        assertEquals(PushType.OVERTAKEN, Push.from(payload("OVERTAKEN"))?.type)

        val completed = Push.from(payload("FRIEND_COMPLETED_GAME", "gameTitle" to "Hades"))
        assertEquals(Push(PushType.FRIEND_COMPLETED_GAME, me, "Ana", "Hades"), completed)
    }

    @Test
    fun `a push this version doesn't know, or one missing its fields, is ignored`() {
        assertNull(Push.from(payload("SOMETHING_NEW")))
        assertNull(Push.from(mapOf("type" to "OVERTAKEN", "actorName" to "Ana")))
        assertNull(Push.from(mapOf("type" to "OVERTAKEN", "userId" to me, "actorName" to " ")))
        assertNull(Push.from(emptyMap()))
    }

    @Test
    fun `reads the achievement push, which needs a count but no friend`() {
        val data = mapOf("type" to "ACHIEVEMENT_UNLOCKED", "userId" to me, "gameTitle" to "Hades", "count" to "3", "achievementName" to "Beat the Game")

        assertEquals(
            Push(PushType.ACHIEVEMENT_UNLOCKED, me, gameTitle = "Hades", count = 3, achievementName = "Beat the Game"),
            Push.from(data),
        )
        // spread over several games there is no game title
        assertEquals(null, Push.from(data - "gameTitle" - "achievementName")?.gameTitle)
        assertNull(Push.from(data - "count"))
        assertNull(Push.from(data + ("count" to "0")))
        assertNull(Push.from(data + ("count" to "many")))
    }

    @Test
    fun `reads the season push, with or without a rank`() {
        val ranked = Push.from(mapOf("type" to "SEASON_RESET", "userId" to me, "monthPeriod" to "2026-12", "lastRank" to "2", "lastPoints" to "120"))
        assertEquals(Push(PushType.SEASON_RESET, me, lastRank = 2, lastPoints = 120), ranked)

        val unranked = Push.from(mapOf("type" to "SEASON_RESET", "userId" to me, "lastPoints" to "0"))
        assertEquals(Push(PushType.SEASON_RESET, me, lastRank = null, lastPoints = 0), unranked)
        assertNull(Push.from(mapOf("type" to "SEASON_RESET")))
    }

    @Test
    fun `the achievements switch hides achievement pushes, and the season resets switch hides season pushes`() {
        val achievementsOff = everythingOn.copy(achievementNotificationsEnabled = false)
        val seasonOff = everythingOn.copy(seasonResetNotificationsEnabled = false)
        val achievements = Push(PushType.ACHIEVEMENT_UNLOCKED, me, count = 1)
        val season = Push(PushType.SEASON_RESET, me)

        assertTrue(achievements.shouldShow(me, everythingOn))
        assertTrue(season.shouldShow(me, everythingOn))
        assertFalse(achievements.shouldShow(me, achievementsOff))
        assertTrue(season.shouldShow(me, achievementsOff))
        assertFalse(season.shouldShow(me, seasonOff))
        assertTrue(achievements.shouldShow(me, seasonOff))
        // neither of the two switches touches the friend or rank pushes
        assertTrue(Push(PushType.OVERTAKEN, me, "Ana").shouldShow(me, achievementsOff.copy(seasonResetNotificationsEnabled = false)))
    }

    @Test
    fun `a push is shown only to the signed-in account it was sent to`() {
        val push = Push(PushType.OVERTAKEN, me, "Ana")

        assertTrue(push.shouldShow(me, everythingOn))
        assertFalse(push.shouldShow(null, everythingOn))
        assertFalse(push.shouldShow("someone-else", everythingOn))
    }

    @Test
    fun `the friend activity switch hides friend pushes, and rank changes hides overtaken`() {
        val friendsOff = everythingOn.copy(friendActivityNotificationsEnabled = false)
        val rankOff = everythingOn.copy(rankChangeNotificationsEnabled = false)

        assertFalse(Push(PushType.FRIEND_REQUEST_RECEIVED, me, "Ana").shouldShow(me, friendsOff))
        assertFalse(Push(PushType.FRIEND_REQUEST_ACCEPTED, me, "Ana").shouldShow(me, friendsOff))
        assertFalse(Push(PushType.FRIEND_COMPLETED_GAME, me, "Ana").shouldShow(me, friendsOff))
        assertTrue(Push(PushType.OVERTAKEN, me, "Ana").shouldShow(me, friendsOff))

        assertFalse(Push(PushType.OVERTAKEN, me, "Ana").shouldShow(me, rankOff))
        assertTrue(Push(PushType.FRIEND_REQUEST_RECEIVED, me, "Ana").shouldShow(me, rankOff))
    }

    @Test
    fun `an achievement push, about something you did yourself, is held back while the app is open`() {
        val achievements = Push(PushType.ACHIEVEMENT_UNLOCKED, me, count = 2)

        assertFalse(achievements.shouldShow(me, everythingOn, appOpen = true))
        assertTrue(achievements.shouldShow(me, everythingOn, appOpen = false))
        // and the app being closed is the default
        assertTrue(achievements.shouldShow(me, everythingOn))
    }

    @Test
    fun `pushes caused by someone else or by the calendar still show while the app is open`() {
        val people = listOf(PushType.FRIEND_REQUEST_RECEIVED, PushType.FRIEND_REQUEST_ACCEPTED, PushType.FRIEND_COMPLETED_GAME, PushType.OVERTAKEN)

        people.forEach { assertTrue("$it", Push(it, me, "Ana").shouldShow(me, everythingOn, appOpen = true)) }
        assertTrue(Push(PushType.SEASON_RESET, me).shouldShow(me, everythingOn, appOpen = true))
        // the achievement push is the only one held back
        assertEquals(listOf(PushType.ACHIEVEMENT_UNLOCKED), PushType.entries.filter { it.hiddenWhileAppOpen })
    }

    @Test
    fun `being open does not make a push show that its switch or its account would hide`() {
        val friendsOff = everythingOn.copy(friendActivityNotificationsEnabled = false)

        assertFalse(Push(PushType.FRIEND_REQUEST_RECEIVED, me, "Ana").shouldShow(me, friendsOff, appOpen = true))
        assertFalse(Push(PushType.FRIEND_REQUEST_RECEIVED, me, "Ana").shouldShow("someone-else", everythingOn, appOpen = true))
    }

    @Test
    fun `tapping a push opens its matching screen`() {
        assertEquals(R.id.friendsFragment, PushNotifications.destinationOf(PushType.FRIEND_REQUEST_RECEIVED))
        assertEquals(R.id.friendsFragment, PushNotifications.destinationOf(PushType.FRIEND_REQUEST_ACCEPTED))
        assertEquals(R.id.activityFragment, PushNotifications.destinationOf(PushType.FRIEND_COMPLETED_GAME))
        assertEquals(R.id.standingsFragment, PushNotifications.destinationOf(PushType.OVERTAKEN))
        assertEquals(R.id.standingsFragment, PushNotifications.destinationOf(PushType.SEASON_RESET))
        // the profile's recent activity lists what was unlocked
        assertEquals(R.id.profileFragment, PushNotifications.destinationOf(PushType.ACHIEVEMENT_UNLOCKED))

        // a friend request opens the friends screen on its requests tab
        assertEquals(OPEN_TAB_REQUESTS, PushType.FRIEND_REQUEST_RECEIVED.friendsTab)
        assertNull(PushType.FRIEND_REQUEST_ACCEPTED.friendsTab)
    }

    @Test
    fun `friend pushes and competition pushes go to separate channels`() {
        assertEquals(PushChannel.FRIENDS, PushType.FRIEND_REQUEST_RECEIVED.channel)
        assertEquals(PushChannel.FRIENDS, PushType.FRIEND_COMPLETED_GAME.channel)
        assertEquals(PushChannel.COMPETITION, PushType.OVERTAKEN.channel)
        assertEquals(PushChannel.COMPETITION, PushType.SEASON_RESET.channel)
        assertEquals(PushChannel.ACHIEVEMENTS, PushType.ACHIEVEMENT_UNLOCKED.channel)
    }
}
//------------------------------EOF------------------------------\\
