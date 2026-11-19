package app.belle.assistant.book

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** EPUB 2/3 reader: container.xml → OPF (metadata, manifest, spine) → nav.xhtml or toc.ncx titles → spine XHTML as text. */
object Epub {
    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) m[e.name] = z.readBytes() } }
        return m
    }

    private fun attr(tag: String, name: String) = Regex("\\b$name\\s*=\\s*([\"'])(.*?)\\1", RegexOption.DOT_MATCHES_ALL).find(tag)?.groupValues?.get(2)
    private fun resolve(base: String, href: String): String {
        val h = java.net.URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
        val parts = (if (base.isEmpty()) h else "$base/$h").split('/').fold(ArrayList<String>()) { acc, p -> when (p) { "", "." -> {}; ".." -> if (acc.isNotEmpty()) acc.removeAt(acc.size - 1); else -> acc += p }; acc }
        return parts.joinToString("/")
    }
    private fun dir(path: String) = path.substringBeforeLast('/', "")

    fun parse(bytes: ByteArray, fallbackTitle: String = "Book"): BookDoc {
        val files = unzip(bytes); val out = ArrayList<Chapter>()
        val (t, a) = parse({ files[it] }, fallbackTitle) { out += it }
        return BookDoc(t, a, "epub", out)
    }

    /** Opens the EPUB file in place (zip central directory; one spine document read at a time). */
    fun parse(file: java.io.File, fallbackTitle: String, emit: (Chapter) -> Unit): Pair<String, String> =
        java.util.zip.ZipFile(file).use { z -> parse({ n -> z.getEntry(n)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }, fallbackTitle, emit) }

    /** Streaming: [read] returns one file of the archive; chapters go to [emit] one at a time. Returns (title, author). */
    fun parse(read: (String) -> ByteArray?, fallbackTitle: String, emit: (Chapter) -> Unit): Pair<String, String> {
        fun text(p: String) = read(p)?.let { String(it, Charsets.UTF_8) }
        val container = text("META-INF/container.xml") ?: error("Not an EPUB (no META-INF/container.xml)")
        val opfPath = Regex("<rootfile\\b[^>]*>").findAll(container).mapNotNull { attr(it.value, "full-path") }.firstOrNull() ?: error("EPUB has no package file")
        val opf = text(opfPath) ?: error("EPUB package file missing")
        val base = dir(opfPath)
        val title = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(opf)?.groupValues?.get(1)?.let(Html::decode)?.trim()?.ifEmpty { null } ?: fallbackTitle
        val author = Regex("<dc:creator[^>]*>(.*?)</dc:creator>", RegexOption.DOT_MATCHES_ALL).find(opf)?.groupValues?.get(1)?.let(Html::decode)?.trim().orEmpty()
        data class Item(val id: String, val href: String, val type: String, val props: String)
        val items = Regex("<item\\b[^>]*>").findAll(opf).map { m -> Item(attr(m.value, "id").orEmpty(), resolve(base, attr(m.value, "href").orEmpty()), attr(m.value, "media-type").orEmpty(), attr(m.value, "properties").orEmpty()) }.toList()
        val byId = items.associateBy { it.id }
        val spineTag = Regex("<spine\\b[^>]*>").find(opf)?.value.orEmpty()
        val spine = Regex("<itemref\\b[^>]*>").findAll(opf).filter { attr(it.value, "linear") != "no" }.mapNotNull { byId[attr(it.value, "idref")] }.toList()
        // guide/landmark types (EPUB 2 <guide>, EPUB 3 nav landmarks) mark cover / copyright / toc pages
        val guide = HashMap<String, String>()
        Regex("<reference\\b[^>]*>").findAll(opf).forEach { r -> val h = attr(r.value, "href") ?: return@forEach; guide[resolve(base, h)] = attr(r.value, "type").orEmpty().lowercase() }

        // table of contents: href (no fragment) → first title
        val toc = LinkedHashMap<String, String>()
        items.firstOrNull { it.props.split(' ').contains("nav") }?.let { nav -> text(nav.href)?.let { h ->
            val navBlock = Regex("<nav\\b[^>]*(epub:)?type=\"[^\"]*toc[^\"]*\"[^>]*>(.*?)</nav>", RegexOption.DOT_MATCHES_ALL).find(h)?.groupValues?.get(2) ?: h
            Regex("<a\\b([^>]*)>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(navBlock).forEach { a ->
                val href = attr(a.groupValues[1], "href") ?: return@forEach
                toc.putIfAbsent(resolve(dir(nav.href), href), Html.decode(a.groupValues[2].replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()) }
            Regex("<nav\\b[^>]*type=\"[^\"]*landmarks[^\"]*\"[^>]*>(.*?)</nav>", RegexOption.DOT_MATCHES_ALL).find(h)?.groupValues?.get(1)?.let { lm ->
                Regex("<a\\b([^>]*)>").findAll(lm).forEach { a -> val href = attr(a.groupValues[1], "href") ?: return@forEach; guide[resolve(dir(nav.href), href)] = (attr(a.groupValues[1], "epub:type") ?: "").lowercase() } }
        } }
        if (toc.isEmpty()) (attr(spineTag, "toc")?.let { byId[it] } ?: items.firstOrNull { it.type == "application/x-dtbncx+xml" })?.let { ncx -> text(ncx.href)?.let { x ->
            Regex("<navPoint\\b.*?<text>(.*?)</text>.*?<content\\b([^>]*)>", RegexOption.DOT_MATCHES_ALL).findAll(x).forEach { m ->
                val src = attr(m.groupValues[2], "src") ?: return@forEach
                toc.putIfAbsent(resolve(dir(ncx.href), src), Html.decode(m.groupValues[1]).replace(Regex("\\s+"), " ").trim()) } } }

        // one pending section at a time: a chapter split over several files is merged before it is classified
        val asm = BookText.Assembler(emit)
        var pending: Triple<String, List<String>, Kind?>? = null
        var n = 0
        for (it in spine) {
            if (!it.type.contains("html")) continue
            val html = text(it.href) ?: continue
            val paras = Html.paragraphs(html)
            val type = (guide[it.href].orEmpty() + " " + Html.epubType(html)).lowercase()
            val hint = when {
                Regex("cover|titlepage|title-page|copyright|toc|dedication|epigraph|frontmatter|halftitle|imprint|loi|lot").containsMatchIn(type) && !type.contains("bodymatter") -> Kind.FRONT
                Regex("backmatter|acknowledg|appendix|colophon|index|bibliography|glossary|endnotes|footnotes|rearnotes").containsMatchIn(type) -> Kind.BACK
                Regex("bodymatter|chapter|part|prologue|epilogue").containsMatchIn(type) -> Kind.CHAPTER
                else -> null
            }
            val t = toc[it.href]
            val p = pending
            if (t == null && p != null && hint == null) { pending = Triple(p.first, p.second + paras, p.third); continue }   // a chapter split over several files
            p?.let { asm.section(it.first, it.second, it.third) }
            n++
            pending = Triple(t ?: Html.firstHeading(html) ?: paras.firstOrNull { it.isNotBlank() && it.length < 60 } ?: "Section $n", paras, hint)
        }
        pending?.let { asm.section(it.first, it.second, it.third) }
        return Html.decode(title) to author
    }
}

