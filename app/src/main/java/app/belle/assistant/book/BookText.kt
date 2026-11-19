package app.belle.assistant.book

/**
 * "Read to me" text pipeline, pure Kotlin (no Android types; unit-tested on the JVM).
 * Raw pages / documents → cleaned chapters: publisher boilerplate and front matter out, running headers/footers and
 * page numbers out, hyphenated and wrapped lines rejoined, paragraphs kept, chapters from the book's own table of
 * contents (EPUB nav/NCX, PDF outline) or, failing that, heading heuristics.
 */
enum class Kind { CHAPTER, FRONT, BACK }

data class Chapter(val title: String, val kind: Kind, val text: String) {
    val words get() = text.split(Regex("\\s+")).count { it.isNotEmpty() }
}

data class BookDoc(val title: String, val author: String, val format: String, val chapters: List<Chapter>) {
    val readable get() = chapters.filter { it.kind == Kind.CHAPTER }
}

/** One table-of-contents entry pointing at a page (PDF) or a spine document (EPUB). */
data class TocEntry(val title: String, val index: Int)

object BookText {
    const val PARA = '¶'   // paragraph start marker (PDFTextStripper.paragraphStart)

    // ---------- classification ----------
    private val frontTitle = Regex("^(cover|title( page)?|half title|copyright|contents|table of contents|toc|dedication|epigraph|maps?( of .*)?|" +
        "also by .*|other books by .*|books by .*|praise( for .*)?|about the publisher|newsletter|sign up.*|frontispiece|imprint|colophon|landmarks)$", RegexOption.IGNORE_CASE)
    private val backTitle = Regex("^(acknowledge?ments?|about the authors?|about .{2,40}|notes?|endnotes|further reading|bibliography|references|glossary|index|" +
        "also by .*|other books by .*|books by .*|excerpt.*|preview.*|a preview of .*|reading group guide|discussion questions|copyright|credits|newsletter|" +
        "more from .*|coming soon.*|read on.*|teaser.*)$", RegexOption.IGNORE_CASE)
    private val chapterTitle = Regex("^(chapter|part|book|prologue|epilogue|interlude|afterword|foreword|preface|introduction|act|section)\\b.*|^[0-9]{1,3}$|^[ivxlcdm]{1,7}$|" +
        "^(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)( .*)?$", RegexOption.IGNORE_CASE)
    private val boiler = Regex("click here|sign up|newsletter|thank you for (purchasing|downloading|buying)|ebook(news)?\\b|visit us (online|at)|all rights reserved|\\bisbn\\b|" +
        "copyright ©|©\\s*\\d{4}|special offers|bonus content|www\\.|https?://|created by pdf to epub|no part of this (book|publication)", RegexOption.IGNORE_CASE)

    fun boilerScore(text: String): Int = boiler.findAll(text).count()

    /** Kind of a section from its title, content and what came before ([inBack] = back matter already started). */
    fun classify(title: String, text: String, inBack: Boolean, seenChapter: Boolean): Kind {
        val t = title.trim().trim('.', ':')
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (words < 400 && boilerScore(text) >= 1) return if (seenChapter) Kind.BACK else Kind.FRONT
        if (chapterTitle.matches(t)) return Kind.CHAPTER
        if (!seenChapter && frontTitle.matches(t)) return Kind.FRONT
        if (seenChapter && backTitle.matches(t)) return Kind.BACK
        if (inBack) return Kind.BACK
        if (!seenChapter && words < 120) return Kind.FRONT       // short untitled bits before the story: dedication, quote
        return Kind.CHAPTER
    }

