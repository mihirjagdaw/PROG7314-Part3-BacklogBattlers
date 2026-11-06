package com.backlogbattlers.app.util

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.backlogbattlers.app.R
import com.backlogbattlers.app.domain.model.LibraryStatus

// the statuses a player can pick for a game, in the order the dropdown lists them. completed is not one of
// them: only completing the game sets it
val PICKABLE_STATUSES: List<LibraryStatus> = listOf(LibraryStatus.BACKLOG, LibraryStatus.PLAYING, LibraryStatus.ABANDONED)

//------------------------------
// what the dropdown lists for a game in status [current]. a game whose story is completed (status completed)
// lists completed, which it already is, with in progress and abandoned for going after 100%, and no longer
// offers not started
fun statusChoices(current: LibraryStatus?): List<LibraryStatus> =
    if (current == LibraryStatus.COMPLETED) listOf(LibraryStatus.COMPLETED, LibraryStatus.PLAYING, LibraryStatus.ABANDONED) else PICKABLE_STATUSES

//------------------------------
// what a status is called on the game detail screen, in the user's language
@StringRes
fun statusLabel(status: LibraryStatus): Int = when (status) {
    LibraryStatus.BACKLOG -> R.string.status_backlog
    LibraryStatus.PLAYING -> R.string.status_playing
    LibraryStatus.ABANDONED -> R.string.status_abandoned
    LibraryStatus.COMPLETED -> R.string.status_completed
}

//------------------------------
// the colour of the dot in front of a status: grey for not started, blue for in progress, gold for abandoned
// and green for completed
@ColorRes
fun statusColor(status: LibraryStatus): Int = when (status) {
    LibraryStatus.BACKLOG -> R.color.text_tertiary
    LibraryStatus.PLAYING -> R.color.accent
    LibraryStatus.ABANDONED -> R.color.gold
    LibraryStatus.COMPLETED -> R.color.success
}
//------------------------------EOF------------------------------\\
