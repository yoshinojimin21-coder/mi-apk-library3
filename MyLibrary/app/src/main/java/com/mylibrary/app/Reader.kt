package com.mylibrary.app

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val HOST = Epub.HOST

private data class TocEntry(val label: String, val section: Int, val fraction: Float)

private fun chapterUrl(path: String) =
    "https://$HOST/" + path.split('/').joinToString("/") { Uri.encode(it) }

private fun buildCss(dark: Boolean, pct: Int): String {
    val bg = if (dark) "#1C1C1E" else "#FBF6EA"
    val fg = if (dark) "#E5E5E7" else "#2B2B2B"
    val link = if (dark) "#6CB4FF" else "#1F5FBF"
    return """
html{background:$bg !important;}
body{margin:0 auto !important;padding:24px 20px 72px 20px !important;box-sizing:border-box !important;max-width:46em !important;
 color:$fg !important;background:$bg !important;font-size:$pct% !important;line-height:1.7 !important;
 font-family:Georgia,'Times New Roman',serif !important;overflow-wrap:break-word;}
body *{color:inherit !important;background-color:transparent !important;max-width:100% !important;}
*{break-before:auto !important;break-after:auto !important;break-inside:auto !important;page-break-before:auto !important;page-break-after:auto !important;page-break-inside:auto !important;}
img,svg,image{max-width:100% !important;max-height:100vh !important;object-fit:contain;}
a{color:$link !important;}
p{margin:0 0 1em 0;text-align:left;}
h1,h2,h3,h4{text-align:left !important;line-height:1.3 !important;margin:1.4em 0 .6em 0 !important;}
.mlsec{display:block;}
"""
}

private fun inject(html: String, css: String): String {
    val tag = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>$css</style>"
    val i = html.indexOf("</head>", ignoreCase = true)
    return if (i >= 0) html.substring(0, i) + tag + html.substring(i) else tag + html
}

