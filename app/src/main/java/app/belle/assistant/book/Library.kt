package app.belle.assistant.book

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Books imported into "Read to me", kept only on this phone (shared by every assistant/profile):
 * files/books/<id>/book.json (title, author, chapter list) + per chapter a sentence file (one reading unit per line,
 * an empty line = paragraph break) and an offset index, so playback reads a few sentences at a time instead of
 * loading the book; pos.json = where you stopped.
 */
class Library(val root: File) {
    data class ChapterInfo(val title: String, val kind: Kind, val words: Int, val file: String, val sentences: Int)
    data class Entry(val id: String, val title: String, val author: String, val format: String, val source: String, val added: Long, val chapters: List<ChapterInfo>) {
        val readable get() = chapters.withIndex().filter { it.value.kind == Kind.CHAPTER }.map { it.index }
        /** How far [p] is through the readable text, 0..100 (by sentences). */
        fun percent(p: Position?): Int {
            if (p == null) return 0; if (p.finished) return 100
            val r = readable; val total = r.sumOf { chapters[it].sentences }.coerceAtLeast(1)
            val before = r.filter { it < p.chapter }.sumOf { chapters[it].sentences } + (if (p.chapter in r) p.sentence.coerceIn(0, chapters[p.chapter].sentences) else 0)
            return (100L * before / total).toInt().coerceIn(0, 100)
        }
        val words get() = chapters.filter { it.kind == Kind.CHAPTER }.sumOf { it.words }
    }
    /** Where reading stopped: chapter index (into all chapters) and sentence index within it. */
    data class Position(val chapter: Int, val sentence: Int, val updated: Long, val finished: Boolean = false)

    // an import that was killed half way leaves its temp dir behind: clean those up (not one still being written: < 1 h old)
    init { root.mkdirs(); root.listFiles()?.filter { it.isDirectory && it.name.startsWith("import-") && it.name.endsWith(".part") && System.currentTimeMillis() - it.lastModified() > 3_600_000L }?.forEach { it.deleteRecursively() } }

    fun list(): List<Entry> = (root.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.endsWith(".part") }.mapNotNull { get(it.name) }.sortedByDescending { lastUsed(it.id) }

    fun get(id: String): Entry? = runCatching {
        val j = JSONObject(File(root, "$id/book.json").readText())
        val cs = j.getJSONArray("chapters")
        Entry(id, j.getString("title"), j.optString("author"), j.optString("format"), j.optString("source"), j.optLong("added"),
            (0 until cs.length()).map { val c = cs.getJSONObject(it); ChapterInfo(c.getString("title"), Kind.valueOf(c.getString("kind")), c.optInt("words"), c.getString("file"), c.optInt("sentences")) })
    }.getOrNull()

    /** Streaming import: chapters are written to disk as they are extracted; nothing appears in the library until [finish]. */
    inner class Writer(private val source: String) {
        private val tmp = File(root, "import-${System.nanoTime()}.part").apply { deleteRecursively(); mkdirs() }
        private val cs = JSONArray()
        var count = 0; private set
        fun chapter(c: Chapter) {
            val f = "c%03d".format(count++)
            val units = BookText.sentences(c.text)
            writeSentences(File(tmp, "$f.txt"), File(tmp, "$f.idx"), units)
            cs.put(JSONObject().put("title", c.title).put("kind", c.kind.name).put("words", c.words).put("file", f).put("sentences", units.size))
        }
        fun finish(title: String, author: String, format: String): Entry {
            val id = (slug(title).ifEmpty { "book" }).take(40) + "-" + java.lang.Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36)
            File(tmp, "book.json").writeText(JSONObject().put("title", title).put("author", author).put("format", format).put("source", source)
                .put("added", System.currentTimeMillis()).put("chapters", cs).toString())
            check(tmp.renameTo(File(root, id))) { "couldn't save the book" }
            return get(id)!!
        }
        fun abort() { tmp.deleteRecursively() }
    }

    fun add(doc: BookDoc, source: String): Entry { val w = Writer(source); doc.chapters.forEach(w::chapter); return w.finish(doc.title, doc.author, doc.format) }

    /** Random access to one chapter's sentences; only the requested lines are read. */
    class Sentences(txt: File, idx: File) : AutoCloseable {
        private val offs: IntArray = DataInputStream(idx.inputStream().buffered()).use { d -> IntArray(d.readInt()) { d.readInt() } }
        private val raf = RandomAccessFile(txt, "r")
        val size get() = offs.size
        operator fun get(i: Int): String {
            val a = offs[i]; val b = if (i + 1 < offs.size) offs[i + 1] - 1 else raf.length().toInt() - 1
            val buf = ByteArray(maxOf(0, b - a)); raf.seek(a.toLong()); raf.readFully(buf); return String(buf, Charsets.UTF_8)
        }
        override fun close() { raf.close() }
    }

    fun sentences(id: String, chapter: Int): Sentences? { val c = get(id)?.chapters?.getOrNull(chapter) ?: return null; return Sentences(File(root, "$id/${c.file}.txt"), File(root, "$id/${c.file}.idx")) }

    /** Whole chapter text (tests / preview only; playback uses [sentences]). */
    fun text(id: String, chapter: Int): String = sentences(id, chapter)?.use { s -> (0 until s.size).joinToString(" ") { s[it].ifEmpty { "\n\n" } } }
        ?.replace(Regex(" ?\n\n ?"), "\n\n")?.trim().orEmpty()

    fun delete(id: String): Boolean = File(root, id).let { it.isDirectory && !id.contains('/') && it.deleteRecursively() }

    fun position(id: String): Position? = runCatching {
        val j = JSONObject(File(root, "$id/pos.json").readText()); Position(j.getInt("chapter"), j.getInt("sentence"), j.optLong("updated"), j.optBoolean("finished"))
    }.getOrNull()

    fun save(id: String, p: Position) {
        val f = File(root, "$id/pos.json"); if (!f.parentFile!!.isDirectory) return
        val tmp = File(f.path + ".tmp"); tmp.writeText(JSONObject().put("chapter", p.chapter).put("sentence", p.sentence).put("updated", p.updated).put("finished", p.finished).toString())
        tmp.renameTo(f)
    }

    private fun lastUsed(id: String) = position(id)?.updated ?: get(id)?.added ?: 0L

    /** The book a spoken title means ("read me treasure island" → "Robert Louis Stevenson: Treasure Island"), or null. */
    fun find(spoken: String): Entry? { val all = list(); return BookCommands.bestTitle(spoken, all.map { it.title })?.let { t -> all.firstOrNull { it.title == t } } }

    companion object {
        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        fun writeSentences(txt: File, idx: File, units: List<String>) {
            val offs = IntArray(units.size); var pos = 0
            txt.outputStream().buffered().use { o -> units.forEachIndexed { i, u -> offs[i] = pos; val b = (u.replace('\n', ' ') + "\n").toByteArray(Charsets.UTF_8); o.write(b); pos += b.size } }
            DataOutputStream(idx.outputStream().buffered()).use { d -> d.writeInt(offs.size); offs.forEach(d::writeInt) }
        }
    }
}
