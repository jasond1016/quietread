package com.quietread.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.quietread.app.epub.EpubExtractor
import com.quietread.app.epub.EpubPackage
import com.quietread.app.epub.EpubPackageParser
import com.quietread.app.epub.InvalidEpubException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

sealed interface ImportOutcome {
    data class Imported(val book: BookRecord) : ImportOutcome
    data class AlreadyExists(val book: BookRecord) : ImportOutcome
}

class BookRepository(private val context: Context) {
    private val database = BookDatabase(context)
    private val mutableBooks = MutableStateFlow(database.allBooks())
    private val booksRoot = File(context.filesDir, "books").apply { mkdirs() }

    val books: StateFlow<List<BookRecord>> = mutableBooks

    suspend fun import(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("quietread-import-", ".epub", context.cacheDir)
        try {
            val hash = copyAndHash(uri, tempFile)
            database.bookByHash(hash)?.let { existing ->
                return@withContext ImportOutcome.AlreadyExists(existing)
            }

            val id = UUID.randomUUID().toString()
            val bookDir = File(booksRoot, id)
            val contentDir = File(bookDir, "content")
            val epubFile = File(bookDir, "book.epub")
            bookDir.mkdirs()
            if (!tempFile.renameTo(epubFile)) {
                tempFile.copyTo(epubFile, overwrite = true)
            }

            try {
                EpubExtractor.extract(epubFile, contentDir)
                val parsed = EpubPackageParser.parse(contentDir)
                val now = System.currentTimeMillis()
                val fallbackTitle = displayName(uri).substringBeforeLast('.').ifBlank { "未命名书籍" }
                val book = BookRecord(
                    id = id,
                    sha256 = hash,
                    title = parsed.title.ifBlank { fallbackTitle },
                    author = parsed.author,
                    coverPath = parsed.coverFile?.absolutePath,
                    epubPath = epubFile.absolutePath,
                    contentRoot = contentDir.absolutePath,
                    packagePath = parsed.packageRelativePath,
                    lastSpineIndex = 0,
                    lastSpineProgress = 0f,
                    overallProgress = 0f,
                    importedAt = now,
                    lastOpenedAt = now,
                )
                database.insert(book)
                refresh()
                ImportOutcome.Imported(book)
            } catch (error: Exception) {
                bookDir.deleteRecursively()
                throw error
            }
        } catch (error: InvalidEpubException) {
            throw error
        } catch (error: Exception) {
            throw InvalidEpubException("无法导入这本 EPUB", error)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun loadPackage(book: BookRecord): EpubPackage = withContext(Dispatchers.IO) {
        EpubPackageParser.parse(File(book.contentRoot), book.packagePath)
    }

    suspend fun savePosition(bookId: String, position: ReadingPosition) = withContext(Dispatchers.IO) {
        database.updatePosition(bookId, position, System.currentTimeMillis())
        refresh()
    }

    suspend fun delete(book: BookRecord) = withContext(Dispatchers.IO) {
        val root = booksRoot.canonicalFile
        val bookDirectory = File(book.epubPath).parentFile?.canonicalFile
        database.delete(book.id)
        if (bookDirectory != null && bookDirectory.parentFile == root) {
            bookDirectory.deleteRecursively()
        }
        refresh()
    }

    private fun copyAndHash(uri: Uri, output: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw InvalidEpubException("无法读取所选文件")
        input.buffered().use { source ->
            FileOutputStream(output).buffered().use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    target.write(buffer, 0, count)
                }
            }
        }
        if (output.length() == 0L) throw InvalidEpubException("所选文件为空")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun displayName(uri: Uri): String {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
    }

    private fun refresh() {
        mutableBooks.value = database.allBooks()
    }
}
