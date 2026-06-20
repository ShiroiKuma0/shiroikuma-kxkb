package com.urik.keyboard.settings.japanesedictionary

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
import com.urik.keyboard.settings.SettingsEventHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Japanese user-dictionary editor (BUG A): lists every learned/registered reading→surface pair with a
 * per-row delete, so a bad entry (e.g. 白い熊's polluted しろい→しろいはな) can be removed. Modeled on the
 * learned-words editor.
 */
@AndroidEntryPoint
class JapaneseDictionaryFragment : Fragment() {
    private lateinit var viewModel: JapaneseDictionaryViewModel
    private lateinit var eventHandler: SettingsEventHandler
    private lateinit var adapter: JapaneseDictionaryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[JapaneseDictionaryViewModel::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_japanese_dictionary, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        eventHandler = SettingsEventHandler(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.japanese_dictionary_list)
        val emptyView = view.findViewById<TextView>(R.id.japanese_dictionary_empty)
        val loadingView = view.findViewById<ProgressBar>(R.id.japanese_dictionary_loading)

        adapter = JapaneseDictionaryAdapter { entry -> viewModel.deleteEntry(entry) }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
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

                launch {
                    viewModel.events.collect { event -> eventHandler.handle(event) }
                }
            }
        }
    }

    // Reload after returning to this screen so an entry registered/used in the meantime is reflected.
    override fun onResume() {
        super.onResume()
        viewModel.loadEntries()
    }
}
