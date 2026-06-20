package com.urik.keyboard.settings.japanesedictionary

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.urik.keyboard.R

class JapaneseDictionaryAdapter(private val onDeleteClick: (UserKanjiEntry) -> Unit) :
    ListAdapter<UserKanjiEntry, JapaneseDictionaryAdapter.EntryViewHolder>(EntryDiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_japanese_dictionary, parent, false)
        return EntryViewHolder(view, onDeleteClick)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class EntryViewHolder(itemView: View, private val onDeleteClick: (UserKanjiEntry) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val surfaceText: TextView = itemView.findViewById(R.id.kanji_surface_text)
        private val readingText: TextView = itemView.findViewById(R.id.kanji_reading_text)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_button)

        fun bind(entry: UserKanjiEntry) {
            surfaceText.text = entry.surface
            readingText.text = entry.reading
            deleteButton.setOnClickListener { onDeleteClick(entry) }
        }
    }

    private object EntryDiffCallback : DiffUtil.ItemCallback<UserKanjiEntry>() {
        override fun areItemsTheSame(oldItem: UserKanjiEntry, newItem: UserKanjiEntry): Boolean =
            oldItem.reading == newItem.reading && oldItem.surface == newItem.surface

        override fun areContentsTheSame(oldItem: UserKanjiEntry, newItem: UserKanjiEntry): Boolean =
            oldItem == newItem
    }
}
