package com.example.bantaybeshie.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.example.bantaybeshie.R
import com.example.bantaybeshie.model.ActivityLogEntry
import com.example.bantaybeshie.model.LogType

class ActivityLogAdapter(
    private val logs: List<ActivityLogEntry>
) : RecyclerView.Adapter<ActivityLogAdapter.LogViewHolder>() {

    inner class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.logIcon)
        val title: TextView = view.findViewById(R.id.logTitle)
        val time: TextView = view.findViewById(R.id.logTime)
        val card: CardView = view as CardView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(v)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val item = logs[position]

        holder.title.text = item.title
        holder.time.text = item.timestamp

        when (item.type) {


            // RED — SOS
            LogType.SOS -> {
                holder.card.setCardBackgroundColor(Color.parseColor("#FF3B30"))
                holder.icon.setImageResource(R.drawable.ic_sos)
            }

            LogType.OBJECT_DETECTED -> {
                holder.card.setCardBackgroundColor(Color.parseColor("#FFA500"))
                holder.icon.setImageResource(R.drawable.ic_detect)
            }

            // BLUE – System/user actions
            LogType.USER_ACTION,
            LogType.MODE_CHANGE -> {
                holder.card.setCardBackgroundColor(Color.parseColor("#3A7BD5"))
                holder.icon.setImageResource(R.drawable.ic_person)
            }
        }
    }

    override fun getItemCount(): Int = logs.size
}
