package android.database

/** Desktop stand-in (no content providers on the desktop; queries return null). */
interface Cursor : java.io.Closeable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getString(i: Int): String
    fun getLong(i: Int): Long
    fun getInt(i: Int): Int
}
