package com.backlogbattlers.app.adapters

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.backlogbattlers.app.R
import com.backlogbattlers.app.domain.model.Game
import com.backlogbattlers.app.domain.model.LibraryStatus
import com.backlogbattlers.app.domain.model.Platform
import com.backlogbattlers.app.util.formatHours
import com.backlogbattlers.app.util.formatHoursPlayed
import com.backlogbattlers.app.util.formatPoints
import com.backlogbattlers.app.util.inflateChild
import com.backlogbattlers.app.util.loadArtwork
import com.backlogbattlers.app.util.loadLandscapeArt
import com.backlogbattlers.app.util.platformLabel
import com.backlogbattlers.app.util.statusColor
import com.backlogbattlers.app.util.statusLabel
import com.backlogbattlers.app.util.setVisible
import com.backlogbattlers.app.viewmodels.GameDetailUiState

// the game detail screen's single-item header: hero art through the stat tiles and the
// "Achievements" label. Rides as item 0 of the screen's ConcatAdapter, ahead of the achievement
// rows - see fragment_game_detail.xml for why this is not a separate scrolling header
class GameDetailHeaderAdapter(
    private val onBack: () -> Unit,
    private val onFavouriteToggle: () -> Unit,
    private val onComplete: () -> Unit,
    // a tap on the status tile, with the tile for the screen to open its dropdown on
    private val onStatusClick: (View) -> Unit,
    private val onEditHours: () -> Unit,
) : RecyclerView.Adapter<GameDetailHeaderAdapter.ViewHolder>() {

    private var state = GameDetailUiState()
    private var isFavourite = false

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(parent.inflateChild(R.layout.partial_detail_header), onBack, onFavouriteToggle, onComplete, onStatusClick, onEditHours)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(state, isFavourite)
    }

    fun submitState(newState: GameDetailUiState) {
        state = newState
        notifyItemChanged(0)
    }

    fun setFavourite(value: Boolean) {
        isFavourite = value
        notifyItemChanged(0)
    }

    class ViewHolder(
        view: View,
        onBack: () -> Unit,
        onFavouriteToggle: () -> Unit,
        onComplete: () -> Unit,
        private val onStatusClick: (View) -> Unit,
        private val onEditHours: () -> Unit,
    ) : RecyclerView.ViewHolder(view) {

        private val backdrop: ImageView = view.findViewById(R.id.iv_detail_backdrop)
        private val cover: ImageView = view.findViewById(R.id.iv_detail_cover)
        private val favouriteButton: ImageView = view.findViewById(R.id.btn_detail_favourite)
        private val title: TextView = view.findViewById(R.id.tv_detail_title)
        private val platformChip: TextView = view.findViewById(R.id.tv_detail_platform)
        private val genreChip: TextView = view.findViewById(R.id.tv_detail_genre)
        private val completionPercent: TextView = view.findViewById(R.id.tv_detail_completion_percent)
        private val completionBar: ProgressBar = view.findViewById(R.id.progress_detail_completion)
        private val statPoints: TextView = view.findViewById(R.id.tv_stat_points)
        private val statAchievements: TextView = view.findViewById(R.id.tv_stat_achievements)
        private val statCompletionTime: TextView = view.findViewById(R.id.tv_stat_completion_time)
        private val completeButton: TextView = view.findViewById(R.id.btn_detail_complete)

        // the two tiles of the progress card: the status dropdown and the hours played
        private val statusTile: View = view.findViewById(R.id.tile_detail_status)
        private val statusDot: View = view.findViewById(R.id.dot_detail_status)
        private val statusValue: TextView = view.findViewById(R.id.tv_detail_status_value)
        private val statusIcon: ImageView = view.findViewById(R.id.iv_detail_status_icon)
        private val hoursTile: View = view.findViewById(R.id.tile_detail_hours)
        private val hoursValue: TextView = view.findViewById(R.id.tv_detail_hours_value)
        private val hoursIcon: ImageView = view.findViewById(R.id.iv_detail_hours_icon)

        init {
            view.findViewById<View>(R.id.btn_detail_back).setOnClickListener { onBack() }
            favouriteButton.setOnClickListener { onFavouriteToggle() }
            completeButton.setOnClickListener { onComplete() }
        }

        fun bind(state: GameDetailUiState, isFavourite: Boolean) {
            val context = itemView.context

            renderGame(state.game, state.platform)

            completionPercent.text = context.getString(R.string.detail_completion_value, state.completionPercent)
            completionBar.progress = state.completionPercent

            statPoints.text = context.getString(
                R.string.detail_stat_ratio,
                formatPoints(state.earnedPoints),
                formatPoints(state.totalPoints),
            )
            statAchievements.text = context.getString(
                R.string.detail_stat_ratio,
                state.unlockedCount.toString(),
                state.totalCount.toString(),
            )
            statCompletionTime.text = context.formatHours(state.game?.avgCompletionHours)

            val color = if (isFavourite) R.color.destructive else R.color.white
            favouriteButton.imageTintList = ContextCompat.getColorStateList(context, color)

            renderCompletion(state)
            renderProgress(state)
        }

        // the status with a dot in its colour, and the hours logged. both tiles open something to change them,
        // except on a game completed to 100%: that is finished with, and its hours are what the bonus was worked
        // out from, so the tiles show a lock instead of the chevron and the pencil, and a tap does nothing.
        // a game whose story is completed is not finished with: there is still the 100% to go after
        private fun renderProgress(state: GameDetailUiState) {
            val context = itemView.context
            val completed = state.hundredPercentCompleted
            // a game that is not in the library yet reads as not started, which is what picking anything makes it
            val shown = state.status ?: LibraryStatus.BACKLOG

            statusValue.setText(statusLabel(shown))
            statusDot.backgroundTintList = ContextCompat.getColorStateList(context, statusColor(shown))
            statusIcon.setImageResource(if (completed) R.drawable.ic_lock else R.drawable.ic_chevron_down)
            statusTile.isEnabled = !completed
            statusTile.setOnClickListener { if (!completed) onStatusClick(statusTile) }

            val hours = state.hoursPlayed
            hoursValue.text = if (hours == null || hours <= 0f) {
                context.getString(R.string.hours_unknown)
            } else {
                context.getString(R.string.hours_played_value, formatHoursPlayed(hours))
            }
            hoursIcon.setVisible(!completed)
            hoursTile.isEnabled = !completed
            hoursTile.setOnClickListener { if (!completed) onEditHours() }
        }

        // the button under the two tiles. it reads "Mark as complete" until the story is completed, then
        // "Complete to 100%", and says so while the backend is working. there is nothing left to do at 100%, so it goes
        private fun renderCompletion(state: GameDetailUiState) {
            completeButton.setVisible(!state.hundredPercentCompleted)
            completeButton.isEnabled = !state.completing
            completeButton.setText(
                when {
                    state.completing -> R.string.detail_completing
                    state.storyCompleted -> R.string.detail_complete_hundred_action
                    else -> R.string.detail_complete_button
                },
            )
        }

        private fun renderGame(game: Game?, platform: Platform?) {
            game ?: return
            backdrop.loadLandscapeArt(game.landscapeArtUrls)
            cover.loadArtwork(game.coverImageUrl ?: game.artworkUrl)
            title.text = game.title

            platformChip.text = platform?.let { itemView.context.platformLabel(it.name) }
            platformChip.setVisible(platform != null)

            val genre = game.genres.firstOrNull()
            genreChip.text = genre
            genreChip.setVisible(genre != null)
        }
    }
}
//------------------------------EOF------------------------------\\
