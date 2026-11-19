package app.belle.desktop

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * SharedPreferences on the desktop: one JSON file per profile (`<name>.json`), types kept, written atomically
 * (temp → fsync → rename) so a power cut leaves either the old or the new file, never half of one.
 */
class JsonPrefs(private val file: File) : SharedPreferences {
    private val map = LinkedHashMap<String, Any?>()
    init { load() }

    private fun load() {
        if (!file.isFile) { File(file.path + ".tmp").takeIf { it.isFile }?.renameTo(file) }
        if (!file.isFile) return
        runCatching {
            val o = JSONObject(file.readText())
            for (k in o.keys()) {
                val t = o.getJSONObject(k); val v: Any? = when (t.getString("t")) {
                    "s" -> t.getString("v"); "i" -> t.getInt("v"); "l" -> t.getLong("v"); "f" -> t.getDouble("v").toFloat()
                    "b" -> t.getBoolean("v"); "S" -> t.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> null
                }
                if (v != null) map[k] = v
            }
        }.onFailure { DesktopLog.line('W', "Prefs", "unreadable ${file.name}: ${it.message}", null) }
    }

    @Synchronized private fun write() {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, when (v) {
            is String -> JSONObject().put("t", "s").put("v", v); is Int -> JSONObject().put("t", "i").put("v", v)
            is Long -> JSONObject().put("t", "l").put("v", v); is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
            is Boolean -> JSONObject().put("t", "b").put("v", v); is Set<*> -> JSONObject().put("t", "S").put("v", JSONArray(v.toList()))
            else -> continue
        })
        AtomicFile.write(file, o.toString(1).toByteArray())
    }

    @Synchronized override fun getString(key: String, def: String?) = map[key] as? String ?: def
    @Suppress("UNCHECKED_CAST") @Synchronized override fun getStringSet(key: String, def: Set<String>?) = (map[key] as? Set<String>) ?: def
    @Synchronized override fun getInt(key: String, def: Int) = (map[key] as? Number)?.toInt() ?: def
    @Synchronized override fun getLong(key: String, def: Long) = (map[key] as? Number)?.toLong() ?: def
    @Synchronized override fun getFloat(key: String, def: Float) = (map[key] as? Number)?.toFloat() ?: def
    @Synchronized override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    @Synchronized override fun contains(key: String) = key in map
    override val all: Map<String, *> @Synchronized get() = LinkedHashMap(map)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val ops = ArrayList<(MutableMap<String, Any?>) -> Unit>()
        private fun op(f: (MutableMap<String, Any?>) -> Unit) = apply { ops += f }
        override fun putString(key: String, v: String?) = op { if (v == null) it.remove(key) else it[key] = v }
        override fun putStringSet(key: String, v: Set<String>?) = op { if (v == null) it.remove(key) else it[key] = LinkedHashSet(v) }
        override fun putInt(key: String, v: Int) = op { it[key] = v }
        override fun putLong(key: String, v: Long) = op { it[key] = v }
        override fun putFloat(key: String, v: Float) = op { it[key] = v }
        override fun putBoolean(key: String, v: Boolean) = op { it[key] = v }
        override fun remove(key: String) = op { it.remove(key) }
        override fun clear() = op { it.clear() }
        override fun commit(): Boolean { synchronized(this@JsonPrefs) { ops.forEach { it(map) }; write() }; return true }
        override fun apply() { commit() }
    }
}

/** temp → fsync → rename; the rename is atomic on NTFS and ext4 (ATOMIC_MOVE). */
object AtomicFile {
    fun write(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
