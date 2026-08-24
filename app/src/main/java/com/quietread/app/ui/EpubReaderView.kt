package com.quietread.app.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.MimeTypeMap
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.quietread.app.data.ReaderSettings
import com.quietread.app.data.ReaderTheme
import com.quietread.app.data.ParagraphStyle
import com.quietread.app.data.ReadingLocator
import com.quietread.app.data.ReadingPosition
import com.quietread.app.data.BookAnnotation
import com.quietread.app.data.AnnotationType
import com.quietread.app.data.ReadingSelection
import com.quietread.app.epub.EpubPackage
import com.quietread.app.epub.EpubResourceResolver
import com.quietread.app.epub.ReadingProgress
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import kotlin.math.floor

data class ReaderRenderState(
    val spineIndex: Int,
    val page: Int,
    val pageCount: Int,
    val overallProgress: Float,
)

private class SelectionWebView(context: android.content.Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(SelectionActionModeCallback(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(SelectionActionModeCallback(callback), type)

    private class SelectionActionModeCallback(
        private val delegate: ActionMode.Callback,
    ) : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = delegate.onCreateActionMode(mode, menu)
            menu.clear()
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            delegate.onPrepareActionMode(mode, menu)
            menu.clear()
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = true

        override fun onDestroyActionMode(mode: ActionMode) {
            delegate.onDestroyActionMode(mode)
        }
    }
}

class ReaderController(
    private val epub: EpubPackage,
    private val initialSpineIndex: Int,
    private val initialSpineProgress: Float,
    initialLocator: ReadingLocator?,
    initialSettings: ReaderSettings,
    initialAnnotations: List<BookAnnotation>,
) {
    private var webView: WebView? = null
    private var settings = initialSettings
    private var annotations = initialAnnotations
    private var spineIndex = initialSpineIndex.coerceIn(epub.spine.indices)
    private var page = 0
    private var pageCount = 1
    private var pendingProgress = initialSpineProgress.coerceIn(0f, 1f)
    private var pendingFragment: String? = null
    private var pendingLocator = initialLocator
    private var currentLocator: ReadingLocator? = initialLocator
    private var currentSelection: ReadingSelection? = null
    private var completed = spineIndex == epub.spine.lastIndex && initialSpineProgress >= 0.999999f
    private val spineWeights = epub.spine.map { it.readingWeight }
    private val contentRoot = epub.contentRoot.canonicalFile

    var onStateChanged: (ReaderRenderState) -> Unit = {}
    var onCenterTap: () -> Unit = {}
    var onFootnoteOpened: () -> Unit = {}
    var onBookmarkGesture: () -> Unit = {}
    var onSelectionChanged: (ReadingSelection?) -> Unit = {}
    var onHighlightRequested: (ReadingSelection) -> Unit = {}
    var onThoughtRequested: (ReadingSelection) -> Unit = {}
    var onAnnotationDeleteRequested: (String) -> Unit = {}
    var onPositionChanged: (ReadingPosition) -> Unit = {}

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: android.content.Context): WebView = SelectionWebView(context).also { view ->
        webView = view
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            domStorageEnabled = false
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
        }
        view.isHorizontalScrollBarEnabled = false
        view.isVerticalScrollBarEnabled = false
        view.overScrollMode = WebView.OVER_SCROLL_NEVER
        view.addJavascriptInterface(Bridge(view), "QuietRead")
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                request?.url?.let(::interceptResource)
        }
        loadCurrentChapter()
    }

    fun updateSettings(newSettings: ReaderSettings) {
        if (newSettings == settings) return
        settings = newSettings
        pendingProgress = currentSpineProgress()
        pendingFragment = null
        pendingLocator = currentLocator
        loadCurrentChapter()
    }

    fun updateAnnotations(newAnnotations: List<BookAnnotation>) {
        if (annotations == newAnnotations) return
        annotations = newAnnotations
        applyHighlights()
        applyBookmarkState()
    }

    fun clearSelection() {
        currentSelection = null
        evaluate("window.getSelection()?.removeAllRanges();")
        onSelectionChanged(null)
    }

    fun nextPage() {
        if (page + 1 < pageCount) {
            evaluate("window.qrGoPage(${page + 1});")
        } else if (spineIndex + 1 < epub.spine.size) {
            emitPosition(1f)
            loadChapter(spineIndex + 1, 0f)
        } else {
            completed = true
            emitPosition(1f)
            onStateChanged(ReaderRenderState(spineIndex, page, pageCount, 1f))
        }
    }

    fun previousPage() {
        if (page > 0) {
            completed = false
            evaluate("window.qrGoPage(${page - 1});")
        } else if (spineIndex > 0) {
            completed = false
            loadChapter(spineIndex - 1, 1f)
        }
    }

    fun goTo(spine: Int, progress: Float = 0f, fragment: String? = null) {
        loadChapter(spine.coerceIn(epub.spine.indices), progress, fragment)
    }

    fun goToOverall(progress: Float) {
        val target = ReadingProgress.target(spineWeights, progress)
        loadChapter(target.spineIndex, target.spineProgress)
    }

    fun goToLocator(spine: Int, locator: ReadingLocator) {
        spineIndex = spine.coerceIn(epub.spine.indices)
        page = 0
        pageCount = 1
        pendingProgress = 0f
        pendingFragment = null
        pendingLocator = locator
        currentLocator = locator
        completed = false
        loadCurrentChapter()
    }

    fun destroy() {
        webView?.apply {
            removeJavascriptInterface("QuietRead")
            stopLoading()
            destroy()
        }
        webView = null
    }

    private fun loadChapter(index: Int, progress: Float, fragment: String? = null) {
        spineIndex = index
        page = 0
        pageCount = 1
        pendingProgress = progress.coerceIn(0f, 1f)
        pendingFragment = fragment
        pendingLocator = null
        currentLocator = null
        completed = index == epub.spine.lastIndex && progress >= 0.999999f
        loadCurrentChapter()
    }

    private fun loadCurrentChapter() {
        val view = webView ?: return
        val chapter = epub.spine[spineIndex].file
        val base = chapterBaseUrl(chapter)
        val html = sanitizeAndPrepare(chapter, base, settings)
        view.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
    }

    private fun sanitizeAndPrepare(file: File, base: String, settings: ReaderSettings): String {
        val document = Jsoup.parse(file, null, base)
        document.select("script, iframe, frame, object, embed, form, input, button, textarea, audio, video").remove()
        document.allElements.forEach { element ->
            element.attributes().asList()
                .filter { it.key.startsWith("on", ignoreCase = true) }
                .forEach { element.removeAttr(it.key) }
        }
        document.select("[src], [href]").forEach { element ->
            listOf("src", "href").forEach { attribute ->
                val value = element.attr(attribute).trim().lowercase()
                if (
                    value.startsWith("http://") ||
                    value.startsWith("https://") ||
                    value.startsWith("file:") ||
                    value.startsWith("content:") ||
                    value.startsWith("javascript:")
                ) {
                    element.removeAttr(attribute)
                }
            }
        }

        val dark = settings.theme == ReaderTheme.DARK
        val background = if (dark) "#171916" else "#f5f1e8"
        val foreground = if (dark) "#e4e5de" else "#252722"
        val muted = if (dark) "#a9ada5" else "#65685f"
        val fontPercent = (settings.fontScale * 100).toInt()
        val paragraphRule = when (settings.paragraphStyle) {
            ParagraphStyle.ORIGINAL -> ""
            ParagraphStyle.FLUSH -> "body p { text-indent: 0 !important; }"
            ParagraphStyle.INDENTED -> "body p { text-indent: 2em !important; }"
        }
        document.head().prependElement("meta")
            .attr("http-equiv", "Content-Security-Policy")
            .attr(
                "content",
                "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self' data:; script-src 'unsafe-inline'",
            )
        document.head().appendElement("meta").attr("name", "viewport")
            .attr("content", "width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no")
        document.head().appendElement("style").appendText(
            """
            :root { color-scheme: ${if (dark) "dark" else "light"}; }
            html {
              width: 100% !important; height: 100% !important;
              margin: 0 !important; padding: 0 !important;
              overflow: hidden !important; background: $background !important;
            }
            body {
              box-sizing: border-box !important;
              width: 100% !important; height: 100% !important;
              margin: 0 !important; padding: 16px 20px !important;
              overflow: visible !important; column-fill: auto !important;
              background: $background !important; color: $foreground !important;
              font-size: $fontPercent% !important; line-height: 1.75 !important;
              overflow-wrap: break-word;
            }
            body * { max-width: 100% !important; }
            img, svg { max-height: calc(var(--qr-page-height, 100vh) - 64px) !important; object-fit: contain; }
            a { color: $foreground !important; text-decoration-color: $muted !important; }
            p { margin-block: 0.55em; }
            $paragraphRule
            ::highlight(quietread-highlight) {
              background-color: rgba(241, 199, 91, 0.42);
              text-decoration: underline rgba(190, 137, 24, 0.85) 1.5px;
            }
            #quietread-selection-toolbar {
              position: fixed !important; z-index: 2147483646 !important;
              display: none !important; align-items: center !important;
              max-width: calc(100vw - 16px) !important; padding: 4px 6px !important;
              border: 1px solid $muted !important; border-radius: 12px !important;
              background: $background !important; color: $foreground !important;
              box-shadow: 0 5px 22px rgba(0, 0, 0, 0.28) !important;
              white-space: nowrap !important; transform: none !important;
            }
            #quietread-selection-toolbar[data-open="true"] { display: flex !important; }
            #quietread-selection-toolbar button {
              border: 0 !important; padding: 9px 11px !important; margin: 0 !important;
              background: transparent !important; color: $foreground !important;
              font: 500 14px/1 sans-serif !important; white-space: nowrap !important;
            }
            #quietread-selection-toolbar button + button {
              border-left: 1px solid color-mix(in srgb, $muted 32%, transparent) !important;
            }
            #quietread-bookmark-pull-indicator {
              position: fixed !important; z-index: 0 !important; top: 10px !important; left: 50% !important;
              width: 38px !important; height: 38px !important; margin-left: -19px !important;
              display: flex !important; align-items: center !important; justify-content: center !important;
              border-radius: 19px !important; background: $background !important; color: $foreground !important;
              box-shadow: 0 2px 10px rgba(0, 0, 0, 0.16) !important;
              font: 20px/1 sans-serif !important; opacity: 0 !important;
              transform: scale(0.72) !important; transition: opacity 120ms, transform 120ms !important;
            }
            .footnote-content,
            [role="doc-footnote"],
            [epub\:type~="footnote"] { display: none !important; }
            #quietread-footnote-overlay {
              position: fixed !important; inset: 0 !important; z-index: 2147483647 !important;
              display: none !important; align-items: flex-end !important;
              box-sizing: border-box !important; padding: 20px !important;
              max-width: none !important; background: rgba(0, 0, 0, 0.42) !important;
              column-span: all !important;
            }
            #quietread-footnote-overlay[data-open="true"] { display: flex !important; }
            #quietread-footnote-panel {
              width: 100% !important; max-width: none !important;
              max-height: calc(var(--qr-page-height, 600px) - 40px) !important;
              box-sizing: border-box !important; overflow-y: auto !important;
              overscroll-behavior: contain; padding: 18px 20px 20px !important;
              border: 1px solid $muted !important; border-radius: 16px !important;
              background: $background !important; color: $foreground !important;
              box-shadow: 0 8px 32px rgba(0, 0, 0, 0.28) !important;
            }
            #quietread-footnote-header {
              display: flex !important; align-items: center !important;
              justify-content: space-between !important; gap: 16px !important;
              margin-bottom: 12px !important; font-weight: 700 !important;
            }
            #quietread-footnote-close {
              border: 0 !important; padding: 6px 0 6px 16px !important;
              background: transparent !important; color: $foreground !important;
              font: inherit !important;
            }
            #quietread-footnote-content,
            #quietread-footnote-content * {
              max-width: 100% !important; color: $foreground !important;
            }
            #quietread-footnote-content p {
              margin: 0 !important; text-indent: 0 !important;
              line-height: 1.65 !important;
            }
            """.trimIndent(),
        )
        document.body().appendElement("script").appendChild(DataNode(PAGINATION_SCRIPT))
        document.outputSettings().prettyPrint(false)
        return document.outerHtml()
    }

    private fun handleReady(total: Int) {
        pageCount = total.coerceAtLeast(1)
        val fragment = pendingFragment
        val progress = pendingProgress
        val locator = pendingLocator
        pendingFragment = null
        pendingProgress = 0f
        pendingLocator = null
        applyHighlights()
        applyBookmarkState()
        if (fragment != null) {
            evaluate("window.qrGoAnchor(${jsString(fragment)});")
        } else if (locator != null) {
            val fallbackPage = floor(progress.coerceIn(0f, 0.999999f) * pageCount)
                .toInt().coerceIn(0, pageCount - 1)
            evaluate(
                "window.qrGoLocator(" +
                    "${jsString(locator.elementId.orEmpty())}," +
                    "${jsString(locator.elementPath)}," +
                    "${locator.textOffset},$fallbackPage);",
            )
        } else {
            val target = floor(progress.coerceIn(0f, 0.999999f) * pageCount).toInt().coerceIn(0, pageCount - 1)
            evaluate("window.qrGoPage($target);")
        }
    }

    private fun applyHighlights() {
        val items = JSONArray()
        annotations.asSequence()
            .filter { it.spineIndex == spineIndex && it.type != AnnotationType.BOOKMARK && it.endLocator != null }
            .forEach { annotation ->
                items.put(
                    JSONObject().apply {
                        put("id", annotation.id)
                        put("type", annotation.type.name)
                        put("text", annotation.selectedText.orEmpty())
                        put("start", locatorJson(annotation.startLocator))
                        put("end", locatorJson(annotation.endLocator!!))
                    },
                )
            }
        evaluate("window.qrApplyHighlights?.($items);")
    }

    private fun applyBookmarkState() {
        val locator = currentLocator
        val bookmarked = locator != null && annotations.any {
            it.type == AnnotationType.BOOKMARK &&
                it.spineIndex == spineIndex &&
                it.startLocator.elementPath == locator.elementPath &&
                it.startLocator.textOffset == locator.textOffset
        }
        evaluate("window.qrSetBookmarked?.($bookmarked);")
    }

    private fun locatorJson(locator: ReadingLocator) = JSONObject().apply {
        put("id", locator.elementId.orEmpty())
        put("path", locator.elementPath)
        put("offset", locator.textOffset)
    }

    private fun handlePageChanged(newPage: Int, locator: ReadingLocator?) {
        page = newPage.coerceIn(0, pageCount - 1)
        currentLocator = locator
        applyBookmarkState()
        val local = currentSpineProgress()
        val overall = overallProgress(local)
        onStateChanged(ReaderRenderState(spineIndex, page, pageCount, overall))
        emitPosition(local)
    }

    private fun handleRelayout(total: Int, currentPage: Int, locator: ReadingLocator?) {
        pageCount = total.coerceAtLeast(1)
        handlePageChanged(currentPage, locator)
    }

    private fun handleLink(rawHref: String) {
        val href = Uri.decode(rawHref)
        if (href.startsWith("http://", true) || href.startsWith("https://", true)) return
        val current = epub.spine[spineIndex].file
        val fragment = href.substringAfter('#', "").takeIf(String::isNotBlank)
        if (href.startsWith("#")) {
            fragment?.let { evaluate("window.qrGoAnchor(${jsString(it)});") }
            return
        }
        val targetPath = runCatching {
            val path = URI(href.substringBefore('#')).path
            File(current.parentFile, path).canonicalFile
        }.getOrNull() ?: return
        val targetIndex = epub.spine.indexOfFirst { it.file.canonicalFile == targetPath }
        if (targetIndex >= 0) loadChapter(targetIndex, 0f, fragment)
    }

    private fun currentSpineProgress(): Float =
        if (completed) 1f else if (pageCount <= 1) 0f else (page.toFloat() / pageCount).coerceIn(0f, 1f)

    private fun emitPosition(local: Float) {
        onPositionChanged(
            ReadingPosition(
                spineIndex = spineIndex,
                spineProgress = local,
                locator = currentLocator,
                overallProgress = overallProgress(local),
            ),
        )
    }

    private fun overallProgress(local: Float): Float {
        return ReadingProgress.overall(spineWeights, spineIndex, local)
    }

    private fun chapterBaseUrl(chapter: File): String {
        val parent = (chapter.parentFile ?: contentRoot).canonicalFile
        val relative = parent.relativeTo(contentRoot).invariantSeparatorsPath
        val encoded = relative.split('/').filter(String::isNotEmpty).joinToString("/") { Uri.encode(it) }
        return "$LOCAL_ORIGIN/$BOOK_PATH${if (encoded.isEmpty()) "" else "$encoded/"}"
    }

    private fun interceptResource(uri: Uri): WebResourceResponse? {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "https" && uri.host.equals(LOCAL_HOST, ignoreCase = true)) {
            val path = uri.path.orEmpty()
            if (!path.startsWith("/$BOOK_PATH")) return blockedResponse(404, "Not Found")
            val relative = path.removePrefix("/$BOOK_PATH")
            val resource = EpubResourceResolver.resolve(contentRoot, relative)
                ?: return blockedResponse(404, "Not Found")
            val mimeType = resourceMimeType(resource)
            val encoding = if (
                mimeType.startsWith("text/") ||
                mimeType == "application/xhtml+xml" ||
                mimeType == "application/xml"
            ) "UTF-8" else null
            return WebResourceResponse(mimeType, encoding, resource.inputStream().buffered())
        }
        return if (scheme in setOf("http", "https", "file", "content")) {
            blockedResponse(403, "Forbidden")
        } else null
    }

    private fun resourceMimeType(file: File): String = when (file.extension.lowercase()) {
        "css" -> "text/css"
        "html", "htm" -> "text/html"
        "xhtml" -> "application/xhtml+xml"
        "svg" -> "image/svg+xml"
        "xml", "opf", "ncx" -> "application/xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    private fun blockedResponse(statusCode: Int, reason: String) = WebResourceResponse(
        "text/plain",
        "UTF-8",
        statusCode,
        reason,
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun evaluate(script: String) {
        webView?.post { webView?.evaluateJavascript(script, null) }
    }

    private fun jsString(value: String): String = org.json.JSONObject.quote(value)

    private inner class Bridge(private val view: WebView) {
        @JavascriptInterface
        fun ready(totalPages: Int) = view.post { handleReady(totalPages) }

        @JavascriptInterface
        fun pageChanged(currentPage: Int, elementId: String, elementPath: String, textOffset: Int) = view.post {
            handlePageChanged(currentPage, ReadingLocator.create(elementId, elementPath, textOffset))
        }

        @JavascriptInterface
        fun relayout(
            totalPages: Int,
            currentPage: Int,
            elementId: String,
            elementPath: String,
            textOffset: Int,
        ) = view.post {
            handleRelayout(
                totalPages,
                currentPage,
                ReadingLocator.create(elementId, elementPath, textOffset),
            )
        }

        @JavascriptInterface
        fun tapped(ratio: Double) = view.post {
            when {
                ratio < 0.30 -> previousPage()
                ratio > 0.70 -> nextPage()
                else -> onCenterTap()
            }
        }

        @JavascriptInterface
        fun openLink(href: String) = view.post { handleLink(href) }

        @JavascriptInterface
        fun footnoteOpened() = view.post { onFootnoteOpened() }

        @JavascriptInterface
        fun toggleBookmark() = view.post { onBookmarkGesture() }

        @JavascriptInterface
        fun selectionChanged(
            text: String,
            startId: String,
            startPath: String,
            startOffset: Int,
            endId: String,
            endPath: String,
            endOffset: Int,
        ) = view.post {
            val start = ReadingLocator.create(startId, startPath, startOffset)
            val end = ReadingLocator.create(endId, endPath, endOffset)
            currentSelection = if (start != null && end != null && text.isNotBlank()) {
                    ReadingSelection(spineIndex, start, end, text.take(20_000))
                } else null
            onSelectionChanged(currentSelection)
        }

        @JavascriptInterface
        fun selectionCleared() = view.post {
            currentSelection = null
            onSelectionChanged(null)
        }

        @JavascriptInterface
        fun highlightSelection() = view.post {
            currentSelection?.let(onHighlightRequested)
            clearSelection()
        }

        @JavascriptInterface
        fun writeThought() = view.post {
            currentSelection?.let(onThoughtRequested)
            clearSelection()
        }

        @JavascriptInterface
        fun deleteAnnotation(id: String) = view.post {
            id.takeIf { it.isNotBlank() }?.let(onAnnotationDeleteRequested)
        }

        @JavascriptInterface
        fun copyText(text: String) = view.post {
            val clipboard = view.context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("QuietRead", text.take(20_000)))
        }

        @JavascriptInterface
        fun turnPage(delta: Int) = view.post {
            if (delta > 0) nextPage() else if (delta < 0) previousPage()
        }
    }

    private companion object {
        const val LOCAL_HOST = "appassets.androidplatform.net"
        const val LOCAL_ORIGIN = "https://$LOCAL_HOST"
        const val BOOK_PATH = "book/"

        val PAGINATION_SCRIPT = """
            (() => {
              const flow = document.body;
              let viewportWidth = 1;
              let viewportHeight = 1;
              let totalPages = 1;
              let currentPage = 0;
              let initialized = false;
              let resizeTimer = 0;
              let touchStart = null;
              let suppressClickUntil = 0;
              const pageTail = document.createElement('span');
              pageTail.setAttribute('aria-hidden', 'true');
              pageTail.style.cssText = 'position:absolute!important;left:0!important;top:0!important;width:1px!important;height:1px!important;pointer-events:none!important;visibility:hidden!important;';
              flow.appendChild(pageTail);
              const footnoteOverlay = document.createElement('div');
              footnoteOverlay.id = 'quietread-footnote-overlay';
              footnoteOverlay.setAttribute('role', 'dialog');
              footnoteOverlay.setAttribute('aria-modal', 'true');
              footnoteOverlay.setAttribute('aria-label', '脚注');
              footnoteOverlay.setAttribute('aria-hidden', 'true');
              footnoteOverlay.innerHTML = `
                <div id="quietread-footnote-panel" tabindex="-1">
                  <div id="quietread-footnote-header">
                    <span>脚注</span>
                    <button id="quietread-footnote-close" type="button" aria-label="关闭脚注">关闭</button>
                  </div>
                  <div id="quietread-footnote-content"></div>
                </div>`;
              document.documentElement.appendChild(footnoteOverlay);
              const footnotePanel = document.getElementById('quietread-footnote-panel');
              const footnoteContent = document.getElementById('quietread-footnote-content');
              let lastFootnoteAnchor = null;
              const selectionToolbar = document.createElement('div');
              selectionToolbar.id = 'quietread-selection-toolbar';
              selectionToolbar.setAttribute('role', 'toolbar');
              selectionToolbar.setAttribute('aria-label', '文本操作');
              selectionToolbar.innerHTML = `
                <button type="button" data-action="copy">复制</button>
                <button type="button" data-action="highlight">划线</button>
                <button type="button" data-action="thought">写想法</button>
                <button type="button" data-action="delete">取消划线</button>`;
              document.documentElement.appendChild(selectionToolbar);
              const pullIndicator = document.createElement('div');
              pullIndicator.id = 'quietread-bookmark-pull-indicator';
              pullIndicator.setAttribute('aria-hidden', 'true');
              pullIndicator.textContent = '🔖';
              document.documentElement.appendChild(pullIndicator);
              window.qrSetBookmarked = (value) => {
                pullIndicator.textContent = value ? '🔖−' : '🔖+';
              };
              let toolbarSelectionText = '';
              let toolbarAnnotation = null;
              let renderedHighlights = [];

              const hideSelectionToolbar = () => {
                selectionToolbar.removeAttribute('data-open');
                toolbarSelectionText = '';
                toolbarAnnotation = null;
              };

              const placeSelectionToolbar = (rect, mode) => {
                selectionToolbar.querySelector('[data-action="highlight"]').style.display = mode === 'selection' ? '' : 'none';
                selectionToolbar.querySelector('[data-action="thought"]').style.display = mode === 'selection' ? '' : 'none';
                selectionToolbar.querySelector('[data-action="delete"]').style.display = mode === 'annotation' ? '' : 'none';
                selectionToolbar.dataset.open = 'true';
                selectionToolbar.style.visibility = 'hidden';
                selectionToolbar.style.left = '0px';
                selectionToolbar.style.top = '0px';
                const width = selectionToolbar.offsetWidth;
                const height = selectionToolbar.offsetHeight;
                const center = rect.left + rect.width / 2;
                const left = Math.max(8, Math.min(viewportWidth - width - 8, center - width / 2));
                const above = rect.top - height - 10;
                const top = above >= 8
                  ? above
                  : Math.min(viewportHeight - height - 8, rect.bottom + 10);
                selectionToolbar.style.left = `${'$'}{Math.round(left)}px`;
                selectionToolbar.style.top = `${'$'}{Math.max(8, Math.round(top))}px`;
                selectionToolbar.style.visibility = 'visible';
              };

              selectionToolbar.addEventListener('pointerdown', (event) => event.preventDefault());
              selectionToolbar.addEventListener('click', (event) => {
                event.preventDefault();
                event.stopPropagation();
                const action = event.target && event.target.dataset && event.target.dataset.action;
                if (action === 'copy') QuietRead.copyText(toolbarSelectionText);
                else if (action === 'highlight') QuietRead.highlightSelection();
                else if (action === 'thought') QuietRead.writeThought();
                else if (action === 'delete' && toolbarAnnotation) QuietRead.deleteAnnotation(toolbarAnnotation.id);
                hideSelectionToolbar();
              }, true);

              const footnoteTarget = (anchor) => {
                const href = anchor.getAttribute('href') || '';
                if (!href.startsWith('#') || href.length <= 1) return null;
                let id;
                try { id = decodeURIComponent(href.slice(1)); }
                catch (_) { id = href.slice(1); }
                const target = document.getElementById(id);
                if (!target) return null;
                const isNote = anchor.matches('.footnote, [role="doc-noteref"], [epub\\:type~="noteref"]') ||
                  target.matches('.footnote-item, [role="doc-footnote"], [epub\\:type~="footnote"]') ||
                  Boolean(target.closest('.footnote-content, [role="doc-footnote"], [epub\\:type~="footnote"]'));
                return isNote ? target : null;
              };

              const showFootnote = (anchor) => {
                const target = footnoteTarget(anchor);
                if (!target) return false;
                footnoteContent.replaceChildren();
                Array.from(target.childNodes).forEach((node) => {
                  footnoteContent.appendChild(node.cloneNode(true));
                });
                footnoteContent.querySelectorAll('[id]').forEach((element) => element.removeAttribute('id'));
                footnoteContent.querySelectorAll('a[href^="#"]').forEach((element) => element.removeAttribute('href'));
                lastFootnoteAnchor = anchor;
                footnoteOverlay.dataset.open = 'true';
                footnoteOverlay.setAttribute('aria-hidden', 'false');
                footnotePanel.scrollTop = 0;
                footnotePanel.focus({ preventScroll: true });
                QuietRead.footnoteOpened();
                return true;
              };

              const closeFootnote = () => {
                footnoteOverlay.removeAttribute('data-open');
                footnoteOverlay.setAttribute('aria-hidden', 'true');
                if (lastFootnoteAnchor) lastFootnoteAnchor.focus({ preventScroll: true });
                lastFootnoteAnchor = null;
              };

              const locatorElement = (id, path) => {
                if (id) {
                  const byId = document.getElementById(id);
                  if (byId && flow.contains(byId)) return byId;
                }
                if (path === '.') return flow;
                if (!/^\d+(\/\d+)*$/.test(path || '')) return null;
                let element = flow;
                for (const part of path.split('/')) {
                  element = element.children[Number(part)];
                  if (!element) return null;
                }
                return element;
              };

              const locatorPath = (element) => {
                if (element === flow) return '.';
                const parts = [];
                let current = element;
                while (current && current !== flow) {
                  const parent = current.parentElement;
                  if (!parent) return '';
                  const index = Array.prototype.indexOf.call(parent.children, current);
                  if (index < 0) return '';
                  parts.unshift(String(index));
                  current = parent;
                }
                return current === flow ? parts.join('/') : '';
              };

              const textOffsetWithin = (element, targetNode, nodeOffset) => {
                const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
                let offset = 0;
                for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                  if (node === targetNode) return offset + Math.max(0, Math.min(node.length, nodeOffset));
                  offset += node.length;
                }
                return 0;
              };

              const selectionPoint = (node, offset) => {
                const element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
                const block = element &&
                  (element.closest('p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,figcaption,div,section,article') || element);
                if (!block || !flow.contains(block)) return null;
                const path = locatorPath(block);
                if (!path) return null;
                let textOffset = 0;
                if (node.nodeType === Node.TEXT_NODE) {
                  textOffset = textOffsetWithin(block, node, offset);
                } else {
                  const prefix = document.createRange();
                  prefix.selectNodeContents(block);
                  try { prefix.setEnd(node, offset); textOffset = prefix.toString().length; }
                  catch (_) { textOffset = 0; }
                }
                return { id: block.id || '', path, offset: textOffset };
              };

              const pointForLocator = (locator) => {
                const element = locatorElement(locator.id, locator.path);
                if (!element) return null;
                let remaining = Math.max(0, Number(locator.offset) || 0);
                const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
                let last = null;
                for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                  last = node;
                  if (remaining <= node.length) return { node, offset: remaining };
                  remaining -= node.length;
                }
                return last ? { node: last, offset: last.length } : null;
              };

              window.qrApplyHighlights = (items) => {
                if (!window.CSS || !CSS.highlights || typeof Highlight === 'undefined') return;
                renderedHighlights = [];
                (Array.isArray(items) ? items : []).forEach((item) => {
                  const start = pointForLocator(item.start);
                  const end = pointForLocator(item.end);
                  if (!start || !end) return;
                  try {
                    const range = document.createRange();
                    range.setStart(start.node, start.offset);
                    range.setEnd(end.node, end.offset);
                    if (!range.collapsed) renderedHighlights.push({
                      id: String(item.id || ''),
                      type: String(item.type || 'HIGHLIGHT'),
                      text: String(item.text || range.toString()),
                      range
                    });
                  } catch (_) {}
                });
                CSS.highlights.delete('quietread-highlight');
                if (renderedHighlights.length) {
                  CSS.highlights.set(
                    'quietread-highlight',
                    new Highlight(...renderedHighlights.map((item) => item.range))
                  );
                }
              };

              const visibleRangeRect = (range) => Array.from(range.getClientRects()).find((rect) =>
                rect.right > 0 && rect.left < viewportWidth && rect.bottom > 0 && rect.top < viewportHeight &&
                rect.width > 0 && rect.height > 0
              );

              const highlightAtPoint = (x, y) => renderedHighlights.find((item) =>
                Array.from(item.range.getClientRects()).some((rect) =>
                  x >= rect.left - 4 && x <= rect.right + 4 && y >= rect.top - 4 && y <= rect.bottom + 4
                )
              );

              let selectionTimer = 0;
              document.addEventListener('selectionchange', () => {
                window.clearTimeout(selectionTimer);
                selectionTimer = window.setTimeout(() => {
                  const selection = window.getSelection();
                  if (!selection || selection.rangeCount !== 1 || selection.isCollapsed) {
                    if (!toolbarAnnotation) hideSelectionToolbar();
                    QuietRead.selectionCleared();
                    return;
                  }
                  const range = selection.getRangeAt(0);
                  if (!flow.contains(range.startContainer) || !flow.contains(range.endContainer)) {
                    hideSelectionToolbar();
                    QuietRead.selectionCleared();
                    return;
                  }
                  const start = selectionPoint(range.startContainer, range.startOffset);
                  const end = selectionPoint(range.endContainer, range.endOffset);
                  const text = selection.toString();
                  if (!start || !end || !text.trim()) {
                    hideSelectionToolbar();
                    QuietRead.selectionCleared();
                    return;
                  }
                  QuietRead.selectionChanged(
                    text,
                    start.id,
                    start.path,
                    start.offset,
                    end.id,
                    end.path,
                    end.offset
                  );
                  const rect = visibleRangeRect(range);
                  if (rect) {
                    toolbarSelectionText = text;
                    toolbarAnnotation = null;
                    placeSelectionToolbar(rect, 'selection');
                  }
                }, 80);
              });

              const captureLocator = () => {
                const walker = document.createTreeWalker(flow, NodeFilter.SHOW_TEXT);
                for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                  if (!node.nodeValue || !node.nodeValue.trim()) continue;
                  const parent = node.parentElement;
                  if (!parent || parent.closest('#quietread-footnote-overlay')) continue;
                  const range = document.createRange();
                  range.selectNodeContents(node);
                  const visibleRects = Array.from(range.getClientRects()).filter((rect) =>
                    rect.right > 0 && rect.left < viewportWidth &&
                    rect.bottom > 0 && rect.top < viewportHeight &&
                    rect.width > 0 && rect.height > 0
                  );
                  if (!visibleRects.length) continue;
                  visibleRects.sort((a, b) => a.top - b.top || a.left - b.left);
                  const rect = visibleRects[0];
                  const x = Math.max(0, Math.min(viewportWidth - 1, rect.left + 1));
                  const y = Math.max(0, Math.min(viewportHeight - 1, rect.top + rect.height / 2));
                  const caretPosition = document.caretPositionFromPoint && document.caretPositionFromPoint(x, y);
                  const caretRange = !caretPosition && document.caretRangeFromPoint && document.caretRangeFromPoint(x, y);
                  const caretNode = caretPosition ? caretPosition.offsetNode : (caretRange && caretRange.startContainer);
                  const caretOffset = caretPosition ? caretPosition.offset : (caretRange ? caretRange.startOffset : 0);
                  const block = parent.closest('p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,figcaption,div,section,article') || parent;
                  const path = locatorPath(block);
                  if (!path) continue;
                  const offset = caretNode && caretNode.nodeType === Node.TEXT_NODE && block.contains(caretNode)
                    ? textOffsetWithin(block, caretNode, caretOffset)
                    : textOffsetWithin(block, node, 0);
                  return { id: block.id || '', path, offset };
                }
                return { id: '', path: '', offset: 0 };
              };

              const locatorLeft = (locator) => {
                if (!locator) return null;
                const element = locatorElement(locator.id, locator.path);
                if (!element) return null;
                let remaining = Math.max(0, Number(locator.offset) || 0);
                const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
                let lastText = null;
                for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                  lastText = node;
                  if (remaining < node.length) {
                    const range = document.createRange();
                    range.setStart(node, remaining);
                    range.setEnd(node, Math.min(node.length, remaining + 1));
                    const rect = Array.from(range.getClientRects()).find((candidate) => candidate.width > 0 && candidate.height > 0);
                    if (rect) return rect.left + window.scrollX;
                  }
                  remaining -= node.length;
                }
                if (lastText && lastText.length) {
                  const range = document.createRange();
                  range.setStart(lastText, lastText.length - 1);
                  range.setEnd(lastText, lastText.length);
                  const rect = range.getBoundingClientRect();
                  if (rect.width > 0 || rect.height > 0) return rect.left + window.scrollX;
                }
                const rect = element.getBoundingClientRect();
                return rect.left + window.scrollX;
              };

              const pageForLocator = (locator) => {
                const left = locatorLeft(locator);
                return left == null ? null : Math.max(0, Math.min(totalPages - 1, Math.floor(left / viewportWidth)));
              };

              const reportPage = (relayout) => requestAnimationFrame(() => {
                const locator = captureLocator();
                if (relayout) {
                  QuietRead.relayout(totalPages, currentPage, locator.id, locator.path, locator.offset);
                } else {
                  QuietRead.pageChanged(currentPage, locator.id, locator.path, locator.offset);
                }
              });

              const applyPage = (report) => {
                window.scrollTo(currentPage * viewportWidth, 0);
                if (report) reportPage(false);
              };

              const measure = (preservePosition) => {
                const previousTotal = totalPages;
                const previousPage = currentPage;
                const preservedLocator = preservePosition ? captureLocator() : null;
                viewportWidth = Math.max(1, document.documentElement.clientWidth || window.innerWidth);
                viewportHeight = Math.max(1, document.documentElement.clientHeight || window.innerHeight);
                document.documentElement.style.setProperty('--qr-page-height', `${'$'}{viewportHeight}px`);
                footnotePanel.style.setProperty('max-height', `${'$'}{Math.max(160, Math.floor(viewportHeight * 0.62))}px`, 'important');
                window.scrollTo(0, 0);
                pageTail.style.setProperty('left', '0px', 'important');
                flow.style.setProperty('width', `${'$'}{viewportWidth}px`, 'important');
                flow.style.setProperty('height', `${'$'}{viewportHeight}px`, 'important');
                flow.style.setProperty('column-width', `${'$'}{Math.max(1, viewportWidth - 40)}px`, 'important');
                flow.style.setProperty('column-gap', '40px', 'important');
                void flow.offsetWidth;
                totalPages = Math.max(1, Math.ceil(Math.max(
                  flow.scrollWidth,
                  document.documentElement.scrollWidth,
                  document.body.scrollWidth
                ) / viewportWidth));
                pageTail.style.setProperty('left', `${'$'}{totalPages * viewportWidth - 1}px`, 'important');
                void document.documentElement.offsetWidth;
                const anchoredPage = preservePosition ? pageForLocator(preservedLocator) : null;
                currentPage = anchoredPage == null
                  ? (preservePosition
                    ? Math.min(totalPages - 1, Math.floor((previousPage / Math.max(1, previousTotal)) * totalPages))
                    : 0)
                  : anchoredPage;
                applyPage(false);
                if (initialized) reportPage(true);
                else {
                  initialized = true;
                  QuietRead.ready(totalPages);
                }
              };

              window.qrGoPage = (page) => {
                currentPage = Math.max(0, Math.min(totalPages - 1, Number(page) || 0));
                applyPage(true);
              };
              window.qrGoLocator = (id, path, offset, fallbackPage) => {
                const targetPage = pageForLocator({ id, path, offset });
                window.qrGoPage(targetPage == null ? fallbackPage : targetPage);
              };
              window.qrGoAnchor = (id) => {
                const target = document.getElementById(id) || document.querySelector(`[name="${'$'}{CSS.escape(id)}"]`);
                if (!target) { window.qrGoPage(0); return; }
                const left = target.getBoundingClientRect().left + window.scrollX;
                window.qrGoPage(Math.floor(left / viewportWidth));
              };
              const bookmarkPullThreshold = () => Math.max(72, viewportHeight * 0.10);
              const updateBookmarkPull = (distance) => {
                const offset = Math.min(92, Math.max(0, distance) * 0.52);
                const progress = Math.min(1, offset / (bookmarkPullThreshold() * 0.52));
                const base = touchStart && touchStart.baseTransform ? `${'$'}{touchStart.baseTransform} ` : '';
                flow.style.setProperty('transition', 'none', 'important');
                flow.style.setProperty('transform', `${'$'}{base}translate3d(0, ${'$'}{offset}px, 0)`, 'important');
                pullIndicator.style.setProperty('opacity', String(Math.min(1, progress * 1.25)), 'important');
                pullIndicator.style.setProperty('transform', `scale(${'$'}{0.72 + progress * 0.34})`, 'important');
              };
              const resetBookmarkPull = (gesture) => {
                const baseTransform = gesture && gesture.baseTransform ? gesture.baseTransform : '';
                const baseTransition = gesture && gesture.baseTransition ? gesture.baseTransition : '';
                flow.style.setProperty('transition', 'transform 190ms cubic-bezier(.2,.8,.2,1)', 'important');
                flow.style.setProperty('transform', baseTransform || 'translate3d(0, 0, 0)', 'important');
                pullIndicator.style.setProperty('opacity', '0', 'important');
                pullIndicator.style.setProperty('transform', 'scale(0.72)', 'important');
                window.setTimeout(() => {
                  if (baseTransition) flow.style.setProperty('transition', baseTransition);
                  else flow.style.removeProperty('transition');
                  if (baseTransform) flow.style.setProperty('transform', baseTransform);
                  else flow.style.removeProperty('transform');
                }, 210);
              };
              document.addEventListener('touchstart', (event) => {
                const target = event.target;
                if (event.touches.length !== 1 ||
                    (target.closest && target.closest('#quietread-footnote-overlay, #quietread-selection-toolbar'))) {
                  touchStart = null;
                  return;
                }
                const touch = event.touches[0];
                touchStart = {
                  x: touch.clientX,
                  y: touch.clientY,
                  time: Date.now(),
                  pulling: false,
                  baseTransform: flow.style.transform || '',
                  baseTransition: flow.style.transition || ''
                };
              }, { passive: true });
              document.addEventListener('touchmove', (event) => {
                if (!touchStart || event.touches.length !== 1) return;
                const touch = event.touches[0];
                const dx = touch.clientX - touchStart.x;
                const dy = touch.clientY - touchStart.y;
                const liveSelection = window.getSelection();
                if (liveSelection && !liveSelection.isCollapsed) return;
                if (dy > 8 && Math.abs(dy) > Math.abs(dx) * 1.15) {
                  event.preventDefault();
                  touchStart.pulling = true;
                  updateBookmarkPull(dy);
                } else if (Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy)) {
                  event.preventDefault();
                }
              }, { passive: false });
              document.addEventListener('touchend', (event) => {
                if (!touchStart || event.changedTouches.length !== 1) {
                  touchStart = null;
                  return;
                }
                const touch = event.changedTouches[0];
                const dx = touch.clientX - touchStart.x;
                const dy = touch.clientY - touchStart.y;
                const elapsed = Date.now() - touchStart.time;
                const threshold = Math.max(48, viewportWidth * 0.12);
                const gesture = touchStart;
                touchStart = null;
                if (gesture.pulling) resetBookmarkPull(gesture);
                if (elapsed <= 900 && Math.abs(dx) >= threshold && Math.abs(dx) > Math.abs(dy) * 1.2) {
                  event.preventDefault();
                  suppressClickUntil = Date.now() + 500;
                  QuietRead.turnPage(dx < 0 ? 1 : -1);
                } else if (elapsed <= 900 && dy >= bookmarkPullThreshold() && Math.abs(dy) > Math.abs(dx) * 1.2) {
                  event.preventDefault();
                  suppressClickUntil = Date.now() + 500;
                  QuietRead.toggleBookmark();
                }
              }, { passive: false });
              document.addEventListener('touchcancel', () => {
                const gesture = touchStart;
                touchStart = null;
                if (gesture && gesture.pulling) resetBookmarkPull(gesture);
              }, { passive: true });
              document.addEventListener('click', (event) => {
                const toolbar = event.target.closest && event.target.closest('#quietread-selection-toolbar');
                if (toolbar) return;
                if (Date.now() < suppressClickUntil) {
                  event.preventDefault();
                  event.stopPropagation();
                  return;
                }
                const liveSelection = window.getSelection();
                if (liveSelection && !liveSelection.isCollapsed) {
                  event.preventDefault();
                  event.stopPropagation();
                  return;
                }
                const highlighted = highlightAtPoint(event.clientX, event.clientY);
                if (highlighted) {
                  event.preventDefault();
                  event.stopPropagation();
                  toolbarSelectionText = highlighted.text;
                  toolbarAnnotation = highlighted;
                  const rect = Array.from(highlighted.range.getClientRects()).find((candidate) =>
                    event.clientX >= candidate.left - 4 && event.clientX <= candidate.right + 4 &&
                    event.clientY >= candidate.top - 4 && event.clientY <= candidate.bottom + 4
                  ) || visibleRangeRect(highlighted.range);
                  if (rect) placeSelectionToolbar(rect, 'annotation');
                  return;
                }
                hideSelectionToolbar();
                const overlay = event.target.closest && event.target.closest('#quietread-footnote-overlay');
                if (overlay) {
                  event.preventDefault();
                  event.stopPropagation();
                  if (event.target === footnoteOverlay ||
                      (event.target.closest && event.target.closest('#quietread-footnote-close'))) {
                    closeFootnote();
                  }
                  return;
                }
                const anchor = event.target.closest && event.target.closest('a[href]');
                event.preventDefault();
                if (anchor && showFootnote(anchor)) return;
                if (anchor) QuietRead.openLink(anchor.getAttribute('href'));
                else QuietRead.tapped(event.clientX / viewportWidth);
              }, true);
              document.addEventListener('keydown', (event) => {
                if (event.key === 'Escape' && footnoteOverlay.dataset.open === 'true') {
                  event.preventDefault();
                  closeFootnote();
                }
              });
              window.addEventListener('resize', () => {
                window.clearTimeout(resizeTimer);
                resizeTimer = window.setTimeout(() => measure(true), 100);
              });

              const documentLoaded = new Promise((resolve) => {
                if (document.readyState === 'complete') resolve();
                else window.addEventListener('load', resolve, { once: true });
              });
              const fontsLoaded = document.fonts && document.fonts.ready
                ? document.fonts.ready.catch(() => {})
                : Promise.resolve();
              Promise.all([documentLoaded, fontsLoaded])
                .then(() => requestAnimationFrame(() => measure(false)));
            })();
        """.trimIndent()
    }
}

