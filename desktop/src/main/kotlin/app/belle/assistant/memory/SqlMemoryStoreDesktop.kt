package app.belle.assistant.memory

import android.content.Context
import app.belle.assistant.core.Memory
import app.belle.assistant.core.MemoryStore
import app.belle.desktop.platform.SecretKeys
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Desktop twin of the phone's memory store: the same SQLite schema and the same per-column AES-256-GCM format, with the
 * key protected by Windows DPAPI (tied to your Windows account) instead of the Android Keystore — see [SecretKeys].
 * Clearing deletes the rows with secure_delete, VACUUMs, deletes the files and destroys the key.
 */
class SqlMemoryStore(private val ctx: Context, val profileId: String) : MemoryStore {
    private val alias = "belle_mem_" + profileId.replace(Regex("[^A-Za-z0-9_]"), "_")
    val file: File get() = File(dir(ctx), "$alias.db")
    private var conn: Connection? = null

    private fun db(): Connection = synchronized(this) {
        conn ?: DriverManager.getConnection("jdbc:sqlite:" + file.path).also { c ->
            c.createStatement().use { s ->
                s.execute("PRAGMA secure_delete=ON"); s.execute("PRAGMA journal_mode=DELETE")
                s.execute("CREATE TABLE IF NOT EXISTS turns(id INTEGER PRIMARY KEY AUTOINCREMENT, thread TEXT, u BLOB, r BLOB, ts INTEGER, summarized INTEGER DEFAULT 0)")
                s.execute("CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY AUTOINCREMENT, thread TEXT, t BLOB, ts INTEGER)")
                s.execute("CREATE TABLE IF NOT EXISTS summaries(thread TEXT PRIMARY KEY, t BLOB, ts INTEGER)")
            }
            conn = c
        }
    }
    private fun exec(sql: String, vararg a: Any?): Int = db().prepareStatement(sql).use { p -> a.forEachIndexed { i, v -> p.setObject(i + 1, v) }; p.executeUpdate() }
    private fun insert(sql: String, vararg a: Any?): Long = db().prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { p ->
        a.forEachIndexed { i, v -> p.setObject(i + 1, v) }; p.executeUpdate(); p.generatedKeys.use { if (it.next()) it.getLong(1) else -1 } }
    private fun <T> query(sql: String, vararg a: Any?, row: (java.sql.ResultSet) -> T): List<T> = db().prepareStatement(sql).use { p ->
        a.forEachIndexed { i, v -> p.setObject(i + 1, v) }; p.executeQuery().use { rs -> val l = ArrayList<T>(); while (rs.next()) l += row(rs); l } }

    private var cache: MutableList<Memory.Turn>? = null
    private fun all(): MutableList<Memory.Turn> = cache ?: query("SELECT id,thread,u,r,ts,summarized FROM turns ORDER BY id") {
        Memory.Turn(it.getLong(1), it.getString(2), dec(it.getBytes(3)), dec(it.getBytes(4)), it.getLong(5), it.getInt(6) != 0) }.toMutableList().also { cache = it }

