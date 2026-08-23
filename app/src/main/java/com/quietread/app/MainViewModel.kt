package com.quietread.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietread.app.data.BookRecord
import com.quietread.app.data.BookRepository
import com.quietread.app.data.ImportOutcome
import com.quietread.app.data.ReadingPosition
import com.quietread.app.epub.EpubPackage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface AppScreen {
    data object Library : AppScreen
    data class Reader(val book: BookRecord, val epub: EpubPackage) : AppScreen
}

data class MainUiState(
    val books: List<BookRecord> = emptyList(),
    val screen: AppScreen = AppScreen.Library,
    val busy: Boolean = false,
    val message: String? = null,
)

class MainViewModel(private val repository: BookRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    private var saveJob: Job? = null
    private var pendingPosition: Pair<String, ReadingPosition>? = null

    init {
        viewModelScope.launch {
            repository.books.collectLatest { books ->
                mutableState.value = mutableState.value.copy(books = books)
            }
        }
    }

    fun importBook(uri: Uri) {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, message = null)
            runCatching { repository.import(uri) }
                .onSuccess { outcome ->
                    val book = when (outcome) {
                        is ImportOutcome.Imported -> outcome.book
                        is ImportOutcome.AlreadyExists -> outcome.book
                    }
                    val message = if (outcome is ImportOutcome.AlreadyExists) "这本书已在书架中" else null
                    mutableState.value = mutableState.value.copy(busy = false, message = message)
                    openBook(book)
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        busy = false,
                        message = error.message ?: "导入失败",
                    )
                }
        }
    }

    fun openBook(book: BookRecord) {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, message = null)
            runCatching { repository.loadPackage(book) }
                .onSuccess { epub ->
                    mutableState.value = mutableState.value.copy(
                        busy = false,
                        screen = AppScreen.Reader(book, epub),
                    )
                    savePosition(
                        book.id,
                        ReadingPosition(book.lastSpineIndex, book.lastSpineProgress, book.overallProgress),
                        immediately = true,
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        busy = false,
                        message = error.message ?: "无法打开这本书",
                    )
                }
        }
    }

    fun updatePosition(bookId: String, position: ReadingPosition) {
        savePosition(bookId, position, immediately = false)
    }

    fun closeReader() {
        flushPosition()
        mutableState.value = mutableState.value.copy(screen = AppScreen.Library)
    }

    fun deleteBook(book: BookRecord) {
        viewModelScope.launch {
            runCatching { repository.delete(book) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(message = error.message ?: "删除失败")
                }
        }
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun savePosition(bookId: String, position: ReadingPosition, immediately: Boolean) {
        pendingPosition = bookId to position
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!immediately) delay(350)
            val pending = pendingPosition ?: return@launch
            pendingPosition = null
            repository.savePosition(pending.first, pending.second)
        }
    }

    private fun flushPosition() {
        saveJob?.cancel()
        val pending = pendingPosition ?: return
        pendingPosition = null
        viewModelScope.launch { repository.savePosition(pending.first, pending.second) }
    }

    override fun onCleared() {
        flushPosition()
        super.onCleared()
    }
}
