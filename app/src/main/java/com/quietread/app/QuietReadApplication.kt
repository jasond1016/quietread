package com.quietread.app

import android.app.Application
import com.quietread.app.data.BookRepository
import com.quietread.app.data.ReaderPreferences

class QuietReadApplication : Application() {
    lateinit var books: BookRepository
        private set
    lateinit var readerPreferences: ReaderPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        books = BookRepository(this)
        readerPreferences = ReaderPreferences(this)
    }
}
