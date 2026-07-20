package com.urik.keyboard.settings.learnedwords

import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.urik.keyboard.R
import com.urik.keyboard.settings.SettingsEventHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LearnedWordsFragment : Fragment() {
    private lateinit var viewModel: LearnedWordsViewModel
    private lateinit var eventHandler: SettingsEventHandler
    private lateinit var adapter: LearnedWordsAdapter
    private var renderedTabTags: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[LearnedWordsViewModel::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_learned_words, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        eventHandler = SettingsEventHandler(requireContext())

        val tabLayout = view.findViewById<TabLayout>(R.id.learned_words_tabs)
        val recyclerView = view.findViewById<RecyclerView>(R.id.learned_words_list)
        val emptyView = view.findViewById<TextView>(R.id.learned_words_empty)
        val loadingView = view.findViewById<ProgressBar>(R.id.learned_words_loading)

        adapter = LearnedWordsAdapter { row -> viewModel.deleteWord(row) }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // A horizontal fling anywhere on the list area changes the language tab (finger left = next
        // tab, finger right = previous). The item-touch-listener only OBSERVES — taps and vertical
        // scrolling stay untouched; the container listener covers the empty state.
        val flingDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (kotlin.math.abs(vx) > kotlin.math.abs(vy) * 1.5f && kotlin.math.abs(vx) > 800f) {
                        viewModel.selectAdjacentLanguage(if (vx < 0) 1 else -1)
                        return true
                    }
                    return false
                }
            }
        )
        recyclerView.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    flingDetector.onTouchEvent(e)
                    return false
                }
            }
        )
        view.findViewById<View>(R.id.learned_words_swipe_area)?.setOnTouchListener { v, e ->
            flingDetector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    (tab.tag as? String)?.let { viewModel.selectLanguage(it) }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {}

                override fun onTabReselected(tab: TabLayout.Tab) {}
            }
        )

        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_learned_words, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    if (menuItem.itemId == R.id.action_delete_all) {
                        showDeleteAllConfirmation()
                        return true
                    }
                    return false
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        loadingView.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                        if (!state.isLoading) {
                            renderTabs(tabLayout, state)

                            if (state.rows.isEmpty()) {
                                recyclerView.visibility = View.GONE
                                emptyView.visibility = View.VISIBLE
                            } else {
                                recyclerView.visibility = View.VISIBLE
                                emptyView.visibility = View.GONE
                            }
                            adapter.submitList(state.rows)
                        }
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        eventHandler.handle(event)
                    }
                }
            }
        }
    }

    private fun renderTabs(tabLayout: TabLayout, state: LearnedWordsUiState) {
        val tags = state.tabs.map { it.tag }
        if (tags != renderedTabTags) {
            tabLayout.removeAllTabs()
            state.tabs.forEach { tab ->
                tabLayout.addTab(tabLayout.newTab().setText(tab.displayName).setTag(tab.tag), false)
            }
            renderedTabTags = tags
        }
        val selectedIndex = tags.indexOf(state.selectedTag)
        if (selectedIndex >= 0 && tabLayout.selectedTabPosition != selectedIndex) {
            tabLayout.getTabAt(selectedIndex)?.select()
        }
    }

    private fun showDeleteAllConfirmation() {
        AlertDialog
            .Builder(requireContext())
            .setTitle(resources.getString(R.string.learned_words_delete_all))
            .setMessage(resources.getString(R.string.learned_words_delete_all_confirm))
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.deleteAllWords() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
