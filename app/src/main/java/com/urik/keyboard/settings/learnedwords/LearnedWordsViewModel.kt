package com.urik.keyboard.settings.learnedwords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urik.keyboard.data.UserDictionaryRepository
import com.urik.keyboard.service.WordLearningEngine
import com.urik.keyboard.settings.SettingsEvent
import com.urik.keyboard.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One language tab of the Learned-words page. */
data class LanguageTab(val tag: String, val displayName: String)

/**
 * One deletable word row. [userDictId] non-null = an explicit user-dictionary entry (added/registered,
 * e.g. the Japanese ＋登録 surface); null = an implicitly learned word.
 */
data class LearnedWordRow(val word: String, val languageTag: String, val userDictId: Long? = null)

data class LearnedWordsUiState(
    val tabs: List<LanguageTab> = emptyList(),
    val selectedTag: String? = null,
    val rows: List<LearnedWordRow> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class LearnedWordsViewModel
@Inject
constructor(
    private val wordLearningEngine: WordLearningEngine,
    private val userDictionaryRepository: UserDictionaryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(LearnedWordsUiState())
    val uiState: StateFlow<LearnedWordsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    private var rowsByLanguage: Map<String, List<LearnedWordRow>> = emptyMap()

    init {
        loadWords()
    }

    fun loadWords() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val settings = settingsRepository.settings.first()
            val learned = wordLearningEngine.getAllLearnedWordsByLanguage().getOrElse { emptyMap() }
            val userDict = try {
                userDictionaryRepository.exportAll()
            } catch (e: Exception) {
                emptyList()
            }

            // Merge the implicitly learned words with the explicit user-dictionary entries (registered
            // words like 白い熊 live only there); an identical word in both stores shows once, as the
            // explicit entry. GNU is a no-prediction keyboard — nothing of its is shown.
            val merged = mutableMapOf<String, MutableMap<String, LearnedWordRow>>()
            learned.forEach { (lang, words) ->
                val byWord = merged.getOrPut(lang) { mutableMapOf() }
                words.forEach { byWord.putIfAbsent(it, LearnedWordRow(it, lang)) }
            }
            userDict.forEach { entry ->
                val byWord = merged.getOrPut(entry.languageTag) { mutableMapOf() }
                byWord[entry.value] = LearnedWordRow(entry.value, entry.languageTag, userDictId = entry.id)
            }
            rowsByLanguage = merged.mapValues { (lang, byWord) ->
                val collator = Collator.getInstance(Locale.forLanguageTag(lang))
                byWord.values.sortedWith(compareBy(collator) { it.word })
            }

            val active = settings.activeLanguages
            val tags = (active + rowsByLanguage.keys.filterNot { it in active }.sorted())
                .distinct()
                .filterNot { it == GNU_TAG }
            val tabs = tags.map { LanguageTab(it, languageDisplayName(it)) }
            val selected = _uiState.value.selectedTag?.takeIf { it in tags }
                ?: settings.primaryLayoutLanguage.takeIf { it in tags }
                ?: tags.firstOrNull()
            _uiState.value =
                LearnedWordsUiState(tabs = tabs, selectedTag = selected, rows = rowsFor(selected), isLoading = false)
        }
    }

    fun selectLanguage(tag: String) {
        val state = _uiState.value
        if (state.selectedTag == tag) return
        _uiState.value = state.copy(selectedTag = tag, rows = rowsFor(tag))
    }

    /** Move the selected tab by [delta] (swipe left/right on the list), clamped to the tab range. */
    fun selectAdjacentLanguage(delta: Int) {
        val state = _uiState.value
        if (state.tabs.isEmpty()) return
        val current = state.tabs.indexOfFirst { it.tag == state.selectedTag }.coerceAtLeast(0)
        val next = (current + delta).coerceIn(0, state.tabs.size - 1)
        selectLanguage(state.tabs[next].tag)
    }

    private fun rowsFor(tag: String?): List<LearnedWordRow> = tag?.let { rowsByLanguage[it] } ?: emptyList()

    fun deleteWord(row: LearnedWordRow) {
        viewModelScope.launch {
            val result = if (row.userDictId != null) {
                try {
                    userDictionaryRepository.deleteById(row.userDictId, row.languageTag)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            } else {
                wordLearningEngine.deleteWordForLanguage(row.word, row.languageTag)
            }
            result
                .onSuccess {
                    _events.emit(SettingsEvent.Success.WordDeleted)
                    loadWords()
                }
                .onFailure {
                    _events.emit(SettingsEvent.Error.DeleteWordFailed)
                }
        }
    }

    fun deleteAllWords() {
        viewModelScope.launch {
            wordLearningEngine.deleteAllWordsCompletely()
                .onSuccess {
                    _events.emit(SettingsEvent.Success.AllWordsDeleted)
                    loadWords()
                }
                .onFailure {
                    _events.emit(SettingsEvent.Error.DeleteAllWordsFailed)
                }
        }
    }

    /** The language's own native name (house convention — the space bar shows it the same way). */
    private fun languageDisplayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val native = locale.getDisplayLanguage(locale).ifEmpty { tag }
        return native.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    private companion object {
        const val GNU_TAG = "gnu"
    }
}
