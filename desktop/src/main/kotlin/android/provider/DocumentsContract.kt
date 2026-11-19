package android.provider

import android.net.Uri
import java.io.File

/**
 * Desktop stand-in for Android's document tree API: a picked folder is a plain file:// Uri, a document id is a
 * file path, and listing children reads the folder. Lets "import a model folder" run unchanged on the PC.
 */
object DocumentsContract {
    object Document { const val COLUMN_DOCUMENT_ID = "document_id"; const val COLUMN_DISPLAY_NAME = "_display_name"; const val COLUMN_SIZE = "_size"; const val COLUMN_MIME_TYPE = "mime_type" }
    private const val KIDS = "belle-children:"
    @JvmStatic fun getTreeDocumentId(tree: Uri): String = tree.path ?: error("not a folder")
    @JvmStatic fun getDocumentId(doc: Uri): String = doc.path ?: error("not a document")
    @JvmStatic fun buildDocumentUriUsingTree(tree: Uri, id: String): Uri = Uri.fromFile(File(id))
    @JvmStatic fun buildChildDocumentsUriUsingTree(tree: Uri, parentId: String): Uri = Uri.parse(KIDS + File(parentId).toURI())
    internal fun children(u: Uri): List<File>? = u.toString().takeIf { it.startsWith(KIDS) }?.let { File(java.net.URI(it.removePrefix(KIDS))).listFiles()?.sortedBy { f -> f.name } ?: emptyList() }
}
