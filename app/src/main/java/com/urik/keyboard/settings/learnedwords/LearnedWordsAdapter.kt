package com.urik.keyboard.settings.learnedwords

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.urik.keyboard.R

class LearnedWordsAdapter(private val onDeleteClick: (LearnedWordRow) -> Unit) :
    ListAdapter<LearnedWordRow, LearnedWordsAdapter.WordViewHolder>(RowDiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_learned_word, parent, false)
        return WordViewHolder(view, onDeleteClick)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class WordViewHolder(itemView: View, private val onDeleteClick: (LearnedWordRow) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val wordText: TextView = itemView.findViewById(R.id.word_text)
        private val userDictMarker: TextView = itemView.findViewById(R.id.user_dict_marker)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_button)

        fun bind(row: LearnedWordRow) {
            wordText.text = row.word
            // The ＋ pill marks an EXPLICIT user-dictionary entry (added/registered — the ＋登録
            // metaphor): deleting it destroys curated data, not just an automatically learned word.
            // INVISIBLE (not GONE) keeps the gutter width constant so every word aligns.
            userDictMarker.visibility = if (row.userDictId != null) View.VISIBLE else View.INVISIBLE
            deleteButton.setOnClickListener { onDeleteClick(row) }
        }
    }

    private object RowDiffCallback : DiffUtil.ItemCallback<LearnedWordRow>() {
        override fun areItemsTheSame(oldItem: LearnedWordRow, newItem: LearnedWordRow): Boolean = oldItem == newItem

        override fun areContentsTheSame(oldItem: LearnedWordRow, newItem: LearnedWordRow): Boolean = oldItem == newItem
    }
}