private class EpubClient(val zip: ZipFile, val spine: List<String>, val sections: List<List<Int>>) : WebViewClient() {
    private fun empty(code: Int, msg: String) =
        WebResourceResponse("text/plain", "UTF-8", code, msg, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val u = request.url
        // Seguridad: solo se sirve contenido del propio libro. Todo lo demás se bloquea.
        if (u.scheme != "https" || u.host != HOST) return empty(403, "Forbidden")
        val path = Epub.norm(Uri.decode(u.encodedPath ?: "").trimStart('/'))
        if (path.startsWith(Epub.MERGED_PREFIX.trimEnd('/') + "/")) {
            return try {
                val n = path.substringAfter('/').substringBefore('.').toIntOrNull()
                val group = sections.getOrNull(n ?: -1) ?: return empty(404, "Not found")
                val html = Epub.merged(zip, spine, group)
                val css = (view as? ReaderWebView)?.css ?: ""
                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(inject(html, css).toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) {
                empty(500, "Error")
            }
        }
        val entry = (try { zip.getEntry(path) } catch (e: Exception) { null }) ?: return empty(404, "Not found")
        val mime = Epub.mime(path)
        return try {
            if (mime == "text/html") {
                if (entry.size > 20L * 1024 * 1024) return empty(413, "Too large")
                val html = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
                val css = (view as? ReaderWebView)?.css ?: ""
                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(inject(html, css).toByteArray(Charsets.UTF_8)))
            } else {
                WebResourceResponse(mime, null, zip.getInputStream(entry))
            }
        } catch (e: Exception) {
            empty(500, "Error")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun onPageFinished(view: WebView, url: String?) {
        (view as? ReaderWebView)?.restore()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
}

/** WebView de lectura continua: el texto se desliza hacia abajo, como una novela web. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ReaderWebView(ctx: Context) : WebView(ctx) {
    @Volatile var css: String = ""
    var pendingFraction = 0f
    var onTapCenter: () -> Unit = {}
    var onScrollFraction: (Float) -> Unit = {}
    private var lastFraction = 0f
    private var ready = true
    private var loadId = 0

    private fun range(): Int = (computeVerticalScrollRange() - height).coerceAtLeast(1)
    var onShort: () -> Unit = {}
    fun fraction(): Float = (scrollY.toFloat() / range()).coerceIn(0f, 1f)

    private fun checkShort(id: Int) {
        for (d in longArrayOf(700, 2200)) postDelayed({
            if (id == loadId && contentHeight > 0 && computeVerticalScrollRange() - height <= 2) onShort()
        }, d)
    }

    /** Restaura la posición de lectura. Reintenta porque el texto tarda en maquetarse. */
    fun restore() {
        val id = ++loadId
        checkShort(id)
        if (pendingFraction <= 0f) {
            ready = true
            scrollTo(0, 0)
            return
        }
        ready = false
        val delays = longArrayOf(250, 800, 1800)
        delays.forEachIndexed { k, d ->
            postDelayed({
                if (id == loadId) {
                    scrollTo(0, (pendingFraction * range()).toInt())
                    if (k == delays.lastIndex) { ready = true; lastFraction = fraction() }
                }
            }, d)
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (ready) {
            lastFraction = fraction()
            onScrollFraction(lastFraction)
        }
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTapCenter(); return false }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (ow != 0 && w != ow) { // rotación: mantener el punto de lectura
            pendingFraction = lastFraction
            restore()
        }
    }
}

@Composable
fun ReaderScreen(book: Book, store: Store, dark: Boolean, onToggleTheme: () -> Unit, onClose: () -> Unit) {
    val a = LocalAqua.current
    val file = remember(book.id) { File(store.booksDir, book.file) }
    val info = remember(book.id) { Epub.parse(file) }
    if (info == null || info.spine.isEmpty()) {
        Box(Modifier.fillMaxSize().pinstripes(a).systemBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No se pudo abrir este libro.", color = a.text)
                Spacer(Modifier.height(12.dp))
                GlossButton(onClose) { Text("Volver", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    } else {
        ReaderContent(book, info, file, store, dark, onToggleTheme, onClose)
    }
}

@Composable
private fun ReaderContent(
    book: Book, info: EpubInfo, file: File, store: Store,
    dark: Boolean, onToggleTheme: () -> Unit, onClose: () -> Unit
) {
    val a = LocalAqua.current
    val zip = remember(book.id) { ZipFile(file) }
    DisposableEffect(book.id) { onDispose { try { zip.close() } catch (_: Exception) { } } }

    val sizes = remember(book.id) { Epub.sizes(zip, info.spine) }
    val chapters = remember(book.id) { Epub.chapters(info, sizes) }
    val sections = remember(book.id) { chapters.map { it.idxs } }
    val toc = remember(book.id) { chapters.mapIndexed { i, c -> TocEntry(c.title, i, 0f) } }
    val saved = remember(book.id) { store.getPosition(book.id) }
    var chapter by remember { mutableIntStateOf(saved.first.coerceIn(0, sections.lastIndex)) }
    var tick by remember { mutableIntStateOf(0) }
    val forcePending = remember { booleanArrayOf(false) }
    val lastSaved = remember { floatArrayOf(-1f) }
    val pending = remember { floatArrayOf(saved.second) }
    var fontPct by remember { mutableIntStateOf(store.fontPct) }
    var bars by remember { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var pageInfo by remember { mutableStateOf("") }
    var atEnd by remember { mutableStateOf(false) }
    val chapterNow by rememberUpdatedState(chapter)
    val go = { n: Int, frac: Float ->
        pending[0] = frac; forcePending[0] = true
        atEnd = false; pageInfo = ""
        chapter = n.coerceIn(0, sections.lastIndex); tick++
    }

    val bg = if (dark) Color(0xFF1C1C1E) else Color(0xFFFBF6EA)
    val fg = if (dark) Color(0xFFE5E5E7) else Color(0xFF2B2B2B)
    val css = remember(dark, fontPct) { buildCss(dark, fontPct) }

    LaunchedEffect(Unit) { delay(2500); bars = false }

    Box(Modifier.fillMaxSize().background(bg).systemBarsPadding()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                ReaderWebView(c).apply {
                    settings.apply {
                        javaScriptEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        blockNetworkLoads = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        textZoom = 100
                        setGeolocationEnabled(false)
                        javaScriptCanOpenWindowsAutomatically = false
                    }
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = true
                    overScrollMode = View.OVER_SCROLL_NEVER
                    setBackgroundColor(bg.toArgb())
                    webViewClient = EpubClient(zip, info.spine, sections)
                    onTapCenter = { bars = !bars }
                    onScrollFraction = { f ->
                        val txt = "Cap. ${chapterNow + 1}/${sections.size}  ·  ${(f * 100).toInt()} %"
                        if (pageInfo != txt) pageInfo = txt
                        val end = f >= 0.995f
                        if (atEnd != end) atEnd = end
                        if (abs(f - lastSaved[0]) > 0.0003f) {
                            store.savePosition(book.id, chapterNow, f)
                            lastSaved[0] = f
                        }
                    }
                    onShort = { atEnd = true; pageInfo = "Cap. ${chapterNow + 1}/${sections.size}" }
                }
            },
            update = { wv ->
                wv.setBackgroundColor(bg.toArgb())
                val key = "$chapter|$dark|$fontPct|$tick"
                val old = wv.tag as? String
                if (old != key) {
                    val sameChapter = old != null && old.substringBefore('|') == chapter.toString()
                    wv.pendingFraction = if (sameChapter && !forcePending[0]) wv.fraction() else pending[0]
                    forcePending[0] = false
                    wv.tag = key
                    wv.css = css
                    wv.loadUrl(chapterUrl(Epub.MERGED_PREFIX + "$chapter.html"))
                }
            }
        )

        if (!(bars || atEnd)) {
            Text(
                pageInfo, color = fg.copy(alpha = 0.5f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
            )
        }

        AnimatedVisibility(bars || atEnd, modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(a.barTop, a.barBottom)))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (chapter > 0) {
                    GlossButton({ go(chapter - 1, 0f) }, hPad = 0.dp, modifier = Modifier.width(112.dp)) {
                        Text("‹ Anterior", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else Spacer(Modifier.width(112.dp))
                Text(pageInfo, color = a.text, fontSize = 12.sp, maxLines = 1)
                if (chapter < sections.lastIndex) {
                    GlossButton({ go(chapter + 1, 0f) }, hPad = 0.dp, modifier = Modifier.width(112.dp)) {
                        Text("Siguiente ›", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else Spacer(Modifier.width(112.dp))
            }
        }

        AnimatedVisibility(bars, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(a.barTop, a.barBottom)))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GlossButton(onClose, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
                Text(
                    book.title, color = a.text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                GlossButton({
                    fontPct = (fontPct - 10).coerceAtLeast(80); store.fontPct = fontPct
                }, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Text("A−", color = Color.White, fontWeight = FontWeight.Bold)
                }
                GlossButton({
                    fontPct = (fontPct + 10).coerceAtMost(220); store.fontPct = fontPct
                }, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Text("A+", color = Color.White, fontWeight = FontWeight.Bold)
                }
                GlossButton({ showToc = true }, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Icon(Icons.Filled.Menu, null, tint = Color.White)
                }
                GlossButton(onToggleTheme, hPad = 0.dp, modifier = Modifier.width(38.dp)) {
                    Icon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, null, tint = Color.White)
                }
            }
        }
    }

    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            confirmButton = { TextButton({ showToc = false }) { Text("Cerrar") } },
            title = { Text("Capítulos") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(toc) { _, e ->
                        Text(
                            e.label,
                            fontWeight = if (e.section == chapter) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { go(e.section, e.fraction); showToc = false }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        )
    }
}
