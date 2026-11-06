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
import com.backlogbattlers.app.domain.model.Friend
import com.backlogbattlers.app.util.avatarColorFor
import com.backlogbattlers.app.util.showProfilePicture
import com.backlogbattlers.app.util.formatPoints
import com.backlogbattlers.app.util.initialsFor
import com.backlogbattlers.app.util.medalRingFor
import com.backlogbattlers.app.util.medalTierColor

// adapter for displaying a ranked list of friends and the signed-in user
class FriendAdapter(
    var currentUserId: String? = null,
    var showRank: Boolean = true,
    // a tap on a friend's row, which opens their profile
    private val onClick: ((Friend) -> Unit)? = null,
) : ListAdapter<Friend, FriendAdapter.ViewHolder>(DiffCallback) {

    // viewholder wrapping views for a single friend row
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

    // diffutil callback comparing friend items for efficient recyclerview updates
    private object DiffCallback : DiffUtil.ItemCallback<Friend>() {
        //------------------------------
        // checks if two friend items represent the same user by id
        override fun areItemsTheSame(oldItem: Friend, newItem: Friend): Boolean = oldItem.userId == newItem.userId

        //------------------------------
        // checks if all properties of two friend items are identical
        override fun areContentsTheSame(oldItem: Friend, newItem: Friend): Boolean = oldItem == newItem
    }

    //------------------------------
    // inflates the friend item layout and creates a viewholder instance
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_friend, parent, false)
        return ViewHolder(view)
    }

    //------------------------------
    // binds friend data to the viewholder for the given list position
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val friend = getItem(position)
        val context = holder.itemView.context
        val rank = position + 1

        // this if statement decides whether to render rank indicators or hide them completely when showRank is false
        if (showRank) {
            val ringRes = medalRingFor(rank)
            // this if statement decides whether to show the medal ring or the plain rank number
            if (ringRes != null) {
                holder.ivAvatarRing.setImageResource(ringRes)
                holder.ivAvatarRing.visibility = View.VISIBLE
                holder.tvRank.visibility = View.GONE
            } else {
                holder.ivAvatarRing.visibility = View.GONE
                holder.tvRank.text = rank.toString()
                holder.tvRank.setTextColor(ContextCompat.getColor(context, medalTierColor(rank)))
                holder.tvRank.visibility = View.VISIBLE
            }
        } else {
            holder.ivAvatarRing.visibility = View.GONE
            holder.tvRank.visibility = View.GONE
        }

        holder.tvAvatarInitial.text = initialsFor(friend.displayName)
        val colorRes = avatarColorFor(friend.userId)
        holder.tvAvatarInitial.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
        holder.ivAvatarPicture.showProfilePicture(friend.pictureId)

        holder.tvFriendName.text = friend.displayName
        holder.itemView.setOnClickListener(onClick?.let { tapped -> View.OnClickListener { tapped(friend) } })

        val isSelf = friend.userId == currentUserId
        // this if statement shows the (You) suffix and applies a highlighted card background when the row is the signed in user
        if (isSelf) {
            holder.tvYouSuffix.visibility = View.VISIBLE
            holder.cardFriend.setBackgroundResource(R.drawable.bg_card_highlighted)
        } else {
            holder.tvYouSuffix.visibility = View.GONE
            holder.cardFriend.setBackgroundResource(R.drawable.bg_card)
        }

        // this if statement sets online status text and color
        if (friend.isOnline) {
            holder.tvOnlineStatus.setText(R.string.friend_online)
            holder.tvOnlineStatus.setTextColor(ContextCompat.getColor(context, R.color.success))
        } else {
            holder.tvOnlineStatus.setText(R.string.friend_offline)
            holder.tvOnlineStatus.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        }

        holder.tvPoints.text = context.getString(R.string.points_short, formatPoints(friend.monthlyPoints))
        holder.tvAchievementCount.text = context.getString(R.string.friend_achievement_count, friend.achievementCount)
    }
}
//------------------------------EOF------------------------------\\