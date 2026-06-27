package com.urik.keyboard.settings.userdictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.data.UserDictionaryRepository
import com.urik.keyboard.data.database.UserDictionaryEntry
import com.urik.keyboard.data.database.UserDictionaryKind
import com.urik.keyboard.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One displayed user-dictionary row (a word, a shortcut→expansion, or a Japanese reading→kanji). */
data class UserDictionaryRow(
    val id: Long,
    val languageTag: String,
    val kind: UserDictionaryKind,
    val matchKey: String,
    val value: String,
    val frequency: Int
)

/**
 * Backs the User Dictionary editor: lists every entry (all languages) and adds / edits / deletes them
 * through the shared [UserDictionaryRepository], so changes are reflected on the keyboard immediately (the
 * repository refreshes its per-language cache on every write).
 */
@HiltViewModel
class UserDictionaryViewModel
@Inject
constructor(
    private val repository: UserDictionaryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    data class UiState(
        val entries: List<UserDictionaryRow> = emptyList(),
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadEntries()
    }

    fun loadEntries() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val rows = try {
                repository.exportAll()
                    .map { it.toRow() }
                    .sortedWith(compareBy({ it.languageTag }, { it.kind.ordinal }, { it.value.lowercase() }))
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.value = UiState(entries = rows, isLoading = false)
        }
    }

    fun saveNew(languageTag: String, kind: UserDictionaryKind, matchKey: String, value: String) {
        viewModelScope.launch {
            repository.add(languageTag, kind, matchKey, value)
            loadEntries()
        }
    }

    fun saveEdit(id: Long, languageTag: String, matchKey: String, value: String) {
        viewModelScope.launch {
            repository.updateEntry(id, languageTag, matchKey, value)
            loadEntries()
        }
    }

    fun delete(row: UserDictionaryRow) {
        viewModelScope.launch {
            repository.deleteById(row.id, row.languageTag)
            loadEntries()
        }
    }

    /** Active languages, for the add-dialog's language picker (falls back to English). */
    suspend fun availableLanguages(): List<String> =
        try {
            settingsRepository.settings.first().activeLanguages.ifEmpty { listOf("en") }
        } catch (_: Exception) {
            listOf("en")
        }

    /** The language a new entry defaults to — the one currently being typed. */
    suspend fun defaultLanguage(): String =
        try {
            settingsRepository.getCurrentLayoutLanguage() ?: availableLanguages().firstOrNull() ?: "en"
        } catch (_: Exception) {
            "en"
        }

    private fun UserDictionaryEntry.toRow(): UserDictionaryRow =
        UserDictionaryRow(
            id = id,
            languageTag = languageTag,
            kind = UserDictionaryKind.fromTag(kind) ?: UserDictionaryKind.WORD,
            matchKey = matchKey,
            value = value,
            frequency = frequency
        )
}
