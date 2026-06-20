package com.urik.keyboard.settings.japanesedictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.data.database.UserKanjiFrequencyDao
import com.urik.keyboard.service.ScriptConverterRegistry
import com.urik.keyboard.settings.SettingsEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One reading→surface entry of the Japanese user dictionary, shown in the editor. */
data class UserKanjiEntry(val reading: String, val surface: String)

data class JapaneseDictionaryUiState(
    val entries: List<UserKanjiEntry> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * Backs the Japanese user-dictionary settings editor (BUG A). Lists every persisted reading→surface pair
 * (learned selections + ＋登録 registrations) and lets the user delete a bad one — e.g. 白い熊's polluted
 * しろい→しろいはな. Deletion goes through the shared converter so the in-memory boost map is cleared too, not
 * just the Room row, so the entry stops being offered immediately. All DB work is off the main thread.
 */
@HiltViewModel
class JapaneseDictionaryViewModel
@Inject
constructor(
    private val dao: UserKanjiFrequencyDao,
    private val scriptConverterRegistry: ScriptConverterRegistry
) : ViewModel() {
    private val _uiState = MutableStateFlow(JapaneseDictionaryUiState())
    val uiState: StateFlow<JapaneseDictionaryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        loadEntries()
    }

    fun loadEntries() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val entries = try {
                withContext(Dispatchers.IO) {
                    dao.getAll()
                        .map { UserKanjiEntry(it.reading, it.surface) }
                        .sortedWith(compareBy({ it.reading }, { it.surface }))
                }
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.value = JapaneseDictionaryUiState(entries = entries, isLoading = false)
        }
    }

    fun deleteEntry(entry: UserKanjiEntry) {
        viewModelScope.launch {
            try {
                // Route through the converter (which clears the live boost map AND deletes the Room row) so the
                // surface stops being offered without needing a keyboard restart. Falls back to a direct DAO
                // delete if the Japanese converter isn't registered for some reason.
                val converter = scriptConverterRegistry.forLanguage(JAPANESE_LANGUAGE)
                if (converter != null) {
                    converter.removeEntry(entry.reading, entry.surface)
                } else {
                    withContext(Dispatchers.IO) { dao.delete(entry.reading, entry.surface) }
                }
                _events.emit(SettingsEvent.Success.WordDeleted)
                loadEntries()
            } catch (_: Exception) {
                _events.emit(SettingsEvent.Error.DeleteWordFailed)
            }
        }
    }

    private companion object {
        const val JAPANESE_LANGUAGE = "ja"
    }
}
