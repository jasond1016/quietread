package com.quietread.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.quietread.app.data.ReaderTheme
import com.quietread.app.ui.LibraryScreen
import com.quietread.app.ui.QuietReadTheme
import com.quietread.app.ui.ReaderScreen

class MainActivity : ComponentActivity() {
    private val applicationContainer by lazy { application as QuietReadApplication }
    private val viewModel by viewModels<MainViewModel> {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(applicationContainer.books) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val readerSettings by applicationContainer.readerPreferences.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = if (state.screen is AppScreen.Reader) {
                readerSettings.theme == ReaderTheme.DARK
            } else systemDark
            SideEffect {
                val barColor = if (dark) 0xFF171916.toInt() else 0xFFF5F1E8.toInt()
                val style = if (dark) SystemBarStyle.dark(barColor) else SystemBarStyle.light(barColor, barColor)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            QuietReadTheme(dark = dark) {
                val snackbar = remember { SnackbarHostState() }
                val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(viewModel::importBook) }

                LaunchedEffect(state.message) {
                    state.message?.let {
                        snackbar.showSnackbar(it)
                        viewModel.clearMessage()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        when (val screen = state.screen) {
                            AppScreen.Library -> LibraryScreen(
                                books = state.books,
                                onImport = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                                onOpen = viewModel::openBook,
                                onDelete = viewModel::deleteBook,
                            )
                            is AppScreen.Reader -> {
                                BackHandler(onBack = viewModel::closeReader)
                                ReaderScreen(
                                    book = screen.book,
                                    epub = screen.epub,
                                    settings = readerSettings,
                                    onBack = viewModel::closeReader,
                                    onPositionChanged = { viewModel.updatePosition(screen.book.id, it) },
                                    onFontScaleChanged = applicationContainer.readerPreferences::setFontScale,
                                    onThemeChanged = applicationContainer.readerPreferences::setTheme,
                                    onParagraphStyleChanged =
                                        applicationContainer.readerPreferences::setParagraphStyle,
                                )
                            }
                        }
                        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                        if (state.busy) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) handleExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    override fun onStop() {
        viewModel.pauseReadingSession()
        viewModel.flushPosition()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        viewModel.resumeReadingSession()
    }

    private fun handleExternalIntent(externalIntent: Intent?) {
        val uri = when (externalIntent?.action) {
            Intent.ACTION_VIEW -> externalIntent.data
            Intent.ACTION_SEND -> externalIntent.sharedStreamUri()
            else -> null
        }
        uri?.let(viewModel::importBook)
    }

    @Suppress("DEPRECATION")
    private fun Intent.sharedStreamUri(): Uri? {
        val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        return extra ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }
}
