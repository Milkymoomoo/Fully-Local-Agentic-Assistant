package app.belle.assistant.models

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * One resumable, verified HTTPS download (no Android dependencies, so it is unit-tested against a local server).
 *  - Keeps `<dest>.part` and continues with `Range: bytes=<have>-`; a server that ignores Range (200) restarts it.
 *  - Follows redirects by hand, each hop checked by [hostOk] (official hosts only).
 *  - Checks the size, then the SHA-256 (also resumed: the hash of what is already on disk is computed first), and only
 *    then renames to [dest]. A wrong file is deleted so the next try starts clean.
 */
class Fetcher(
    private val hostOk: (URL, first: Boolean) -> Boolean = { u, first -> u.protocol == "https" && Downloads.hostAllowed(u.host, first) },
    private val userAgent: String = "MilkyMoosAssistant/1.0",
    private val connectTimeoutMs: Int = 20_000, private val readTimeoutMs: Int = 60_000,
) {
    class Cancelled : RuntimeException("cancelled")
    class Failed(msg: String) : RuntimeException(msg)

    /** [progress](bytes on disk for this file) is called often; [cancelled] is polled between reads. */
    fun fetch(url: String, dest: File, size: Long, sha256: String, progress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        if (dest.exists() && dest.length() == size && (sha256.isEmpty() || sha256(dest).equals(sha256, true))) { progress(size); return }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (have > size) { part.delete(); have = 0 }
        var u = URL(url); var hops = 0
        if (!hostOk(u, true)) throw Failed("Blocked: ${u.host} is not an allowed download source")
        while (have < size) {
            val conn = u.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false; conn.connectTimeout = connectTimeoutMs; conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw Failed("Redirect without a location")
                    u = URL(u, loc)
                    if (!hostOk(u, false)) throw Failed("Blocked redirect to ${u.host}")
                    if (++hops > 8) throw Failed("Too many redirects"); continue
                }
                if (code == 416) { if (have == size) break; part.delete(); have = 0; throw Failed("The server refused to resume; try again") }
                if (code != 200 && code != 206) throw Failed("HTTP $code from ${u.host}")
                if (code == 200) have = 0
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(have); raf.seek(have)
                    conn.inputStream.use { inp ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (cancelled()) throw Cancelled()
                            val n = inp.read(buf); if (n < 0) break
                            if (have + n > size) throw Failed("The file is bigger than expected")
                            raf.write(buf, 0, n); have += n; progress(have)
                        }
                    }
                }
                if (have < size) throw Failed("Connection dropped at ${have * 100 / size} % — retry to continue")
            } catch (e: java.io.IOException) {
                throw Failed("Connection dropped at ${have * 100 / size} % (${e.javaClass.simpleName}) — retry to continue")
            } finally { conn.disconnect() }
        }
        if (part.length() != size) throw Failed("Download incomplete (${part.length()} of $size bytes)")
        if (sha256.isNotEmpty()) {
            val got = sha256(part)
            if (!got.equals(sha256, true)) { part.delete(); throw Failed("Checksum mismatch — the download was damaged and has been deleted; retry") }
        }
        dest.delete()
        if (!part.renameTo(dest)) throw Failed("Couldn't save ${dest.name}")
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
