package com.quietread.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class BookDatabase(context: Context) : SQLiteOpenHelper(context, "quietread.db", null, 1) {
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
                overall_progress REAL NOT NULL DEFAULT 0,
                imported_at INTEGER NOT NULL,
                last_opened_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX books_recent ON books(last_opened_at DESC, imported_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
            put("overall_progress", position.overallProgress.toDouble())
            put("last_opened_at", openedAt)
        }
        writableDatabase.update("books", values, "id = ?", arrayOf(id))
    }

    fun delete(id: String) {
        writableDatabase.delete("books", "id = ?", arrayOf(id))
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
        put("overall_progress", overallProgress.toDouble())
        put("imported_at", importedAt)
        put("last_opened_at", lastOpenedAt)
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
        overallProgress = float("overall_progress"),
        importedAt = long("imported_at"),
        lastOpenedAt = long("last_opened_at"),
    )

    private fun Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String) = getString(index(name))
    private fun Cursor.nullableString(name: String) = index(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(name: String) = getInt(index(name))
    private fun Cursor.float(name: String) = getFloat(index(name))
    private fun Cursor.long(name: String) = getLong(index(name))
}
