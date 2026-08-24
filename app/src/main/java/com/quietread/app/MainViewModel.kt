package com.quietread.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietread.app.data.BookRecord
import com.quietread.app.data.BookAnnotation
import com.quietread.app.data.ReadingSelection
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
import android.os.SystemClock

sealed interface AppScreen {
    data object Library : AppScreen
    data class Reader(val book: BookRecord, val epub: EpubPackage) : AppScreen
}

data class MainUiState(
    val books: List<BookRecord> = emptyList(),
    val annotations: List<BookAnnotation> = emptyList(),
    val screen: AppScreen = AppScreen.Library,
    val busy: Boolean = false,
    val message: String? = null,
)

class MainViewModel(private val repository: BookRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    private var saveJob: Job? = null
    private var pendingPosition: Pair<String, ReadingPosition>? = null
    private val readingSession = ReadingSessionTracker()

    init {
        viewModelScope.launch {
            repository.books.collectLatest { books ->
                val current = mutableState.value.screen
                val updatedScreen = if (current is AppScreen.Reader) {
                    books.firstOrNull { it.id == current.book.id }?.let { current.copy(book = it) } ?: current
                } else current
                mutableState.value = mutableState.value.copy(books = books, screen = updatedScreen)
            }
        }
        viewModelScope.launch {
            repository.annotations.collectLatest { annotations ->
                mutableState.value = mutableState.value.copy(annotations = annotations)
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
                    readingSession.start(book.id)
                    savePosition(
                        book.id,
                        ReadingPosition(
                            spineIndex = book.lastSpineIndex,
                            spineProgress = book.lastSpineProgress,
                            locator = book.lastLocator,
                            overallProgress = book.overallProgress,
                        ),
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
        accrueReadingTime()
        readingSession.interact()
        savePosition(bookId, position, immediately = false)
    }

    fun closeReader() {
        pauseReadingSession()
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

    fun toggleBookmark(bookId: String, position: ReadingPosition) {
        viewModelScope.launch {
            runCatching { repository.toggleBookmark(bookId, position) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(message = error.message ?: "无法更新书签")
                }
        }
    }

    fun addHighlight(bookId: String, selection: ReadingSelection) {
        viewModelScope.launch {
            runCatching { repository.addHighlight(bookId, selection) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(message = error.message ?: "无法保存划线")
                }
        }
    }

    fun addThought(bookId: String, selection: ReadingSelection, note: String) {
        viewModelScope.launch {
            runCatching { repository.addThought(bookId, selection, note) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(message = error.message ?: "无法保存想法")
                }
        }
    }

    fun updateThought(annotationId: String, note: String) {
        viewModelScope.launch {
            runCatching { repository.updateThought(annotationId, note) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(message = error.message ?: "无法更新想法")
                }
        }
    }

    fun deleteAnnotation(annotationId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteAnnotation(annotationId) }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(message = error.message ?: "无法删除标记")
                }
        }
    }

    fun exportMarkdown(uri: Uri, book: BookRecord, epub: EpubPackage, annotations: List<BookAnnotation>) {
        viewModelScope.launch {
            runCatching { repository.exportMarkdown(uri, book, epub, annotations) }
                .onSuccess { mutableState.value = mutableState.value.copy(message = "标记已导出") }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(message = error.message ?: "导出失败")
                }
        }
    }

    fun exportAnnotationBackup(uri: Uri, book: BookRecord, annotations: List<BookAnnotation>) {
        viewModelScope.launch {
            runCatching { repository.exportAnnotationBackup(uri, book, annotations) }
                .onSuccess { mutableState.value = mutableState.value.copy(message = "标记备份已保存") }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(message = error.message ?: "备份失败")
                }
        }
    }

    fun restoreAnnotationBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.restoreAnnotationBackup(uri) }
                .onSuccess { count -> mutableState.value = mutableState.value.copy(message = "已恢复 $count 条标记") }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(message = error.message ?: "恢复失败")
                }
        }
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun savePosition(bookId: String, position: ReadingPosition, immediately: Boolean) {
        pendingPosition = bookId to position
        saveJob?.cancel()
        if (immediately) {
            enqueuePendingPosition()
            return
        }
        saveJob = viewModelScope.launch {
            delay(350)
            enqueuePendingPosition()
        }
    }

    fun flushPosition() {
        saveJob?.cancel()
        enqueuePendingPosition()
    }

    fun resumeReadingSession() {
        val screen = mutableState.value.screen as? AppScreen.Reader ?: return
        readingSession.start(screen.book.id)
    }

    fun pauseReadingSession() {
        accrueReadingTime()
        readingSession.pause()
    }

    private fun accrueReadingTime() {
        val elapsed = readingSession.accrue()
        if (elapsed != null) repository.enqueueReadingTime(elapsed.bookId, elapsed.elapsedMs)
    }

    private fun enqueuePendingPosition() {
        val pending = pendingPosition ?: return
        pendingPosition = null
        repository.enqueuePosition(pending.first, pending.second)
    }

    override fun onCleared() {
        pauseReadingSession()
        flushPosition()
        super.onCleared()
    }
}

internal data class ReadingElapsed(val bookId: String, val elapsedMs: Long)

internal class ReadingSessionTracker(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val idleTimeoutMs: Long = 90_000L,
) {
    private var bookId: String? = null
    private var checkpointMs = 0L
    private var activeUntilMs = 0L

    fun start(newBookId: String) {
        val current = now()
        bookId = newBookId
        checkpointMs = current
        activeUntilMs = current + idleTimeoutMs
    }

    fun interact() {
        if (bookId == null) return
        activeUntilMs = now() + idleTimeoutMs
    }

    fun accrue(): ReadingElapsed? {
        val currentBook = bookId ?: return null
        val current = now()
        val end = minOf(current, activeUntilMs)
        val elapsed = (end - checkpointMs).coerceAtLeast(0L)
        checkpointMs = current
        return elapsed.takeIf { it > 0L }?.let { ReadingElapsed(currentBook, it) }
    }

    fun pause() {
        bookId = null
    }
}
