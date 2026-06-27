package com.urik.keyboard.settings.userdictionary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.urik.keyboard.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The User Dictionary editor: every entry (words, shortcuts, Japanese reading→kanji) across all languages,
 * with add (the bottom button), edit (tap a row) and delete (the trailing button). Entries are offered first
 * as you type in the matching language — see the suggestion path's user-dictionary query.
 */
@AndroidEntryPoint
class UserDictionaryFragment : Fragment() {
    private lateinit var viewModel: UserDictionaryViewModel
    private lateinit var adapter: UserDictionaryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[UserDictionaryViewModel::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_user_dictionary, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.user_dictionary_list)
        val emptyView = view.findViewById<TextView>(R.id.user_dictionary_empty)
        val loadingView = view.findViewById<ProgressBar>(R.id.user_dictionary_loading)
        val addButton = view.findViewById<View>(R.id.user_dictionary_add_button)

        adapter = UserDictionaryAdapter(
            onRowClick = { row -> showEntryDialog(row) },
            onDeleteClick = { row -> viewModel.delete(row) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        addButton.setOnClickListener { showEntryDialog(null) }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    loadingView.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    if (!state.isLoading) {
                        val empty = state.entries.isEmpty()
                        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
                        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
                        adapter.submitList(state.entries)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadEntries()
    }

    private fun showEntryDialog(existing: UserDictionaryRow?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val languages = viewModel.availableLanguages()
            val default = existing?.languageTag ?: viewModel.defaultLanguage()
            UserDictionaryEntryDialog.show(
                context = requireContext(),
                languages = languages,
                defaultLanguage = default,
                existing = existing
            ) { id, languageTag, kind, matchKey, value ->
                if (id == null) {
                    viewModel.saveNew(languageTag, kind, matchKey, value)
                } else {
                    viewModel.saveEdit(id, languageTag, matchKey, value)
                }
            }
        }
    }
}
