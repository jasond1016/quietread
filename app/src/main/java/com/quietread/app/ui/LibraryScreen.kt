package com.quietread.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quietread.app.data.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
fun LibraryScreen(
    books: List<BookRecord>,
    onImport: () -> Unit,
    onOpen: (BookRecord) -> Unit,
    onDelete: (BookRecord) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<BookRecord?>(null) }

    Scaffold(
        floatingActionButton = {
            if (books.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onImport,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text("＋", style = MaterialTheme.typography.headlineSmall)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
                Text("静阅", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("只读书，不喧哗", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
            }
            if (books.isEmpty()) {
                EmptyLibrary(onImport)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(132.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    items(books, key = BookRecord::id) { book ->
                        BookTile(book, onOpen = { onOpen(book) }, onDelete = { pendingDelete = book })
                    }
                }
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除《${book.title}》？") },
            text = { Text("应用内的书籍副本和阅读进度将一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(book)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(width = 96.dp, height = 128.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text("QR", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(20.dp))
            Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("从设备中选择一本 EPUB", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(20.dp))
            Button(onClick = onImport) { Text("导入 EPUB") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: BookRecord, onOpen: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier.combinedClickable(onClick = onOpen, onLongClick = onDelete),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.70f),
            shape = RoundedCornerShape(7.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            BookCover(book)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            book.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        book.author?.let {
            Text(
                it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
            )
        }
        Spacer(Modifier.height(7.dp))
        LinearProgressIndicator(
            progress = { book.overallProgress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
        Text(
            if (book.overallProgress <= 0f) "未开始" else "已读 ${(book.overallProgress * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun BookCover(book: BookRecord) {
    val density = LocalDensity.current
    val targetWidth = with(density) { 160.dp.roundToPx() }
    val targetHeight = with(density) { 230.dp.roundToPx() }
    val cacheKey = "${book.coverPath}:$targetWidth:$targetHeight"
    val bitmap by produceState<Bitmap?>(null, cacheKey) {
        value = withContext(Dispatchers.IO) {
            book.coverPath?.let(::File)?.takeIf(File::isFile)?.let { file ->
                CoverBitmapCache.load(cacheKey, file, targetWidth, targetHeight)
            }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "《${book.title}》封面",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                book.title.take(4),
                modifier = Modifier.padding(18.dp),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private object CoverBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16L / 1024L).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    @Synchronized
    fun load(key: String, file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        cache.get(key)?.let { return it }
        val bitmap = decodeSampled(file, targetWidth, targetHeight) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decodeSampled(file: File, targetWidth: Int, targetHeight: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= targetWidth &&
            bounds.outHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }.getOrNull()
}
