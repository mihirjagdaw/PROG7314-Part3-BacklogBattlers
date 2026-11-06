package com.backlogbattlers.app.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.backlogbattlers.app.R
import com.backlogbattlers.app.domain.model.ActivityEvent
import com.backlogbattlers.app.util.activityDayLabel
import com.backlogbattlers.app.util.activityIconFor
import com.backlogbattlers.app.util.activityIconTint
import com.backlogbattlers.app.util.activityMeta
import com.backlogbattlers.app.util.activityText
import com.backlogbattlers.app.util.avatarColorFor
import com.backlogbattlers.app.util.initialsFor
import com.backlogbattlers.app.util.profilePictureFor
import com.backlogbattlers.app.util.setVisible
import com.backlogbattlers.app.viewmodels.ActivityRow
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView

// the feed itself: a day header over each run of that day's events. the activity screen joins this
// with an intro row above it and a status row below it in one ConcatAdapter
class ActivityFeedAdapter(
    // a tap on an event: a completion opens its game, anything else opens the person's profile
    private val onEventClick: (ActivityEvent) -> Unit = {},
) : ListAdapter<ActivityRow, RecyclerView.ViewHolder>(DiffCallback) {

    // the signed in user, whose own events say "You". rows are bound again when it arrives or changes
    var selfUserId: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    private class DayHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvHeader: TextView = itemView.findViewById(R.id.tvActivityHeader)
    }

    private class EventHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvInitial: TextView = itemView.findViewById(R.id.tv_avatar_initial)
        val ivPicture: ShapeableImageView = itemView.findViewById(R.id.iv_avatar_picture)
        val tvText: TextView = itemView.findViewById(R.id.tv_event_text)
        val tvMeta: TextView = itemView.findViewById(R.id.tv_event_meta)
        val ivIcon: ImageView = itemView.findViewById(R.id.iv_event_icon)
    }

    // diffutil callback: a day is the same row for the same date, an event for the same id
    private object DiffCallback : DiffUtil.ItemCallback<ActivityRow>() {
        override fun areItemsTheSame(oldItem: ActivityRow, newItem: ActivityRow): Boolean = when {
            oldItem is ActivityRow.Day && newItem is ActivityRow.Day -> oldItem.date == newItem.date
            oldItem is ActivityRow.Event && newItem is ActivityRow.Event -> oldItem.event.eventId == newItem.event.eventId
            else -> false
        }

        override fun areContentsTheSame(oldItem: ActivityRow, newItem: ActivityRow): Boolean = oldItem == newItem
    }

    //------------------------------
    // a day header or an event, by the row at that position
    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ActivityRow.Day -> TYPE_DAY
        is ActivityRow.Event -> TYPE_EVENT
    }

    //------------------------------
    // inflates the layout for the kind of row
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DAY) {
            DayHolder(inflater.inflate(R.layout.item_activity_header, parent, false))
        } else {
            EventHolder(inflater.inflate(R.layout.item_activity_event, parent, false))
        }
    }

    //------------------------------
    // fills a day header or an event
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val context = holder.itemView.context
        when (val row = getItem(position)) {
            is ActivityRow.Day -> (holder as DayHolder).tvHeader.text = context.activityDayLabel(row.date)
            is ActivityRow.Event -> {
                holder as EventHolder
                val event = row.event

                // this avatar is the picture the actor picked, or else their initial on a colour of their own
                val picture = profilePictureFor(event.actorPictureId)
                holder.tvInitial.text = initialsFor(event.actorName)
                holder.tvInitial.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, avatarColorFor(event.actorId)))
                if (picture != null) holder.ivPicture.setImageResource(picture.drawable) else holder.ivPicture.setImageDrawable(null)
                holder.ivPicture.setVisible(picture != null)

                holder.itemView.setOnClickListener { onEventClick(event) }
                holder.tvText.text = context.activityText(event, selfUserId)
                holder.tvMeta.text = context.activityMeta(event)
                holder.ivIcon.setImageResource(activityIconFor(event.type))
                ImageViewCompat.setImageTintList(
                    holder.ivIcon,
                    ColorStateList.valueOf(ContextCompat.getColor(context, activityIconTint(event.type))),
                )
            }
        }
    }

    private companion object {
        const val TYPE_DAY = 0
        const val TYPE_EVENT = 1
    }
}

// the one row at the top of the screen's list: the title and the card that opens the leaderboard
class ActivityIntroAdapter(private val onViewStandings: () -> Unit) : RecyclerView.Adapter<ActivityIntroAdapter.Holder>() {

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_activity_intro, parent, false)
        view.findViewById<View>(R.id.btn_view_standings).setOnClickListener { onViewStandings() }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit
}

// the row at the bottom of the screen's list that stands in for the feed when it has nothing to
// show yet (loading, empty or failed to load), and shows a spinner while an older page loads
class ActivityStatusAdapter(private val onRetry: () -> Unit) : RecyclerView.Adapter<ActivityStatusAdapter.Holder>() {

    enum class Status { NONE, LOADING, LOADING_MORE, EMPTY, ERROR }

    // what to say when the status is ERROR
    @StringRes
    var errorText: Int = R.string.activity_error_load
        set(value) {
            if (field == value) return
            field = value
            if (status == Status.ERROR) notifyItemChanged(0)
        }

    var status: Status = Status.NONE
        set(value) {
            if (field == value) return
            val hadRow = field != Status.NONE
            field = value
            when {
                !hadRow -> notifyItemInserted(0)
                value == Status.NONE -> notifyItemRemoved(0)
                else -> notifyItemChanged(0)
            }
        }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val progress: ProgressBar = itemView.findViewById(R.id.progress_status)
        val card: View = itemView.findViewById(R.id.card_status)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_status_title)
        val tvBody: TextView = itemView.findViewById(R.id.tv_status_body)
        val btnRetry: MaterialButton = itemView.findViewById(R.id.btn_status_retry)
    }

    override fun getItemCount(): Int = if (status == Status.NONE) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_activity_status, parent, false)
        return Holder(view).also { holder -> holder.btnRetry.setOnClickListener { onRetry() } }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.progress.setVisible(status == Status.LOADING || status == Status.LOADING_MORE)
        holder.card.setVisible(status == Status.EMPTY || status == Status.ERROR)
        holder.btnRetry.setVisible(status == Status.ERROR)
        if (status == Status.ERROR) {
            holder.tvTitle.setText(R.string.activity_error_title)
            holder.tvBody.setText(errorText)
        } else {
            holder.tvTitle.setText(R.string.activity_empty_title)
            holder.tvBody.setText(R.string.activity_empty_body)
        }
    }
}
//------------------------------EOF------------------------------\
