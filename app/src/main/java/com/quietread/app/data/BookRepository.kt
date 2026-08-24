package com.quietread.app.data

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import com.quietread.app.epub.EpubExtractor
import com.quietread.app.epub.EpubPackage
import com.quietread.app.epub.EpubPackageParser
import com.quietread.app.epub.InvalidEpubException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationMutex = Mutex()
    private val positionUpdates = Channel<PositionUpdate>(Channel.UNLIMITED)

    val books: StateFlow<List<BookRecord>> = mutableBooks

    init {
        repositoryScope.launch {
            cleanPendingDeletes()
            for (update in positionUpdates) {
                mutationMutex.withLock {
                    database.updatePosition(update.bookId, update.position, System.currentTimeMillis())
                    refresh()
                }
            }
        }
    }

    suspend fun import(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        val source = sourceMetadata(uri)
        source.size?.let { size ->
            if (size > MAX_EPUB_BYTES) throw InvalidEpubException("EPUB 文件不能超过 256 MB")
            ensureAvailableSpace(size)
        }
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
                val extractionBudget = (allocatableBytes() - MIN_FREE_BYTES)
                    .coerceAtMost(MAX_EXTRACTED_BYTES)
                if (extractionBudget <= 0L) throw InvalidEpubException("设备存储空间不足，无法解压 EPUB")
                EpubExtractor.extract(epubFile, contentDir, extractionBudget)
                val parsed = EpubPackageParser.parse(contentDir)
                val now = System.currentTimeMillis()
                val fallbackTitle = source.displayName.substringBeforeLast('.').ifBlank { "未命名书籍" }
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
                    lastLocator = null,
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

    fun enqueuePosition(bookId: String, position: ReadingPosition) {
        positionUpdates.trySend(PositionUpdate(bookId, position))
    }

    suspend fun delete(book: BookRecord) = withContext(Dispatchers.IO) {
        val stagedDirectory = mutationMutex.withLock {
            val root = booksRoot.canonicalFile
            val bookDirectory = File(book.epubPath).parentFile?.canonicalFile
                ?: throw IOException("书籍文件路径无效")
            if (bookDirectory.parentFile != root) throw IOException("书籍文件路径无效")

            val staged = if (bookDirectory.exists()) {
                val target = File(root, "$PENDING_DELETE_PREFIX${book.id}-${System.nanoTime()}")
                if (!bookDirectory.renameTo(target)) throw IOException("无法移除书籍文件")
                target
            } else null

            try {
                database.delete(book.id)
                refresh()
                staged
            } catch (error: Exception) {
                staged?.renameTo(bookDirectory)
                throw error
            }
        }
        if (stagedDirectory != null && !stagedDirectory.deleteRecursively()) {
            throw IOException("书籍已移出书架，残留文件将在下次启动时清理")
        }
    }

    private fun copyAndHash(uri: Uri, output: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw InvalidEpubException("无法读取所选文件")
        input.buffered().use { source ->
            FileOutputStream(output).buffered().use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_EPUB_BYTES) {
                        throw InvalidEpubException("EPUB 文件不能超过 256 MB")
                    }
                    digest.update(buffer, 0, count)
                    target.write(buffer, 0, count)
                }
            }
        }
        if (output.length() == 0L) throw InvalidEpubException("所选文件为空")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sourceMetadata(uri: Uri): SourceMetadata {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                SourceMetadata(
                    displayName = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                        ?: uri.lastPathSegment.orEmpty(),
                    size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
                )
            } ?: SourceMetadata(uri.lastPathSegment.orEmpty(), null)
    }

    private fun ensureAvailableSpace(sourceBytes: Long) {
        val available = allocatableBytes()
        if (available < sourceBytes + MIN_FREE_BYTES) {
            throw InvalidEpubException("设备存储空间不足")
        }
    }

    private fun allocatableBytes(): Long {
        val storage = context.getSystemService(StorageManager::class.java)
        val storageUuid = storage.getUuidForPath(context.filesDir)
        return storage.getAllocatableBytes(storageUuid)
    }

    private fun cleanPendingDeletes() {
        booksRoot.listFiles { file -> file.name.startsWith(PENDING_DELETE_PREFIX) }
            ?.forEach(File::deleteRecursively)
    }

    private fun refresh() {
        mutableBooks.value = database.allBooks()
    }

    private data class PositionUpdate(val bookId: String, val position: ReadingPosition)
    private data class SourceMetadata(val displayName: String, val size: Long?)

    private companion object {
        const val MAX_EPUB_BYTES = 256L * 1024L * 1024L
        const val MAX_EXTRACTED_BYTES = 512L * 1024L * 1024L
        const val MIN_FREE_BYTES = 64L * 1024L * 1024L
        const val PENDING_DELETE_PREFIX = ".deleting-"
    }
}
