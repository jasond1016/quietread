package com.quietread.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookDatabaseMigrationTest {
    @Test
    fun migratesVersionOneWithoutLosingExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "quietread-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
                database.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY)")
                database.execSQL("INSERT INTO books (id) VALUES ('existing-book')")
                database.version = 1
            }

            BookDatabase(context, databaseName).use { helper ->
                val database = helper.writableDatabase
                assertEquals(2, database.version)

                val columns = database.rawQuery("PRAGMA table_info(books)", null).use { cursor ->
                    buildSet {
                        val nameColumn = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                    }
                }
                assertTrue("last_locator_id" in columns)
                assertTrue("last_locator_path" in columns)
                assertTrue("last_locator_offset" in columns)

                database.rawQuery(
                    "SELECT id, last_locator_path, last_locator_offset FROM books",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("existing-book", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertEquals(0, cursor.getInt(2))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
