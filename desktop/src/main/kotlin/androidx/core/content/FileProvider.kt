package androidx.core.content

/** Desktop stand-in: files are shared as themselves (shown in Explorer). */
object FileProvider { @JvmStatic fun getUriForFile(ctx: android.content.Context, authority: String, f: java.io.File): android.net.Uri = android.net.Uri.fromFile(f) }
