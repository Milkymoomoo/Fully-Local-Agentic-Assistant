package app.belle.assistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.belle.assistant.core.Memory
import app.belle.assistant.core.MemoryStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One profile's memory: an app-private SQLite file (no-backup storage, never uploaded, excluded from Android backups),
 * with every text column encrypted with AES-256-GCM under a key held in the Android Keystore (the key never leaves the
 * phone's secure hardware where available). Clearing deletes the rows, VACUUMs, deletes the files and destroys the key,
 * so even leftover flash blocks can't be read back.
 */
class SqlMemoryStore(private val ctx: Context, val profileId: String) : MemoryStore {
    private val alias = "belle_mem_" + profileId.replace(Regex("[^A-Za-z0-9_]"), "_")
    val file: File get() = File(dir(ctx), "${alias}.db")
    private var helper: Helper? = null

    private inner class Helper : SQLiteOpenHelper(ctx, file.path, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }; db.disableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE turns(id INTEGER PRIMARY KEY AUTOINCREMENT, thread TEXT, u BLOB, r BLOB, ts INTEGER, summarized INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT, thread TEXT, t BLOB, ts INTEGER)")
            db.execSQL("CREATE TABLE summaries(thread TEXT PRIMARY KEY, t BLOB, ts INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    private fun db(): SQLiteDatabase = synchronized(this) { (helper ?: Helper().also { helper = it }).writableDatabase }

    // Decrypted cache: memory is small (a few thousand lines at most) and BM25 needs the plain text.
    private var cache: MutableList<Memory.Turn>? = null
    private fun all(): MutableList<Memory.Turn> = cache ?: db().rawQuery("SELECT id,thread,u,r,ts,summarized FROM turns ORDER BY id", null).use { c ->
        val l = mutableListOf<Memory.Turn>()
        while (c.moveToNext()) l += Memory.Turn(c.getLong(0), c.getString(1), dec(c.getBlob(2)), dec(c.getBlob(3)), c.getLong(4), c.getInt(5) != 0)
        l
    }.also { cache = it }

    @Synchronized override fun addTurn(thread: String, user: String, reply: String): Memory.Turn {
        val ts = System.currentTimeMillis()
        val id = db().insert("turns", null, ContentValues().apply { put("thread", thread); put("u", enc(user)); put("r", enc(reply)); put("ts", ts) })
        return Memory.Turn(id, thread, user, reply, ts).also { all().add(it) }
    }
    @Synchronized override fun turns(thread: String) = all().filter { it.thread == thread }
    @Synchronized override fun markSummarized(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db().execSQL("UPDATE turns SET summarized=1 WHERE id IN (${ids.joinToString(",")})")
        all().replaceAll { if (it.id in ids) it.copy(summarized = true) else it }
    }
    @Synchronized override fun summary(thread: String): Memory.Summary? =
        db().rawQuery("SELECT t,ts FROM summaries WHERE thread=?", arrayOf(thread)).use { c -> if (c.moveToNext()) Memory.Summary(thread, dec(c.getBlob(0)), c.getLong(1)) else null }
    @Synchronized override fun setSummary(thread: String, text: String) {
        db().insertWithOnConflict("summaries", null, ContentValues().apply { put("thread", thread); put("t", enc(text)); put("ts", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized override fun notes(thread: String): List<Memory.Note> =
        db().rawQuery("SELECT id,t,ts FROM notes WHERE thread=? ORDER BY id", arrayOf(thread)).use { c ->
            val l = mutableListOf<Memory.Note>(); while (c.moveToNext()) l += Memory.Note(c.getLong(0), thread, dec(c.getBlob(1)), c.getLong(2)); l }
    @Synchronized override fun addNote(thread: String, text: String) {
        db().insert("notes", null, ContentValues().apply { put("thread", thread); put("t", enc(text)); put("ts", System.currentTimeMillis()) })
    }
    @Synchronized override fun deleteTurn(id: Long) { db().delete("turns", "id=?", arrayOf(id.toString())); all().removeAll { it.id == id } }
    @Synchronized override fun deleteNote(id: Long) { db().delete("notes", "id=?", arrayOf(id.toString())) }
    @Synchronized override fun deleteSummary(thread: String) { db().delete("summaries", "thread=?", arrayOf(thread)) }

    /** Secure wipe: rows deleted (secure_delete zeroes the pages), VACUUM, files deleted, Keystore key destroyed. */
    @Synchronized override fun clear() {
        runCatching { val d = db(); d.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }; d.execSQL("DELETE FROM turns"); d.execSQL("DELETE FROM notes"); d.execSQL("DELETE FROM summaries"); d.execSQL("VACUUM") }
        runCatching { helper?.close() }; helper = null; cache = null
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
        key = null
    }

    data class Stats(val bytes: Long, val turns: Int, val storyTurns: Int, val notes: Int, val summaries: Int)
    @Synchronized fun stats(): Stats {
        if (!file.exists()) return Stats(0, 0, 0, 0, 0)
        val t = all()
        fun count(sql: String) = db().rawQuery(sql, null).use { if (it.moveToNext()) it.getInt(0) else 0 }
        return Stats(listOf("", "-journal", "-wal").sumOf { File(file.path + it).length() },
            t.count { it.thread == Memory.CHAT }, t.count { it.thread == Memory.STORY },
            count("SELECT COUNT(*) FROM notes"), count("SELECT COUNT(*) FROM summaries"))
    }

    // --- AES-GCM with a Keystore key; falls back to storing plain text only if the Keystore is unusable. ---
    private var key: SecretKey? = null
    private fun key(): SecretKey? = key ?: runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }.getOrNull().also { key = it }
    val encrypted: Boolean get() = key() != null

    private fun enc(s: String): ByteArray {
        val k = key() ?: return byteArrayOf(0) + s.toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k)
        return byteArrayOf(1, c.iv.size.toByte()) + c.iv + c.doFinal(s.toByteArray())
    }
    private fun dec(b: ByteArray?): String {
        if (b == null || b.isEmpty()) return ""
        if (b[0].toInt() == 0) return String(b, 1, b.size - 1)
        return runCatching {
            val n = b[1].toInt(); val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 2, n))
            String(c.doFinal(b, 2 + n, b.size - 2 - n))
        }.getOrDefault("")
    }

    companion object {
        fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "memory").apply { mkdirs() }
        private val open = HashMap<String, SqlMemoryStore>()
        @Synchronized fun of(ctx: Context, profileId: String) = open.getOrPut(profileId) { SqlMemoryStore(ctx.applicationContext, profileId) }
        /** Wipes every profile's memory, including stores of deleted profiles. */
        @Synchronized fun clearAll(ctx: Context) {
            open.values.forEach { it.clear() }; open.clear()
            dir(ctx).listFiles()?.forEach { f ->
                if (f.name.endsWith(".db")) runCatching { SQLiteDatabase.openDatabase(f.path, null, 0).use { d -> d.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }; d.execSQL("DELETE FROM turns"); d.execSQL("DELETE FROM notes"); d.execSQL("DELETE FROM summaries"); d.execSQL("VACUUM") } }
                f.delete()
            }
            runCatching { val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; ks.aliases().toList().filter { it.startsWith("belle_mem_") }.forEach { ks.deleteEntry(it) } }
        }
    }
}