@Composable
fun EpubReaderView(
    epub: EpubPackage,
    initialSpineIndex: Int,
    initialSpineProgress: Float,
    initialLocator: ReadingLocator?,
    settings: ReaderSettings,
    annotations: List<BookAnnotation>,
    modifier: Modifier = Modifier,
    onController: (ReaderController) -> Unit,
    onStateChanged: (ReaderRenderState) -> Unit,
    onCenterTap: () -> Unit,
    onFootnoteOpened: () -> Unit,
    onBookmarkGesture: () -> Unit,
    onSelectionChanged: (ReadingSelection?) -> Unit,
    onHighlightRequested: (ReadingSelection) -> Unit,
    onThoughtRequested: (ReadingSelection) -> Unit,
    onAnnotationDeleteRequested: (String) -> Unit,
    onPositionChanged: (ReadingPosition) -> Unit,
) {
    val controller = remember(epub) {
        ReaderController(epub, initialSpineIndex, initialSpineProgress, initialLocator, settings, annotations)
    }
    controller.onStateChanged = onStateChanged
    controller.onCenterTap = onCenterTap
    controller.onFootnoteOpened = onFootnoteOpened
    controller.onBookmarkGesture = onBookmarkGesture
    controller.onSelectionChanged = onSelectionChanged
    controller.onHighlightRequested = onHighlightRequested
    controller.onThoughtRequested = onThoughtRequested
    controller.onAnnotationDeleteRequested = onAnnotationDeleteRequested
    controller.onPositionChanged = onPositionChanged
    SideEffect { onController(controller) }

    AndroidView(
        factory = controller::createWebView,
        modifier = modifier,
        update = {
            controller.updateSettings(settings)
            controller.updateAnnotations(annotations)
        },
    )
    DisposableEffect(controller) {
        onDispose(controller::destroy)
    }
}
