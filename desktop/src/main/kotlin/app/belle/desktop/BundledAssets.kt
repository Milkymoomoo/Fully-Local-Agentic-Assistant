package app.belle.desktop

import android.content.res.AssetManager

/**
 * The phone app's `assets/` files the Windows / Linux program must carry (default avatars, the wake-word model, the
 * cut-out model, the Photo → 3D model list, voice samples). The build writes their list (path + size) to
 * `bundled-assets.txt` on the classpath; [verify] proves every one opens through [AssetManager.open] with the right
 * size and shows up in [AssetManager.list] of its folder, the way the shared phone code reads them.
 */
object BundledAssets {
    data class Entry(val path: String, val size: Long)

    fun expected(): List<Entry> {
        val text = BundledAssets::class.java.getResourceAsStream("/bundled-assets.txt")?.use { it.readBytes().decodeToString() }
            ?: error("bundled-assets.txt is missing from the program")
        return text.lines().filter { it.isNotBlank() }.map { l -> l.split('\t').let { Entry(it[0], it[1].toLong()) } }
    }

    /** "<n> files, <MB> MB from <folder>", or an exception naming every missing / wrong file. */
    fun verify(am: AssetManager): String {
        val want = expected(); require(want.isNotEmpty()) { "the bundled asset list is empty" }
        val bad = ArrayList<String>()
        for (e in want) {
            val n = runCatching { am.open(e.path).use { s -> var t = 0L; val b = ByteArray(1 shl 16); while (true) { val r = s.read(b); if (r < 0) break; t += r }; t } }
            when {
                n.isFailure -> bad += "${e.path} missing"
                n.getOrNull() != e.size -> bad += "${e.path} is ${n.getOrNull()} bytes, expected ${e.size}"
            }
            val dir = e.path.substringBeforeLast('/', ""); val name = e.path.substringAfterLast('/')
            if (name !in am.list(dir)) bad += "${e.path} not listed in '${dir}'"
            if (am.list(e.path).isNotEmpty()) bad += "${e.path} lists as a folder"
        }
        require(bad.isEmpty()) { "${bad.size} problems in ${want.size} files: " + bad.take(8).joinToString("; ") + if (bad.size > 8) "; …" else "" }
        return "${want.size} files, ${"%.1f".format(want.sumOf { it.size } / 1e6)} MB from ${am.roots.firstOrNull()?.path ?: "the classpath"}"
    }
}
