package androidx.activity.result

class ActivityResult(val resultCode: Int, val data: android.content.Intent?)
fun interface ActivityResultLauncher<I> { fun launch(input: I) }
