package android.content

/** Desktop stand-in for Android's SharedPreferences (subset the shared code uses). See [app.belle.desktop.JsonPrefs]. */
interface SharedPreferences {
    fun getString(key: String, def: String?): String?
    fun getStringSet(key: String, def: Set<String>?): Set<String>?
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getFloat(key: String, def: Float): Float
    fun getBoolean(key: String, def: Boolean): Boolean
    fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, v: String?): Editor
        fun putStringSet(key: String, v: Set<String>?): Editor
        fun putInt(key: String, v: Int): Editor
        fun putLong(key: String, v: Long): Editor
        fun putFloat(key: String, v: Float): Editor
        fun putBoolean(key: String, v: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}