    /** "1" → "Chapter 1", "EPILOGUE" → "Epilogue". */
    fun niceTitle(t0: String): String {
        val t = t0.replace(Regex("\\s+"), " ").trim()
        if (t.matches(Regex("[0-9]{1,3}"))) return "Chapter $t"
        if (t.matches(Regex("[IVXLCDM]{1,7}"))) return "Chapter $t"
        val letters = t.filter { it.isLetter() }
        return if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) t.lowercase().split(' ').joinToString(" ") { w ->
            if (w in setOf("of", "the", "a", "an", "and", "in", "on", "to", "for") && w != t.lowercase().substringBefore(' ')) w else if (w.trimEnd('.', ':').matches(Regex("[ivxlcdm]{1,7}")) && w.trimEnd('.', ':') != "i" || w.trimEnd('.', ':') == "i" && t.lowercase().startsWith("chapter")) w.uppercase() else w.replaceFirstChar { it.uppercase() } } else t
    }

    // ---------- page cleanup (PDF) ----------
    private val pageNum = Regex("^\\s*(page\\s*)?[0-9ivxlc]{1,4}\\s*((/|of)\\s*[0-9]{1,5})?\\s*$", RegexOption.IGNORE_CASE)

    /**
     * Page numbers ("10/359", "Page 3", "xii") and running headers/footers (a first/last line repeating across pages).
     * Streaming: [add] every page once (only line statistics are kept), then [strip] pages one at a time.
     */
    class RunningLines {
        private val count = HashMap<String, Int>(); var pages = 0; private set
        private fun lines(p: String) = p.split('\n').map { it.trimEnd() }
        private fun key(l: String) = l.trim().trimStart(PARA).replace(Regex("[0-9]+"), "#").lowercase()
        fun add(p: String) {
            pages++
            val ls = lines(p).filter { it.isNotBlank() }; if (ls.isEmpty()) return
            (listOf(ls.first(), ls.last()) + ls.take(2) + ls.takeLast(2)).map(::key).filter { it.length <= 80 }.toSet().forEach { count[it] = (count[it] ?: 0) + 1 }
        }
        fun strip(p: String): String {
            val minRep = maxOf(4, pages / 5)
            val ls = lines(p).toMutableList()
            fun bad(l: String): Boolean { val k = key(l); return l.trim().trimStart(PARA).let { pageNum.matches(it) } || (k.length in 1..80 && (count[k] ?: 0) >= minRep && k.any { it.isLetter() } && !k.endsWith(".")) }
            repeat(2) { val i = ls.indexOfFirst { it.isNotBlank() }; if (i >= 0 && bad(ls[i])) ls.removeAt(i) }
            repeat(2) { val i = ls.indexOfLast { it.isNotBlank() }; if (i >= 0 && bad(ls[i])) ls.removeAt(i) }
            return ls.joinToString("\n")
        }
    }

    fun stripRunning(pages: List<String>): List<String> { val r = RunningLines(); pages.forEach(r::add); return pages.map(r::strip) }

    // ---------- paragraphs, hyphens, wrapped lines ----------
    private val sceneBreak = Regex("^[\\s•*·#~—–_=+.○◆◇♦❖✦✧⁂]+$")
    private val terminal = Regex("[.!?…:;”\"’')\\]—]$")

    /**
     * Joins wrapped lines into paragraphs. [PARA] (or a blank line) starts a paragraph. A line ending in "-" is joined
     * without the hyphen unless the hyphenated word is seen elsewhere in the book ([dict] of known words).
     */
    fun paragraphs(text: String, dict: Set<String> = emptySet(), blankLineParagraphs: Boolean = true): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        fun flush() { val s = cur.toString().replace(Regex("[ \\t]+"), " ").trim(); if (s.isNotEmpty()) out += s; cur.setLength(0) }
        val rawLines = text.split('\n')
        // typical full line width: a clearly shorter line that ends a sentence ends its paragraph
        val lens = rawLines.map { it.trim().trimStart(PARA).trim().length }.filter { it > 0 }.sorted()
        val full = if (lens.size >= 8) lens[lens.size * 9 / 10] else Int.MAX_VALUE
        var lastLen = 0
        for (raw in rawLines) {
            var l = raw.replace('\u00A0', ' ').trimEnd()
            var startsPara = l.trimStart().startsWith(PARA)
            if (startsPara) l = l.trimStart().drop(1)
            if (l.isBlank()) { if (blankLineParagraphs) flush(); continue }
            if (sceneBreak.matches(l)) { flush(); out += ""; lastLen = 0; continue }
            val line = l.trim()
            val first = line.first()
            // a false paragraph start: the previous text stops mid-word or mid-sentence and this goes on in lower case
            if (startsPara && cur.isNotEmpty() && first.isLowerCase() && (cur.endsWith("-") || !terminal.containsMatchIn(cur))) startsPara = false
            // a missed paragraph start: the previous line was short and ended a sentence
            if (!startsPara && cur.isNotEmpty() && full != Int.MAX_VALUE && lastLen < full * 0.72 && terminal.containsMatchIn(cur) && !first.isLowerCase()) startsPara = true
            if (startsPara) flush()
            lastLen = line.length
            if (cur.isEmpty()) cur.append(line)
            else if (cur.endsWith("-") && !cur.endsWith("--") && cur.length >= 2 && cur[cur.length - 2].isLetter() && first.isLowerCase()) {
                val left = Regex("([\\p{L}'’]+)-$").find(cur)?.groupValues?.get(1).orEmpty()
                val right = Regex("^[\\p{L}'’]+").find(line)?.value.orEmpty()
                val hyph = "$left-$right".lowercase()
                if (hyph in dict && (left + right).lowercase() !in dict) cur.append(line) else { cur.setLength(cur.length - 1); cur.append(line) }
            } else cur.append(' ').append(line)
        }
        flush()
        return out
    }

    /** Words seen in the book: plain, and hyphenated ones that appear on a single line (to tell "blue-black" from "af-ternoon"). */
    fun dictionary(texts: List<String>): Set<String> { val d = HashSet<String>(); texts.forEach { addWords(d, it) }; return d }
    fun addWords(d: MutableSet<String>, t: String) {
        for (line in t.split('\n')) {
            val words = Regex("[\\p{L}'’]+(?:-[\\p{L}'’]+)*").findAll(line).map { it.value.lowercase() }.toList()
            // a word broken at the line end isn't a real word: skip the last token if the line ends with "-"
            val n = if (line.trimEnd().endsWith("-")) words.size - 1 else words.size
            for (i in 0 until n) d += words[i]
        }
    }

    /** Merges a paragraph broken by a page boundary (next one starts lower-case, or this one has no closing punctuation). */
    fun joinAcrossPages(pages: List<List<String>>): List<String> {
        val out = ArrayList<String>()
        for (p in pages) for ((i, para) in p.withIndex()) {
            val prev = out.lastOrNull()
            if (i == 0 && prev != null && prev.isNotEmpty() && para.isNotEmpty() &&
                (para.first().isLowerCase() || (!terminal.containsMatchIn(prev) && para.first().isLetter()))) {
                out[out.size - 1] = if (prev.endsWith("-") && prev.length > 1 && prev[prev.length - 2].isLetter() && para.first().isLowerCase()) prev.dropLast(1) + para else "$prev $para"
            } else out += para
        }
        return out
    }

    /** Paragraph list → chapter text (paragraphs separated by blank lines; scene breaks collapse). */
    fun render(paras: List<String>): String = paras.joinToString("\n\n").replace(Regex("\n{3,}"), "\n\n").trim()

    /** Drops the chapter's own heading paragraph(s) from its start ("1", "EPILOGUE", "Chapter One: The Storm"). */
    fun dropHeading(paras: List<String>, title: String): List<String> {
        var ps = paras.dropWhile { it.isBlank() }
        val tn = norm(title)
        repeat(2) {
            val first = ps.firstOrNull() ?: return ps
            val fn = norm(first)
            if (first.length <= 80 && (fn == tn || tn.endsWith(fn) || fn.startsWith(tn) || chapterTitle.matches(first.trim()))) ps = ps.drop(1).dropWhile { it.isBlank() }
        }
        return ps
    }
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}0-9]+"), " ").trim()

    // ---------- PDF ----------
    /**
     * Streaming PDF build: [page] returns one page's text (PDFTextStripper with paragraphStart = [PARA]) and may be
     * called more than once per page (pages come back from a disk cache on the phone); [toc] = outline entries
     * (0-based pages). Chapters go to [emit] one at a time, so at most one chapter's text is in memory. Without an
     * outline, chapters come from headings found at the top of pages.
     */
    fun fromPdfPages(nPages: Int, toc: List<TocEntry>, page: (Int) -> String, emit: (Chapter) -> Unit) {
        val running = RunningLines(); val dict = HashSet<String>()
        for (i in 0 until nPages) { val p = page(i); running.add(p); addWords(dict, p) }
        fun clean(i: Int) = running.strip(page(i))
        val entries = (if (toc.size >= 2) toc else (0 until nPages).mapNotNull { i -> headingOf(clean(i))?.let { TocEntry(it, i) } })
            .filter { it.index in 0 until nPages }.sortedBy { it.index }.distinctBy { it.index }
        val asm = Assembler(emit)
        fun section(t: String, a: Int, b: Int) = asm.section(t, joinAcrossPages((a until b).map { paragraphs(clean(it), dict, false) }))
        if (entries.isEmpty()) section("Text", 0, nPages)
        else {
            if (entries[0].index > 0) section("Front matter", 0, entries[0].index)
            for ((k, e) in entries.withIndex()) section(e.title, e.index, if (k + 1 < entries.size) entries[k + 1].index else nPages)
        }
    }

    fun fromPdf(title: String, author: String, pages: List<String>, toc: List<TocEntry>): BookDoc {
        val out = ArrayList<Chapter>(); fromPdfPages(pages.size, toc, pages::get) { out += it }; return BookDoc(title, author, "pdf", out)
    }

    private fun headingOf(p: String): String? {
        val first = p.split('\n').map { it.trim().trimStart(PARA).trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return if (first.length <= 60 && chapterTitle.matches(first)) first else null
    }

    /** Classifies sections one at a time (front matter / chapter / back matter depends on what came before). */
    class Assembler(private val emit: (Chapter) -> Unit) {
        private var inBack = false; private var seen = false
        fun section(t: String, paras0: List<String>, hint: Kind? = null) {
            val paras = dropHeading(paras0, t)
            val text = render(paras)
            val kind = hint?.takeIf { !(it == Kind.CHAPTER && boilerScore(text) > 0 && text.length < 2500) } ?: classify(t, text, inBack, seen)
            if (kind == Kind.CHAPTER) seen = true
            if (kind == Kind.BACK) inBack = true
            if (text.isBlank()) return
            emit(Chapter(niceTitle(t), kind, text))
        }
    }

    /** Sections → classified, cleaned chapters. */
    fun assemble(title: String, author: String, format: String, sections: List<Pair<String, List<String>>>, hints: List<Kind?> = emptyList()): BookDoc {
        val out = ArrayList<Chapter>(); val asm = Assembler { out += it }
        for ((k, sec) in sections.withIndex()) asm.section(sec.first, sec.second, hints.getOrNull(k))
        return BookDoc(title, author, format, out)
    }

    // ---------- plain text ----------
    fun fromTxt(name: String, raw: String): BookDoc {
        val out = ArrayList<Chapter>(); fromTxtLines(name, { raw.reader().buffered() }) { out += it }; return BookDoc(name, "", "txt", out)
    }

    /**
     * Streaming plain text: [open] gives a fresh reader over the file each time (read twice: once for statistics
     * and headings, once to build chapters). Only one chapter's lines are held at a time.
     */
    fun fromTxtLines(name: String, open: () -> java.io.BufferedReader, emit: (Chapter) -> Unit) {
        val gStart = Regex("^\\*\\*\\* ?START OF (THE|THIS) PROJECT GUTENBERG"); val gEnd = Regex("^\\*\\*\\* ?END OF (THE|THIS) PROJECT GUTENBERG")
        fun clean(l: String) = l.replace("\uFEFF", "")
        // pass 1: Gutenberg wrapper, words, headings (a short heading line with blank lines around it)
        var from = 0; var until = Int.MAX_VALUE
        open().useLines { ls -> ls.forEachIndexed { i, l -> if (from == 0 && gStart.containsMatchIn(l)) from = i + 1; if (until == Int.MAX_VALUE && gEnd.containsMatchIn(l)) until = i } }
        val dict = HashSet<String>(); val heads = LinkedHashMap<Int, String>()
        var prev2: String? = null; var prev1: String? = null; var i1 = -1   // sliding window: line i1 is judged once its next line is known
        fun judge(i: Int, l: String, before: String?, after: String?) {
            val s0 = l.trim()
            val blankAround = (before == null || before.isBlank()) && (after == null || after.isBlank() || after.trim().length < 60)
            if (s0.isNotEmpty() && s0.length <= 60 && blankAround && chapterTitle.matches(s0.trimEnd('.'))) {
                val next = after?.trim().orEmpty()
                heads[i] = if (next.isNotEmpty() && next.length < 60 && !next.endsWith(".")) "$s0: $next" else s0   // "CHAPTER I." + subtitle
            }
        }
        open().useLines { ls -> ls.forEachIndexed { i, raw -> if (i < from || i >= until) return@forEachIndexed
            val l = clean(raw); addWords(dict, l)
            prev1?.let { judge(i1, it, prev2, l) }
            prev2 = prev1; prev1 = l; i1 = i } }
        prev1?.let { judge(i1, it, prev2, null) }
        // pass 2: one section at a time
        val asm = Assembler(emit)
        val useHeads = heads.size >= 2
        var title = if (useHeads) "Front matter" else name; val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank() || title != "Front matter") asm.section(title, paragraphs(cur.toString(), dict, true)); cur.setLength(0) }
        open().useLines { ls -> ls.forEachIndexed { i, raw -> if (i < from || i >= until) return@forEachIndexed
            if (useHeads && i in heads) { flush(); title = heads.getValue(i) }
            cur.append(clean(raw)).append('\n') } }
        flush()
    }

    // ---------- reading units ----------
    private val abbrev = Regex("(?:\\b(?:mr|mrs|ms|dr|st|jr|sr|prof|gen|col|capt|lt|sgt|vs|etc|no|vol|ch|fig|mt|ft)|\\b[A-Z])\\.$", RegexOption.IGNORE_CASE)

    /** Chapter text → sentences to speak (short ones grouped, run-ons cut at a comma/space), paragraph breaks kept as "". */
    fun sentences(text: String, minChars: Int = 24, maxChars: Int = 280): List<String> {
        val out = ArrayList<String>()
        for (para in text.split(Regex("\n\\s*\n"))) {
            val p = para.replace(Regex("\\s+"), " ").trim(); if (p.isEmpty()) continue
            val raw = ArrayList<String>(); var start = 0; var i = 0
            while (i < p.length) {
                val c = p[i]
                if (c == '.' || c == '!' || c == '?' || c == '…') {
                    var j = i + 1
                    while (j < p.length && (p[j] in ".!?…”\"’')]")) j++
                    val nextStart = j < p.length && p[j] == ' ' && j + 1 < p.length
                    val nextCh = if (nextStart) p[j + 1] else ' '
                    val end = j >= p.length || (nextStart && (nextCh.isUpperCase() || nextCh.isDigit() || nextCh in "“\"‘'(—–"))
                    val isAbbrev = c == '.' && j == i + 1 && abbrev.containsMatchIn(p.substring(maxOf(start, i - 6), i + 1))
                    if (end && !isAbbrev) { raw += p.substring(start, j).trim(); start = j; i = j; continue }
                }
                i++
            }
            if (start < p.length) p.substring(start).trim().takeIf { it.isNotEmpty() }?.let { raw += it }
            val merged = ArrayList<String>()
            for (s in raw) if (merged.isNotEmpty() && merged.last().length < minChars) merged[merged.size - 1] = merged.last() + " " + s else merged += s
            for (s in merged) out += cut(s, maxChars)
            out += ""
        }
        while (out.lastOrNull() == "") out.removeAt(out.size - 1)
        return out
    }

    private fun cut(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>(); var rest = s
        while (rest.length > max) {
            var k = rest.lastIndexOfAny(charArrayOf(',', ';', ':', '—'), max); if (k < max / 3) k = rest.lastIndexOf(' ', max); if (k <= 0) k = max
            out += rest.substring(0, k + 1).trim(); rest = rest.substring(k + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
