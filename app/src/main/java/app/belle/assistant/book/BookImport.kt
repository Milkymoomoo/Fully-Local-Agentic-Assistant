package app.belle.assistant.book

import android.content.Context
import android.net.Uri
import android.os.Debug
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Turns a picked EPUB / PDF / TXT into a library book, fully offline, without ever holding the whole book in memory:
 * the file is copied to app cache as a stream; a PDF is opened with temp-file scratch storage and its pages are
 * extracted one at a time to a page cache on disk; chapters are then built and written one at a time.
 */
object BookImport {
    class Unsupported(msg: String) : Exception(msg)

    /** Peak memory seen while a job ran (sampled every 100 ms): PSS of the whole app, Java heap in use, native heap. */
    data class Mem(val peakPssKb: Long, val peakJavaKb: Long, val peakNativeKb: Long, val startPssKb: Long) {
        override fun toString() = "peak PSS ${peakPssKb / 1024} MB (was ${startPssKb / 1024} MB before), Java heap ${peakJavaKb / 1024} MB, native heap ${peakNativeKb / 1024} MB"
    }
    class MemSampler(private val periodMs: Long = 100) : AutoCloseable {
        @Volatile private var run = true
        private val start = Debug.getPss()
        @Volatile var pss = start; @Volatile var java = 0L; @Volatile var native = 0L
        private val t = Thread {
            while (run) {
                val rt = Runtime.getRuntime()
                java = maxOf(java, (rt.totalMemory() - rt.freeMemory()) / 1024)
                native = maxOf(native, Debug.getNativeHeapAllocatedSize() / 1024)
                pss = maxOf(pss, Debug.getPss())
                Thread.sleep(periodMs)
            }
        }.apply { isDaemon = true; name = "book-mem"; start() }
        fun result() = Mem(pss, java, native, start)
        override fun close() { run = false; t.join(500) }
    }

    data class Result(val entry: Library.Entry, val mem: Mem, val ms: Long)

    fun displayName(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Book"

    fun import(ctx: Context, lib: Library, uri: Uri, progress: (String) -> Unit = {}): Result {
        val name = displayName(ctx, uri)
        val tmp = File(ctx.cacheDir, "book-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            val src = File(tmp, "source")
            (ctx.contentResolver.openInputStream(uri) ?: throw Unsupported("Couldn't open that file")).use { i -> src.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            return importFile(ctx, lib, src, name, progress, tmp)
        } finally { tmp.deleteRecursively() }
    }

    /** [work]: scratch directory (deleted by the caller); without one, a scratch dir is made and always deleted here. */
    fun importFile(ctx: Context, lib: Library, f: File, name: String = f.name, progress: (String) -> Unit = {}, work: File? = null): Result {
        // page caches of imports that were killed half way hold the book's text: remove them (older than an hour)
        ctx.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("book-import-") && it != work &&
            System.currentTimeMillis() - it.lastModified() > 3_600_000L }?.forEach { it.deleteRecursively() }
        if (work != null) return importIn(ctx, lib, f, name, progress, work)
        val scratch = File(ctx.cacheDir, "book-import-${System.nanoTime()}").apply { mkdirs() }
        try { return importIn(ctx, lib, f, name, progress, scratch) } finally { scratch.deleteRecursively() }
    }

    private fun importIn(ctx: Context, lib: Library, f: File, name: String, progress: (String) -> Unit, scratch: File): Result {
        val t0 = System.currentTimeMillis()
        val base = name.substringBeforeLast('.')
        val head = f.inputStream().use { i -> ByteArray(4096).let { b -> val n = i.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
        val w = lib.Writer(name)
        MemSampler().use { mem ->
            try {
                val (title, author, format) = when {
                    head.size > 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "%PDF" -> pdf(ctx, f, base, scratch, w, progress)
                    head.size > 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> { progress("Reading the EPUB…"); val (t, a) = Epub.parse(f, base, w::chapter); Triple(t, a, "epub") }
                    name.endsWith(".txt", true) || looksLikeText(head) -> { progress("Reading the text…"); val cs = charsetOf(f)
                        BookText.fromTxtLines(base, { f.bufferedReader(cs) }, w::chapter); Triple(base, "", "txt") }
                    else -> throw Unsupported("That isn't an EPUB, PDF or text file")
                }
                if (w.count == 0) throw Unsupported("I couldn't find any readable text in that file (is it a scanned PDF without a text layer, or DRM-protected?)")
                val e = w.finish(title, author, format)
                if (e.readable.isEmpty()) { lib.delete(e.id); throw Unsupported("I couldn't find any readable chapters in that file") }
                return Result(e, mem.result(), System.currentTimeMillis() - t0)
            } catch (e: Throwable) { w.abort(); throw e }
        }
    }

    private fun pdf(ctx: Context, f: File, base: String, scratch: File, w: Library.Writer, progress: (String) -> Unit): Triple<String, String, String> {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val pagesDir = File(scratch, "pages").apply { mkdirs() }
        var title = base; var author = ""; var n = 0
        val toc = ArrayList<TocEntry>()
        // pass 1: one page at a time to the disk cache (PDF objects spill to temp files, not RAM)
        PDDocument.load(f, MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)).use { d ->
            if (d.isEncrypted) runCatching { d.setAllSecurityToBeRemoved(true) }
            fun walk(node: PDOutlineNode, depth: Int) {
                if (depth > 6) return
                var it = node.firstChild
                while (it != null) {
                    val p = runCatching { it.findDestinationPage(d)?.let { pg -> d.pages.indexOf(pg) } }.getOrNull() ?: -1
                    if (p >= 0) toc += TocEntry(it.title ?: "", p)
                    walk(it, depth + 1); it = it.nextSibling
                }
            }
            runCatching { d.documentCatalog.documentOutline?.let { walk(it, 0) } }
            val s = PDFTextStripper().apply { paragraphStart = BookText.PARA.toString(); sortByPosition = false }
            n = d.numberOfPages
            for (i in 1..n) {
                if (i % 20 == 1) progress("Reading page $i of $n…")
                s.startPage = i; s.endPage = i
                File(pagesDir, "$i").writeText(s.getText(d))
            }
            d.documentInformation?.let { info ->
                info.title?.takeIf { it.isNotBlank() && !it.contains("untitled", true) && !it.endsWith(".pdf", true) }?.let { title = it }
                author = info.author.orEmpty()
            }
        }
        // pass 2: chapters, reading pages back from the cache
        progress("Cleaning up the text…")
        BookText.fromPdfPages(n, toc, { i -> File(pagesDir, "${i + 1}").readText() }, w::chapter)
        return Triple(title, author, "pdf")
    }

    private fun looksLikeText(b: ByteArray): Boolean { var bad = 0; for (x in b) { val c = x.toInt() and 0xff; if (c < 9 || (c in 14..31)) bad++ }; return b.isNotEmpty() && bad * 50 < b.size }

    /** UTF-8 if the whole file decodes as UTF-8 (checked as a stream), else Windows-1252. */
    private fun charsetOf(f: File): java.nio.charset.Charset {
        val dec = Charsets.UTF_8.newDecoder()
        return try { java.io.InputStreamReader(f.inputStream(), dec).use { r -> val buf = CharArray(8192); while (r.read(buf) >= 0) {} }; Charsets.UTF_8 }
        catch (e: java.nio.charset.CharacterCodingException) { charset("windows-1252") }
    }
}
