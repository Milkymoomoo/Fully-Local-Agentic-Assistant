package app.belle.assistant.models

import android.content.Context
import android.net.Uri
import app.belle.assistant.core.ModelCatalog
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Brings model files into the app WITHOUT any network access: from the file
 * picker (files you copied to the phone) or shared from the optional
 * "Belle Downloader" companion app. (Network downloads are separate: models/Downloads and threed/Pixal3DInstall.)
 */
object ModelImport {
    /** Copies a .gguf into private storage, verifying SHA-256 when it is a known catalog model. */
    fun importGguf(ctx: Context, uri: Uri, displayName: String, progress: (Long) -> Unit = {}): Result<File> = runCatching {
        val name = displayName.substringAfterLast('/').ifEmpty { "model.gguf" }
        require(name.endsWith(".gguf", ignoreCase = true)) { "Pick a .gguf file" }
        val dest = File(ModelFiles.llmDir(ctx), name)
        val part = File(dest.path + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)!!.use { raw ->
            DigestInputStream(raw, md).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20); var done = 0L; var last = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - last > 20_000_000) { progress(done); last = done }
                    }
                }
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        val known = ModelCatalog.LLMS.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
        if (known != null && !known.sha256.equals(sha, ignoreCase = true)) {
            part.delete(); error("Checksum mismatch for ${known.label}; the file is damaged or not the official one.")
        }
        check(part.renameTo(dest)) { "could not save file" }
        dest
    }

    /** Extract a sherpa-onnx voice .tar.bz2 into the voices directory. Returns the voice dir name. */
    fun extractVoice(ctx: Context, archive: InputStream): String {
        val voices = ModelFiles.voicesDir(ctx)
        var top: String? = null
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(voices, e.name).canonicalFile
                if (!out.path.startsWith(voices.canonicalPath + File.separator)) continue  // path-traversal guard
                if (top == null) top = e.name.trimStart('/').substringBefore('/')
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { tar.copyTo(it) } }
            }
        }
        val dir = top ?: error("empty archive")
        require(File(voices, dir).listFiles()?.any { it.name.endsWith(".onnx") } == true) { "not a voice archive" }
        return dir
    }

    /** Speech recognition archive (sherpa-onnx Moonshine .tar.bz2): keep just the model files, flattened. */
    fun extractStt(ctx: Context, archive: InputStream): String {
        val dest = ModelFiles.stt(ctx).apply { mkdirs() }
        val want = setOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt", "LICENSE")
        var n = 0
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (!e.isDirectory && name in want) { File(dest, name).outputStream().use { tar.copyTo(it) }; n++ }
            }
        }
        check(File(dest, "encoder_model.ort").exists() && File(dest, "decoder_model_merged.ort").exists()) { "not a Moonshine speech-recognition archive" }
        return "speech recognition ($n files)"
    }

    /** Import whatever the user picked or shared: .gguf → LLM, Moonshine .tar.bz2 → STT, silero_vad.onnx → VAD, other .tar.bz2 → voice. */
    fun importAny(ctx: Context, uri: Uri, displayName: String, progress: (Long) -> Unit = {}): Result<String> = runCatching {
        when {
            displayName.endsWith(".gguf", true) -> "model:" + importGguf(ctx, uri, displayName, progress).getOrThrow().name
            displayName.contains("moonshine", true) && displayName.contains(".tar", true) ->
                "stt:" + extractStt(ctx, ctx.contentResolver.openInputStream(uri)!!)
            displayName.startsWith("silero_vad", true) && displayName.endsWith(".onnx", true) -> {
                val md = MessageDigest.getInstance("SHA-256")
                val part = File(ModelFiles.vad(ctx).path + ".part")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> DigestInputStream(i, md).use { d -> part.outputStream().use { d.copyTo(it) } } }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (!sha.equals(ModelCatalog.VAD.sha256, true)) { part.delete(); error("silero_vad.onnx checksum mismatch") }
                check(part.renameTo(ModelFiles.vad(ctx)))
                "vad:silero_vad.onnx"
            }
            displayName.endsWith(".tar.bz2", true) || displayName.endsWith(".tbz2", true) ->
                "voice:" + extractVoice(ctx, ctx.contentResolver.openInputStream(uri)!!)
            else -> error("Unsupported file: $displayName (expected .gguf or .tar.bz2)")
        }
    }
}
