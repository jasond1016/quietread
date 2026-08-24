package com.quietread.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class BookDatabase(
    context: Context,
    databaseName: String = "quietread.db",
) : SQLiteOpenHelper(context, databaseName, null, 4) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE books (
                id TEXT PRIMARY KEY,
                sha256 TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                author TEXT,
                cover_path TEXT,
                epub_path TEXT NOT NULL,
                content_root TEXT NOT NULL,
                package_path TEXT NOT NULL,
                last_spine_index INTEGER NOT NULL DEFAULT 0,
                last_spine_progress REAL NOT NULL DEFAULT 0,
                last_locator_id TEXT,
                last_locator_path TEXT,
                last_locator_offset INTEGER NOT NULL DEFAULT 0,
                overall_progress REAL NOT NULL DEFAULT 0,
                total_reading_ms INTEGER NOT NULL DEFAULT 0,
                imported_at INTEGER NOT NULL,
                last_opened_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX books_recent ON books(last_opened_at DESC, imported_at DESC)")
        createAnnotationsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE books ADD COLUMN last_locator_id TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN last_locator_path TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN last_locator_offset INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE books ADD COLUMN total_reading_ms INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) createAnnotationsTable(db)
    }

    fun allBooks(): List<BookRecord> = readableDatabase.query(
        "books", null, null, null, null, null, "last_opened_at DESC, imported_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toBook()) } }

    fun book(id: String): BookRecord? = readableDatabase.query(
        "books", null, "id = ?", arrayOf(id), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toBook() else null }

    fun bookByHash(hash: String): BookRecord? = readableDatabase.query(
        "books", null, "sha256 = ?", arrayOf(hash), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toBook() else null }

    fun insert(book: BookRecord) {
        writableDatabase.insertOrThrow("books", null, book.values())
    }

    fun updatePosition(id: String, position: ReadingPosition, openedAt: Long) {
        val values = ContentValues().apply {
            put("last_spine_index", position.spineIndex)
            put("last_spine_progress", position.spineProgress.toDouble())
            put("last_locator_id", position.locator?.elementId)
            put("last_locator_path", position.locator?.elementPath)
            put("last_locator_offset", position.locator?.textOffset ?: 0)
            put("overall_progress", position.overallProgress.toDouble())
            put("last_opened_at", openedAt)
        }
        writableDatabase.update("books", values, "id = ?", arrayOf(id))
    }

    fun addReadingTime(id: String, elapsedMs: Long) {
        if (elapsedMs <= 0L) return
        writableDatabase.execSQL(
            "UPDATE books SET total_reading_ms = total_reading_ms + ? WHERE id = ?",
            arrayOf(elapsedMs, id),
        )
    }

    fun delete(id: String) {
        writableDatabase.delete("books", "id = ?", arrayOf(id))
    }

    fun allAnnotations(): List<BookAnnotation> = readableDatabase.query(
        "annotations", null, null, null, null, null, "created_at DESC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toAnnotation()) } }

    fun bookmarkAt(bookId: String, spineIndex: Int, locator: ReadingLocator): BookAnnotation? =
        readableDatabase.query(
            "annotations",
            null,
            "book_id = ? AND type = ? AND spine_index = ? AND start_locator_path = ? AND start_locator_offset = ?",
            arrayOf(
                bookId,
                AnnotationType.BOOKMARK.name,
                spineIndex.toString(),
                locator.elementPath,
                locator.textOffset.toString(),
            ),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toAnnotation() else null }

    fun insertAnnotation(annotation: BookAnnotation) {
        writableDatabase.insertOrThrow("annotations", null, annotation.values())
    }

    fun deleteAnnotation(id: String) {
        writableDatabase.delete("annotations", "id = ?", arrayOf(id))
    }

    fun updateAnnotationNote(id: String, note: String, updatedAt: Long) {
        val values = ContentValues().apply {
            put("type", AnnotationType.THOUGHT.name)
            put("note", note)
            put("updated_at", updatedAt)
        }
        writableDatabase.update("annotations", values, "id = ?", arrayOf(id))
    }

    private fun createAnnotationsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS annotations (
                id TEXT PRIMARY KEY,
                book_id TEXT NOT NULL,
                type TEXT NOT NULL,
                spine_index INTEGER NOT NULL,
                start_locator_id TEXT,
                start_locator_path TEXT NOT NULL,
                start_locator_offset INTEGER NOT NULL,
                end_locator_id TEXT,
                end_locator_path TEXT,
                end_locator_offset INTEGER,
                selected_text TEXT,
                note TEXT,
                color INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS annotations_book_position " +
                "ON annotations(book_id, spine_index, start_locator_path, start_locator_offset)",
        )
    }

    private fun BookRecord.values() = ContentValues().apply {
        put("id", id)
        put("sha256", sha256)
        put("title", title)
        put("author", author)
        put("cover_path", coverPath)
        put("epub_path", epubPath)
        put("content_root", contentRoot)
        put("package_path", packagePath)
        put("last_spine_index", lastSpineIndex)
        put("last_spine_progress", lastSpineProgress.toDouble())
        put("last_locator_id", lastLocator?.elementId)
        put("last_locator_path", lastLocator?.elementPath)
        put("last_locator_offset", lastLocator?.textOffset ?: 0)
        put("overall_progress", overallProgress.toDouble())
        put("total_reading_ms", totalReadingMs)
        put("imported_at", importedAt)
        put("last_opened_at", lastOpenedAt)
    }

    private fun BookAnnotation.values() = ContentValues().apply {
        put("id", id)
        put("book_id", bookId)
        put("type", type.name)
        put("spine_index", spineIndex)
        put("start_locator_id", startLocator.elementId)
        put("start_locator_path", startLocator.elementPath)
        put("start_locator_offset", startLocator.textOffset)
        put("end_locator_id", endLocator?.elementId)
        put("end_locator_path", endLocator?.elementPath)
        endLocator?.let { put("end_locator_offset", it.textOffset) }
        put("selected_text", selectedText)
        put("note", note)
        color?.let { put("color", it) }
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    private fun Cursor.toBook() = BookRecord(
        id = string("id"),
        sha256 = string("sha256"),
        title = string("title"),
        author = nullableString("author"),
        coverPath = nullableString("cover_path"),
        epubPath = string("epub_path"),
        contentRoot = string("content_root"),
        packagePath = string("package_path"),
        lastSpineIndex = int("last_spine_index"),
        lastSpineProgress = float("last_spine_progress"),
        lastLocator = nullableString("last_locator_path")?.let { path ->
            ReadingLocator.create(
                elementId = nullableString("last_locator_id"),
                elementPath = path,
                textOffset = int("last_locator_offset"),
            )
        },
        overallProgress = float("overall_progress"),
        totalReadingMs = long("total_reading_ms"),
        importedAt = long("imported_at"),
        lastOpenedAt = long("last_opened_at"),
    )

    private fun Cursor.toAnnotation() = BookAnnotation(
        id = string("id"),
        bookId = string("book_id"),
        type = runCatching { AnnotationType.valueOf(string("type")) }.getOrDefault(AnnotationType.BOOKMARK),
        spineIndex = int("spine_index"),
        startLocator = ReadingLocator.create(
            nullableString("start_locator_id"),
            string("start_locator_path"),
            int("start_locator_offset"),
        ) ?: ReadingLocator(null, ".", 0),
        endLocator = nullableString("end_locator_path")?.let { path ->
            ReadingLocator.create(
                nullableString("end_locator_id"),
                path,
                nullableInt("end_locator_offset") ?: 0,
            )
        },
        selectedText = nullableString("selected_text"),
        note = nullableString("note"),
        color = nullableInt("color"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
    )

    private fun Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String) = getString(index(name))
    private fun Cursor.nullableString(name: String) = index(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(name: String) = getInt(index(name))
    private fun Cursor.float(name: String) = getFloat(index(name))
    private fun Cursor.long(name: String) = getLong(index(name))
    private fun Cursor.nullableInt(name: String) = index(name).let { if (isNull(it)) null else getInt(it) }
}
