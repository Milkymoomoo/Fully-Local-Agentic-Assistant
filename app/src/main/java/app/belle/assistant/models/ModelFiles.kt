package app.belle.assistant.models

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Where model files live on the phone (app-private storage; nothing is shared).
 * Only the ~6 MB wake-word model ships inside the APK (copied out once, since
 * the native libraries need real file paths). Speech recognition, voices and
 * language models are imported on first run (file picker or the optional
 * Belle Downloader app), which keeps the APK small.
 */
object ModelFiles {
    private const val TAG = "BelleModels"
    private const val BUNDLE_VERSION = 2

    const val KWS_DIR = "kws"
    const val STT_DIR = "stt-moonshine-tiny-en"
    const val VAD_FILE = "silero_vad.onnx"
    /** Bob's default: a natural US male voice. Every other voice stays selectable. */
    const val DEFAULT_VOICE = "vits-piper-en_US-ryan-medium-int8"
    /** Boob Dragon's default: a warm US female voice. */
    const val DEFAULT_FEMALE_VOICE = "vits-piper-en_US-amy-medium-int8"

    fun root(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun voicesDir(ctx: Context) = File(root(ctx), "voices").apply { mkdirs() }
    fun llmDir(ctx: Context) = File(root(ctx), "llm").apply { mkdirs() }
    fun kws(ctx: Context) = File(root(ctx), KWS_DIR)
    fun stt(ctx: Context) = File(root(ctx), STT_DIR)
    fun vad(ctx: Context) = File(root(ctx), VAD_FILE)

    /** Copies assets/models/<...> to filesDir/models once per bundle version. */
    @Synchronized
    fun ensureBundled(ctx: Context) {
        val marker = File(root(ctx), ".bundle_v$BUNDLE_VERSION")
        if (marker.exists()) return
        val t0 = System.currentTimeMillis()
        copyAssetDir(ctx, "models/$KWS_DIR", kws(ctx))
        marker.writeText("ok")
        Log.i(TAG, "bundled models copied in ${System.currentTimeMillis() - t0} ms")
    }

    private fun copyAssetDir(ctx: Context, asset: String, dest: File) {
        val list = ctx.assets.list(asset) ?: return
        if (list.isEmpty()) { copyAssetFile(ctx, asset, dest); return }
        dest.mkdirs()
        for (name in list) copyAssetDir(ctx, "$asset/$name", File(dest, name))
    }

    private fun copyAssetFile(ctx: Context, asset: String, dest: File) {
        dest.parentFile?.mkdirs()
        ctx.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
    }

    /** Speech recognition pack present (Moonshine + Silero VAD)? */
    fun hasStt(ctx: Context) = File(stt(ctx), "encoder_model.ort").exists() && File(stt(ctx), "decoder_model_merged.ort").exists() && vad(ctx).exists()

    fun installedVoices(ctx: Context): List<String> =
        voicesDir(ctx).listFiles()?.filter { it.isDirectory && it.listFiles()?.any { f -> f.name.endsWith(".onnx") } == true }
            ?.map { it.name }?.sorted() ?: emptyList()

    fun installedLlms(ctx: Context): List<File> =
        llmDir(ctx).listFiles()?.filter { it.name.endsWith(".gguf") && !it.name.endsWith(".part") }?.sortedBy { it.length() } ?: emptyList()
}
