package androidx.compose.ui.graphics

/** Desktop stand-in for Compose-Android's Bitmap.asImageBitmap (our Bitmap shim keeps a Skia copy for drawing). */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = skImage().toComposeImageBitmap()