    @Synchronized override fun addTurn(thread: String, user: String, reply: String): Memory.Turn {
        val ts = System.currentTimeMillis()
        val id = insert("INSERT INTO turns(thread,u,r,ts) VALUES(?,?,?,?)", thread, enc(user), enc(reply), ts)
        return Memory.Turn(id, thread, user, reply, ts).also { all().add(it) }
    }
    @Synchronized override fun turns(thread: String) = all().filter { it.thread == thread }
    @Synchronized override fun markSummarized(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        exec("UPDATE turns SET summarized=1 WHERE id IN (${ids.joinToString(",")})")
        all().replaceAll { if (it.id in ids) it.copy(summarized = true) else it }
    }
    @Synchronized override fun summary(thread: String): Memory.Summary? =
        query("SELECT t,ts FROM summaries WHERE thread=?", thread) { Memory.Summary(thread, dec(it.getBytes(1)), it.getLong(2)) }.firstOrNull()
    @Synchronized override fun setSummary(thread: String, text: String) { exec("INSERT OR REPLACE INTO summaries(thread,t,ts) VALUES(?,?,?)", thread, enc(text), System.currentTimeMillis()) }
    @Synchronized override fun notes(thread: String): List<Memory.Note> =
        query("SELECT id,t,ts FROM notes WHERE thread=? ORDER BY id", thread) { Memory.Note(it.getLong(1), thread, dec(it.getBytes(2)), it.getLong(3)) }
    @Synchronized override fun addNote(thread: String, text: String) { insert("INSERT INTO notes(thread,t,ts) VALUES(?,?,?)", thread, enc(text), System.currentTimeMillis()) }
    @Synchronized override fun deleteTurn(id: Long) { exec("DELETE FROM turns WHERE id=?", id); all().removeAll { it.id == id } }
    @Synchronized override fun deleteNote(id: Long) { exec("DELETE FROM notes WHERE id=?", id) }
    @Synchronized override fun deleteSummary(thread: String) { exec("DELETE FROM summaries WHERE thread=?", thread) }

    @Synchronized override fun clear() {
        runCatching { db().createStatement().use { s -> s.execute("PRAGMA secure_delete=ON"); s.execute("DELETE FROM turns"); s.execute("DELETE FROM notes"); s.execute("DELETE FROM summaries"); s.execute("VACUUM") } }
        runCatching { conn?.close() }; conn = null; cache = null
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
        SecretKeys.destroy(alias); key = null
    }

    data class Stats(val bytes: Long, val turns: Int, val storyTurns: Int, val notes: Int, val summaries: Int)
    @Synchronized fun stats(): Stats {
        if (!file.exists()) return Stats(0, 0, 0, 0, 0)
        val t = all()
        fun count(sql: String) = query(sql) { it.getInt(1) }.firstOrNull() ?: 0
        return Stats(listOf("", "-journal", "-wal").sumOf { File(file.path + it).length() },
            t.count { it.thread == Memory.CHAT }, t.count { it.thread == Memory.STORY }, count("SELECT COUNT(*) FROM notes"), count("SELECT COUNT(*) FROM summaries"))
    }

    private var key: SecretKey? = null
    private fun key(): SecretKey? = key ?: SecretKeys.aes(alias).also { key = it }
    val encrypted: Boolean get() = key() != null
    private fun enc(s: String): ByteArray {
        val k = key() ?: return byteArrayOf(0) + s.toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k)
        return byteArrayOf(1, c.iv.size.toByte()) + c.iv + c.doFinal(s.toByteArray())
    }
    private fun dec(b: ByteArray?): String {
        if (b == null || b.isEmpty()) return ""
        if (b[0].toInt() == 0) return String(b, 1, b.size - 1)
        return runCatching { val n = b[1].toInt(); val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 2, n)); String(c.doFinal(b, 2 + n, b.size - 2 - n)) }.getOrDefault("")
    }

    companion object {
        fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "memory").apply { mkdirs() }
        private val open = HashMap<String, SqlMemoryStore>()
        @Synchronized fun of(ctx: Context, profileId: String) = open.getOrPut(profileId) { SqlMemoryStore(ctx.applicationContext, profileId) }
        @Synchronized fun clearAll(ctx: Context) {
            open.values.forEach { it.clear() }; open.clear()
            dir(ctx).listFiles()?.forEach { f ->
                if (f.name.endsWith(".db")) runCatching { DriverManager.getConnection("jdbc:sqlite:" + f.path).use { c -> c.createStatement().use { s ->
                    s.execute("PRAGMA secure_delete=ON"); s.execute("DELETE FROM turns"); s.execute("DELETE FROM notes"); s.execute("DELETE FROM summaries"); s.execute("VACUUM") } } }
                f.delete()
            }
            SecretKeys.destroyAll("belle_mem_")
        }
    }
}
