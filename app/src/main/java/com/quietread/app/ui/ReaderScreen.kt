package com.quietread.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quietread.app.data.BookRecord
import com.quietread.app.data.ParagraphStyle
import com.quietread.app.data.ReaderSettings
import com.quietread.app.data.ReaderTheme
import com.quietread.app.data.ReadingPosition
import com.quietread.app.epub.EpubPackage
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: BookRecord,
    epub: EpubPackage,
    settings: ReaderSettings,
    onBack: () -> Unit,
    onPositionChanged: (ReadingPosition) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onThemeChanged: (ReaderTheme) -> Unit,
    onParagraphStyleChanged: (ParagraphStyle) -> Unit,
) {
    var controller by remember { mutableStateOf<ReaderController?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var showContents by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var renderState by remember {
        mutableStateOf(
            ReaderRenderState(
                spineIndex = book.lastSpineIndex,
                page = 0,
                pageCount = 1,
                overallProgress = book.overallProgress,
            ),
        )
    }
    var sliderProgress by remember(renderState.overallProgress) {
        mutableFloatStateOf(renderState.overallProgress.coerceIn(0f, 1f))
    }

    val dark = settings.theme == ReaderTheme.DARK
    val background = if (dark) Color(0xFF171916) else Color(0xFFF5F1E8)
    val foreground = if (dark) Color(0xFFE4E5DE) else Color(0xFF252722)
    val panel = if (dark) Color(0xF520231F) else Color(0xF5FFFBF3)

    Box(Modifier.fillMaxSize().background(background)) {
        EpubReaderView(
            epub = epub,
            initialSpineIndex = book.lastSpineIndex,
            initialSpineProgress = book.lastSpineProgress,
            initialLocator = book.lastLocator,
            settings = settings,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            onController = { controller = it },
            onStateChanged = { renderState = it },
            onCenterTap = { controlsVisible = !controlsVisible },
            onFootnoteOpened = { controlsVisible = false },
            onPositionChanged = onPositionChanged,
        )

        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(panel)
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = foreground)
                }
                Text(
                    text = book.title,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = foreground,
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(panel)
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${(sliderProgress * 100).roundToInt()}%",
                        color = foreground,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = sliderProgress,
                        onValueChange = { sliderProgress = it },
                        onValueChangeFinished = { controller?.goToOverall(sliderProgress) },
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Text(
                        "${renderState.page + 1}/${renderState.pageCount}",
                        color = foreground.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    IconButton(onClick = { showContents = true }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "目录", tint = foreground)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.FormatSize, contentDescription = "阅读设置", tint = foreground)
                    }
                }
            }
        }
    }

    if (showContents) {
        ModalBottomSheet(onDismissRequest = { showContents = false }) {
            Text(
                "目录",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (epub.toc.isEmpty()) {
                Text("这本书没有可用目录", modifier = Modifier.padding(24.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    itemsIndexed(epub.toc) { _, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showContents = false
                                    controlsVisible = false
                                    controller?.goTo(entry.spineIndex, fragment = entry.fragment)
                                }
                                .padding(
                                    start = (24 + entry.depth.coerceAtMost(3) * 18).dp,
                                    end = 24.dp,
                                    top = 15.dp,
                                    bottom = 15.dp,
                                ),
                        ) {
                            Text(
                                entry.title,
                                color = if (entry.spineIndex == renderState.spineIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
                Text("阅读设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(24.dp))
                Text("字号", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onFontScaleChanged(settings.fontScale - 0.1f) }) { Text("小") }
                    Text(
                        "${(settings.fontScale * 100).roundToInt()}%",
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Button(onClick = { onFontScaleChanged(settings.fontScale + 0.1f) }) { Text("大") }
                }
                Spacer(Modifier.height(28.dp))
                Text("主题", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeButton(
                        label = "浅色",
                        selected = settings.theme == ReaderTheme.LIGHT,
                        modifier = Modifier.weight(1f),
                    ) { onThemeChanged(ReaderTheme.LIGHT) }
                    ThemeButton(
                        label = "深色",
                        selected = settings.theme == ReaderTheme.DARK,
                        modifier = Modifier.weight(1f),
                    ) { onThemeChanged(ReaderTheme.DARK) }
                }
                Spacer(Modifier.height(28.dp))
                Text("段落", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ParagraphStyleButton(
                        label = "原书",
                        selected = settings.paragraphStyle == ParagraphStyle.ORIGINAL,
                        modifier = Modifier.weight(1f),
                    ) { onParagraphStyleChanged(ParagraphStyle.ORIGINAL) }
                    ParagraphStyleButton(
                        label = "顶格",
                        selected = settings.paragraphStyle == ParagraphStyle.FLUSH,
                        modifier = Modifier.weight(1f),
                    ) { onParagraphStyleChanged(ParagraphStyle.FLUSH) }
                    ParagraphStyleButton(
                        label = "缩进",
                        selected = settings.paragraphStyle == ParagraphStyle.INDENTED,
                        modifier = Modifier.weight(1f),
                    ) { onParagraphStyleChanged(ParagraphStyle.INDENTED) }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun ParagraphStyleButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = ThemeButton(label, selected, modifier, onClick)

@Composable
private fun ThemeButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        TextButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}
