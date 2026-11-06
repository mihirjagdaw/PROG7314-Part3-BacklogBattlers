package com.backlogbattlers.app.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.backlogbattlers.app.R
import com.backlogbattlers.app.domain.model.LeaderboardEntry
import com.backlogbattlers.app.util.avatarColorFor
import com.backlogbattlers.app.util.showProfilePicture
import com.backlogbattlers.app.util.formatPoints
import com.backlogbattlers.app.util.initialsFor
import com.backlogbattlers.app.util.medalRingFor
import com.backlogbattlers.app.util.medalTierColor
import com.backlogbattlers.app.util.setVisible

// adapter for the monthly leaderboard. reuses item_friend's row layout (same look as the
// Friends list), but hides the online-status and achievement-count rows: the leaderboard
// endpoint doesn't return either, and the rank comes from the server, not list position,
// since tied scores share a rank there (see LeaderboardEntry).
class LeaderboardAdapter(
    var currentUserId: String? = null,
    // a tap on a row, which opens that player's profile
    private val onClick: ((LeaderboardEntry) -> Unit)? = null,
) : ListAdapter<LeaderboardEntry, LeaderboardAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardFriend: View = itemView.findViewById(R.id.card_friend)
        val ivAvatarRing: ImageView = itemView.findViewById(R.id.iv_avatar_ring)
        val tvAvatarInitial: TextView = itemView.findViewById(R.id.tv_avatar_initial)
        val ivAvatarPicture: ImageView = itemView.findViewById(R.id.iv_avatar_picture)
        val tvRank: TextView = itemView.findViewById(R.id.tv_rank)
        val tvFriendName: TextView = itemView.findViewById(R.id.tv_friend_name)
        val tvYouSuffix: TextView = itemView.findViewById(R.id.tv_you_suffix)
        val tvOnlineStatus: TextView = itemView.findViewById(R.id.tv_online_status)
        val tvPoints: TextView = itemView.findViewById(R.id.tv_points)
        val tvAchievementCount: TextView = itemView.findViewById(R.id.tv_achievement_count)
    }

    private object DiffCallback : DiffUtil.ItemCallback<LeaderboardEntry>() {
        override fun areItemsTheSame(oldItem: LeaderboardEntry, newItem: LeaderboardEntry): Boolean = oldItem.userId == newItem.userId
        override fun areContentsTheSame(oldItem: LeaderboardEntry, newItem: LeaderboardEntry): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_friend, parent, false)
        return ViewHolder(view)
    }

    //------------------------------
    // binds one leaderboard row. rank comes from entry.rank (the server's, with ties), not position
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val context = holder.itemView.context

        val ringRes = medalRingFor(entry.rank)
        // this if statement decides whether to show the medal ring (top 3) or the plain rank number
        if (ringRes != null) {
            holder.ivAvatarRing.setImageResource(ringRes)
            holder.ivAvatarRing.visibility = View.VISIBLE
            holder.tvRank.visibility = View.GONE
        } else {
            holder.ivAvatarRing.visibility = View.GONE
            holder.tvRank.text = entry.rank.toString()
            holder.tvRank.setTextColor(ContextCompat.getColor(context, medalTierColor(entry.rank)))
            holder.tvRank.visibility = View.VISIBLE
        }

        holder.tvAvatarInitial.text = initialsFor(entry.displayName)
        val colorRes = avatarColorFor(entry.userId)
        holder.tvAvatarInitial.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
        holder.ivAvatarPicture.showProfilePicture(entry.pictureId)

        holder.tvFriendName.text = entry.displayName
        holder.itemView.setOnClickListener(onClick?.let { tapped -> View.OnClickListener { tapped(entry) } })

        val isSelf = entry.userId == currentUserId
        // this if statement shows the (You) suffix and applies a highlighted card background for the signed in user's own row
        if (isSelf) {
            holder.tvYouSuffix.visibility = View.VISIBLE
            holder.cardFriend.setBackgroundResource(R.drawable.bg_card_highlighted)
        } else {
            holder.tvYouSuffix.visibility = View.GONE
            holder.cardFriend.setBackgroundResource(R.drawable.bg_card)
        }

        // no online status or achievement count for a leaderboard row; hide both rather than leave them blank
        holder.tvOnlineStatus.setVisible(false)
        holder.tvAchievementCount.setVisible(false)

        holder.tvPoints.text = holder.itemView.context.getString(R.string.points_short, formatPoints(entry.monthlyPoints))
    }
}
//------------------------------EOF------------------------------\\
