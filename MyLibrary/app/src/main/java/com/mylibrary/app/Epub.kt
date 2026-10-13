package com.mylibrary.app

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class EpubInfo(
    val title: String,
    val author: String,
    /** Rutas (dentro del zip) de los capítulos, en orden de lectura. */
    val spine: List<String>,
    val chapterTitles: Map<Int, String>
)

/** Lector de EPUB propio, sin librerías externas: un .epub es un zip con XHTML. */
object Epub {
    private fun parser(i: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(i, null)
    }

    /** Normaliza rutas y elimina "..": evita salirse del zip (path traversal). */
    fun norm(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> { if (out.isNotEmpty()) out.removeAt(out.lastIndex) }
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "xhtml", "html", "htm" -> "text/html"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    fun parse(file: File): EpubInfo? = try {
        ZipFile(file).use { parseZip(it) }
    } catch (e: Exception) {
        null
    }

    private fun parseZip(zip: ZipFile): EpubInfo? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        var rootPath: String? = null
        zip.getInputStream(container).use { s ->
            val p = parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name.substringAfter(':') == "rootfile") {
                    rootPath = p.getAttributeValue(null, "full-path")
                    break
                }
                ev = p.next()
            }
        }
        val opf = rootPath?.let { norm(Uri.decode(it)) } ?: return null
        val dir = opf.substringBeforeLast('/', "")
        val opfEntry = zip.getEntry(opf) ?: return null

        val manifest = HashMap<String, Pair<String, String>>() // id -> (href, media-type)
        val ids = ArrayList<String>()
        var title = ""
        var author = ""
        var ncxId: String? = null
        var navHref: String? = null

        zip.getInputStream(opfEntry).use { s ->
            val p = parser(s)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name.substringAfter(':')) {
                        "title" -> { if (title.isEmpty()) title = p.nextText().trim() }
                        "creator" -> { if (author.isEmpty()) author = p.nextText().trim() }
                        "item" -> {
                            val id = p.getAttributeValue(null, "id")
                            val href = p.getAttributeValue(null, "href")
                            if (id != null && href != null) {
                                manifest[id] = href to (p.getAttributeValue(null, "media-type") ?: "")
                                if ((p.getAttributeValue(null, "properties") ?: "").split(' ').contains("nav")) navHref = href
                            }
                        }
                        "spine" -> ncxId = p.getAttributeValue(null, "toc")
                        "itemref" -> p.getAttributeValue(null, "idref")?.let { ids.add(it) }
                    }
                }
                ev = p.next()
            }
        }

        fun resolve(href: String): String =
            norm((if (dir.isEmpty()) "" else "$dir/") + Uri.decode(href.substringBefore('#')))

        val spine = ids.mapNotNull { id ->
            manifest[id]?.takeIf { it.second.contains("html") }?.let { resolve(it.first) }
        }.filter { zip.getEntry(it) != null }

        // Títulos de capítulo desde el índice NCX (si existe)
        val titles = HashMap<Int, String>()
        val ncxHref = ncxId?.let { manifest[it]?.first }
            ?: manifest.values.firstOrNull { it.second.contains("ncx") }?.first
        if (ncxHref != null) {
            val e = zip.getEntry(resolve(ncxHref))
            if (e != null) {
                try {
                    zip.getInputStream(e).use { s ->
                        val p = parser(s)
                        var ev = p.eventType
                        var label = ""
                        while (ev != XmlPullParser.END_DOCUMENT) {
                            if (ev == XmlPullParser.START_TAG) {
                                when (p.name.substringAfter(':')) {
                                    "text" -> label = p.nextText().trim()
                                    "content" -> {
                                        val src = p.getAttributeValue(null, "src")
                                        if (src != null && label.isNotEmpty()) {
                                            val idx = spine.indexOf(resolve(src))
                                            if (idx >= 0) titles.putIfAbsent(idx, label)
                                        }
                                        label = ""
                                    }
                                }
                            }
                            ev = p.next()
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        // Índice EPUB3 (nav.xhtml) cuando no hay NCX utilizable
        val nh = navHref
        if (titles.isEmpty() && nh != null) {
            val navPath = resolve(nh)
            val ne = zip.getEntry(navPath)
            if (ne != null) {
                val navDir = navPath.substringBeforeLast('/', "")
                try {
                    zip.getInputStream(ne).use { st ->
                        val p = parser(st)
                        var ev = p.eventType
                        while (ev != XmlPullParser.END_DOCUMENT) {
                            if (ev == XmlPullParser.START_TAG && p.name.substringAfter(':') == "a") {
                                val href = p.getAttributeValue(null, "href")
                                val sb = StringBuilder()
                                var depth = 1
                                while (depth > 0) {
                                    ev = p.next()
                                    when (ev) {
                                        XmlPullParser.START_TAG -> depth++
                                        XmlPullParser.END_TAG -> depth--
                                        XmlPullParser.TEXT -> sb.append(p.text)
                                        XmlPullParser.END_DOCUMENT -> depth = 0
                                        else -> {}
                                    }
                                }
                                val label = sb.toString().replace(Regex("\\s+"), " ").trim()
                                if (href != null && label.isNotEmpty()) {
                                    val full = norm((if (navDir.isEmpty()) "" else "$navDir/") + Uri.decode(href.substringBefore('#')))
                                    val idx = spine.indexOf(full)
                                    if (idx >= 0) titles.putIfAbsent(idx, label)
                                }
                            }
                            ev = p.next()
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        return EpubInfo(title, author, spine, titles)
    }

    // ---------------------------------------------------------------------
    // Lectura continua: muchos EPUB parten el libro en decenas o cientos de
    // archivos diminutos (a veces uno por párrafo). Aquí se agrupan los archivos
    // consecutivos en "secciones" y cada sección se sirve como UN solo documento,
    // para que el texto fluya seguido y no se corte página a página.
    // ---------------------------------------------------------------------
    const val HOST = "epub.local"
    private const val SECTION_BYTES = 120_000L // respaldo si el libro no trae índice
    const val MERGED_PREFIX = "__ml/"

    private val OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val BODY = Regex("<body[^>]*>(.*)</body>", OPTS)
    private val HEAD = Regex("<head[^>]*>(.*?)</head>", OPTS)
    private val STYLE = Regex("<style[^>]*>.*?</style>", OPTS)
    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val MEDIA_TAG = Regex("<(?:img|image|source|video|audio|link)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""(\b(?:src|href|xlink:href|poster)\s*=\s*)(["'])(.*?)\2""", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val XML_DECL = Regex("<\\?xml[^>]*\\?>|<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE)

    fun url(path: String): String =
        "https://$HOST/" + path.split('/').joinToString("/") { Uri.encode(it) }

    fun sizes(zip: ZipFile, spine: List<String>): LongArray =
        LongArray(spine.size) { i -> zip.getEntry(spine[i])?.let { maxOf(0L, it.size) } ?: 0L }

    /** Agrupa archivos consecutivos hasta ~250 KB por sección. Devuelve índices de la spine. */
    fun group(sizes: LongArray, limit: Long = SECTION_BYTES): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var acc = 0L
        for (i in sizes.indices) {
            if (cur.isNotEmpty() && acc + sizes[i] > limit) {
                out.add(cur); cur = ArrayList(); acc = 0L
            }
            cur.add(i); acc += sizes[i]
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    data class Chapter(val title: String, val idxs: List<Int>)

    /**
     * Capítulos reales: cada entrada del índice del libro empieza un capítulo, y los
     * archivos diminutos se unen al siguiente. Dentro de un capítulo el texto es continuo.
     */
    fun chapters(info: EpubInfo, sizes: LongArray): List<Chapter> {
        val n = info.spine.size
        val raw = ArrayList<Chapter>()
        if (info.chapterTitles.size >= 2) {
            val starts = (info.chapterTitles.keys + 0).toSortedSet().toList()
            for ((k, st) in starts.withIndex()) {
                val end = if (k + 1 < starts.size) starts[k + 1] else n
                raw.add(Chapter(info.chapterTitles[st] ?: "Inicio", (st until end).toList()))
            }
        } else {
            group(sizes).forEachIndexed { k, g -> raw.add(Chapter("Parte ${k + 1}", g)) }
        }
        val out = ArrayList<Chapter>()
        var carry: List<Int> = emptyList()
        for ((k, c) in raw.withIndex()) {
            val all = carry + c.idxs
            if (k < raw.lastIndex && all.sumOf { sizes[it] } < 3000L) { carry = all; continue }
            out.add(Chapter(c.title, all))
            carry = emptyList()
        }
        return out
    }

    private fun fixRefs(tag: String, dir: String): String = ATTR.replace(tag) { m ->
        val v = m.groupValues[3]
        if (v.isBlank() || v.startsWith("#") || SCHEME.containsMatchIn(v)) {
            m.value
        } else {
            val frag = if (v.contains('#')) "#" + v.substringAfter('#') else ""
            val raw = Uri.decode(v.substringBefore('#').substringBefore('?'))
            val full = if (raw.startsWith("/")) norm(raw) else norm(if (dir.isEmpty()) raw else "$dir/$raw")
            m.groupValues[1] + m.groupValues[2] + url(full) + frag + m.groupValues[2]
        }
    }

    /** Une varios archivos del libro en un único documento HTML continuo. */
    fun merged(zip: ZipFile, spine: List<String>, idxs: List<Int>): String {
        val head = StringBuilder()
        val body = StringBuilder()
        val seen = HashSet<String>()
        for (i in idxs) {
            val path = spine.getOrNull(i) ?: continue
            val dir = path.substringBeforeLast('/', "")
            val e = zip.getEntry(path) ?: continue
            if (e.size > 20L * 1024 * 1024) continue
            val html = zip.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8)
            HEAD.find(html)?.groupValues?.get(1)?.let { h ->
                LINK.findAll(h).forEach { m ->
                    if (m.value.contains("stylesheet", ignoreCase = true)) {
                        val t = fixRefs(m.value, dir)
                        if (seen.add(t)) head.append(t)
                    }
                }
                STYLE.findAll(h).forEach { if (seen.add(it.value)) head.append(it.value) }
            }
            val inner = BODY.find(html)?.groupValues?.get(1) ?: html.replace(XML_DECL, "")
            body.append("<div class=\"mlsec\" id=\"s").append(i).append("\">")
                .append(MEDIA_TAG.replace(inner) { fixRefs(it.value, dir) })
                .append("</div>\n")
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">$head</head><body>$body</body></html>"
    }
}
