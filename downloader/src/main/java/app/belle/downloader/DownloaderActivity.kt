package app.belle.downloader

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.belle.assistant.core.ModelCatalog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Downloads a model (resumable, SHA-256 checked for LLMs), then either sends
 * it straight to Belle or saves it to Downloads for importing with Belle's
 * file picker. No analytics, no other network use. Uninstall it when done.
 */
class DownloaderActivity : Activity() {
    private lateinit var root: LinearLayout
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 48, 40, 80) }
        setContentView(ScrollView(this).apply { addView(root) })
        text("Belle Downloader", 24f, bold = true)
        text("Optional helper. Downloads model files from Hugging Face / GitHub and hands them to Belle, which itself has no internet access. Uninstall this helper whenever you like.")
        status = text("")
        text("1. Speech recognition (required)", 20f, bold = true)
        item("${ModelCatalog.STT.label} + ${ModelCatalog.VAD.label}\n${(ModelCatalog.STT.bytes + ModelCatalog.VAD.bytes) / 1_000_000} MB · MIT") {
            download(ModelCatalog.VAD.url, ModelCatalog.VAD.fileName, ModelCatalog.VAD.bytes, ModelCatalog.VAD.sha256)
            download(ModelCatalog.STT.url, ModelCatalog.STT.fileName, ModelCatalog.STT.bytes, null)
        }
        text("Language models", 20f, bold = true)
        for (m in ModelCatalog.LLMS) item("${m.label}\n${"%.2f".format(m.bytes / 1e9)} GB · ${m.license}\n${m.note}") {
            download(m.url, m.fileName, m.bytes, m.sha256)
        }
        text("Voices (get at least one)", 20f, bold = true)
        for (v in ModelCatalog.VOICES.filter { !it.bundled }) item("${v.label} — ${v.gender}, ${v.accent}\n${v.license}") {
            download(v.archiveUrl, v.dirName + ".tar.bz2", 0, null)
        }
        text("Downloaded files", 20f, bold = true)
        for (f in dlDir().listFiles()?.filter { !it.name.endsWith(".part") } ?: emptyList()) {
            item("${f.name} (${f.length() / 1_000_000} MB)") { send(f) }
        }
    }

    private fun text(s: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, 12, 0, 12)
    }.also { root.addView(it) }

    private fun item(label: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "Get"; isAllCaps = false; setOnClickListener { onClick() } })
        root.addView(row)
    }

    private fun dlDir() = File(filesDir, "dl").apply { mkdirs() }

    private fun download(url: String, name: String, bytes: Long, sha: String?) {
        val dest = File(dlDir(), name)
        if (dest.exists()) { send(dest); return }
        bg.execute {
            val r = runCatching { fetch(url, dest, bytes, sha) }
            runOnUiThread {
                r.onSuccess { status.text = "Downloaded ${it.name}"; send(it) }
                    .onFailure { status.text = "Failed: ${it.message}" }
            }
        }
    }

    private fun fetch(url: String, dest: File, expected: Long, sha: String?): File {
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        var u = URL(url); var hops = 0
        var conn: HttpURLConnection
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false; conn.connectTimeout = 20000; conn.readTimeout = 60000
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 8) { u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code == 200) have = 0 else if (code != 206) error("HTTP $code")
            break
        }
        val total = if (expected > 0) expected else have + conn.contentLengthLong
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(1 shl 16); var done = have; var last = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (done - last > 2_000_000) { last = done; runOnUiThread { status.text = "${dest.name}: ${done / 1_000_000} / ${total / 1_000_000} MB" } }
                }
            }
        }
        if (expected > 0) check(part.length() == expected) { "size mismatch" }
        if (!sha.isNullOrEmpty()) {
            val md = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { val b = ByteArray(1 shl 20); while (true) { val n = it.read(b); if (n < 0) break; md.update(b, 0, n) } }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(sha, true)) { part.delete(); error("checksum mismatch") }
        }
        check(part.renameTo(dest))
        return dest
    }

    /** Hand the file to Belle; if Belle isn't installed, save a copy to Downloads instead. */
    private fun send(f: File) {
        val uri = Uri.parse("content://app.belle.downloader.files/${Uri.encode(f.name)}")
        val i = Intent(Intent.ACTION_SEND).setPackage("app.belle.assistant").setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: Exception) {
            bg.execute {
                val r = runCatching { saveToDownloads(f) }
                runOnUiThread { Toast.makeText(this, r.fold({ "Belle not found; saved to Downloads/${f.name}" }, { "Save failed: ${it.message}" }), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun saveToDownloads(f: File) {
        val cv = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, f.name); put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream") }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
    }
}

/** Minimal read-only provider so Belle can read a downloaded file it was explicitly granted. */
class FilesProvider : ContentProvider() {
    private fun file(uri: Uri) = File(File(context!!.filesDir, "dl"), uri.lastPathSegment ?: "").takeIf {
        it.canonicalPath.startsWith(File(context!!.filesDir, "dl").canonicalPath + File.separator) && it.exists()
    }
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        file(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val f = file(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
