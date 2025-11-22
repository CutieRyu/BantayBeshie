package com.example.bantaybeshie.adapters

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.bantaybeshie.R
import java.io.File

class SnapshotAdapter(
    private val files: List<File>,
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<SnapshotAdapter.ViewHolder>() {

    class ViewHolder(item: View) : RecyclerView.ViewHolder(item) {
        val img: ImageView = item.findViewById(R.id.imgThumb)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_snapshot, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount(): Int = files.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        holder.img.setImageBitmap(bmp)

        holder.itemView.setOnClickListener {
            onClick(file)
        }
    }
}
