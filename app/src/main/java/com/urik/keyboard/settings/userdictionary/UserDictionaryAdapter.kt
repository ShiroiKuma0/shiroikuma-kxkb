package com.urik.keyboard.settings.userdictionary

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.urik.keyboard.R
import com.urik.keyboard.data.database.UserDictionaryKind
import com.urik.keyboard.utils.LanguageDisplayNames

/**
 * Renders the user-dictionary list. The primary line is the inserted [UserDictionaryRow.value]; the
 * secondary line shows the trigger (for shortcuts / Japanese readings), the language, and the use count.
 * Tapping a row edits it; the trailing button deletes it.
 */
class UserDictionaryAdapter(
    private val onRowClick: (UserDictionaryRow) -> Unit,
    private val onDeleteClick: (UserDictionaryRow) -> Unit
) : ListAdapter<UserDictionaryRow, UserDictionaryAdapter.EntryViewHolder>(EntryDiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_dictionary, parent, false)
        return EntryViewHolder(view, onRowClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class EntryViewHolder(
        itemView: View,
        private val onRowClick: (UserDictionaryRow) -> Unit,
        private val onDeleteClick: (UserDictionaryRow) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val primary: TextView = itemView.findViewById(R.id.user_dict_primary)
        private val secondary: TextView = itemView.findViewById(R.id.user_dict_secondary)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.user_dict_delete)

        fun bind(row: UserDictionaryRow) {
            val ctx = itemView.context
            primary.text = row.value
            val freq = ctx.getString(R.string.user_dictionary_freq, row.frequency)
            val lang = LanguageDisplayNames.nativeName(row.languageTag)
            secondary.text =
                if (row.kind == UserDictionaryKind.WORD) "$lang · $freq" else "${row.matchKey} · $lang · $freq"
            itemView.setOnClickListener { onRowClick(row) }
            deleteButton.setOnClickListener { onDeleteClick(row) }
        }
    }

    private object EntryDiffCallback : DiffUtil.ItemCallback<UserDictionaryRow>() {
        override fun areItemsTheSame(oldItem: UserDictionaryRow, newItem: UserDictionaryRow): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: UserDictionaryRow, newItem: UserDictionaryRow): Boolean =
            oldItem == newItem
    }
}