/** Minimal XHTML → text: block elements become paragraphs, entities decoded, scripts/styles/footnote numbers dropped. */
object Html {
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "shy" to "", "copy" to "©", "eacute" to "é", "egrave" to "è",
        "aacute" to "á", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß",
        "ccedil" to "ç", "thinsp" to " ", "ensp" to " ", "emsp" to " ", "zwnj" to "", "zwj" to "", "bull" to "•", "middot" to "·", "deg" to "°", "times" to "×")
    fun decode(s: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when { e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: ""
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: ""
            else -> named[e] ?: m.value }
    }
    fun epubType(html: String) = Regex("<(body|section|div)\\b[^>]*epub:type=\"([^\"]+)\"").findAll(html).take(2).joinToString(" ") { it.groupValues[2] }
    fun firstHeading(html: String) = Regex("<h[1-3]\\b[^>]*>(.*?)</h[1-3]>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        ?.replace(Regex("<[^>]+>"), " ")?.let(::decode)?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }
    private val block = Regex("</?(p|div|h[1-6]|li|ul|ol|blockquote|section|article|header|footer|aside|tr|table|figure|figcaption|pre|hr|dd|dt)\\b[^>]*>|<br\\s*/?>", RegexOption.IGNORE_CASE)
    fun paragraphs(html: String): List<String> {
        var h = html.replace(Regex("(?is)<head\\b.*?</head>|<script\\b.*?</script>|<style\\b.*?</style>|<!--.*?-->"), " ")
        h = h.replace(Regex("(?is)<sup\\b[^>]*>\\s*(<a\\b[^>]*>)?\\s*[0-9*†‡]{1,3}\\s*(</a>)?\\s*</sup>"), "")    // note references
        h = h.replace(Regex("(?i)<hr\\b[^>]*>"), "\n\n•\n\n")
        h = block.replace(h, "\n\n")
        h = h.replace(Regex("<[^>]+>"), "")
        return decode(h).split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
            .map { if (Regex("^[\\s•*·#~—–_=+.○◆◇♦❖✦✧⁂]+$").matches(it)) "" else it }
    }
}
