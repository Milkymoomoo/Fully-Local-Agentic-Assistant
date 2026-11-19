package androidx.activity.result.contract

import android.net.Uri
import androidx.activity.result.ActivityResult
import app.belle.desktop.ui.FileDialogs

/** Desktop stand-in: each contract opens the matching desktop dialog (files from the PC instead of Android's picker). */
abstract class ActivityResultContract<I, O> { abstract fun run(input: I, done: (O) -> Unit) }

object ActivityResultContracts {
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun run(input: Array<String>, done: (Uri?) -> Unit) = FileDialogs.open(input.toList(), multiple = false) { done(it.firstOrNull()?.let(Uri::fromFile)) }
    }
    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun run(input: Array<String>, done: (List<Uri>) -> Unit) = FileDialogs.open(input.toList(), multiple = true) { done(it.map(Uri::fromFile)) }
    }
    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun run(input: String, done: (Uri?) -> Unit) = FileDialogs.open(listOf(input), multiple = false) { done(it.firstOrNull()?.let(Uri::fromFile)) }
    }
    class CreateDocument(private val mime: String) : ActivityResultContract<String, Uri?>() {
        override fun run(input: String, done: (Uri?) -> Unit) = FileDialogs.save(input) { done(it?.let(Uri::fromFile)) }
    }
    class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        override fun run(input: Uri?, done: (Uri?) -> Unit) = FileDialogs.folder { done(it?.let(Uri::fromFile)) }
    }
    /** Android system screens (ringtone picker etc.) don't exist on a PC: always "cancelled". */
    class StartActivityForResult : ActivityResultContract<android.content.Intent, ActivityResult>() {
        override fun run(input: android.content.Intent, done: (ActivityResult) -> Unit) = done(ActivityResult(android.app.Activity.RESULT_CANCELED, null))
    }
    class RequestPermission : ActivityResultContract<String, Boolean>() { override fun run(input: String, done: (Boolean) -> Unit) = done(true) }
}
