package android.database

/** An in-memory cursor over rows (desktop folder listings). */
class ListCursor(private val rows: List<Array<Any?>>) : Cursor {
    private var i = -1
    override fun moveToFirst(): Boolean { i = 0; return rows.isNotEmpty() }
    override fun moveToNext(): Boolean { i++; return i < rows.size }
    override fun getString(c: Int): String = rows[i][c]?.toString() ?: ""
    override fun getLong(c: Int): Long = (rows[i][c] as? Number)?.toLong() ?: 0
    override fun getInt(c: Int): Int = (rows[i][c] as? Number)?.toInt() ?: 0
    override fun close() {}
}
