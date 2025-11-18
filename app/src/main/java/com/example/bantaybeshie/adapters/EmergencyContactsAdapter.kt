package com.example.bantaybeshie.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.bantaybeshie.databinding.ItemContactBinding
import com.example.bantaybeshie.model.ContactEntity

class EmergencyContactsAdapter(
    private val onDelete: (ContactEntity) -> Unit
) : ListAdapter<ContactEntity, EmergencyContactsAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ContactEntity) {
            binding.contactName.text = item.name
            binding.contactNumber.text = item.number

            binding.deleteBtn.setOnClickListener {
                onDelete(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<ContactEntity>() {
        override fun areItemsTheSame(oldItem: ContactEntity, newItem: ContactEntity) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ContactEntity, newItem: ContactEntity) =
            oldItem == newItem
    }
}

