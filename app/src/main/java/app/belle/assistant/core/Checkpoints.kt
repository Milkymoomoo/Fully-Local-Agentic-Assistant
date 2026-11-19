package app.belle.assistant.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Crash-safe files. [write] = temp file → fsync → rename (atomic on the same filesystem), so a kill, a dead battery or
 * a reboot leaves either the old file or the new one, never half of one. [sealed] adds a SHA-256 so a file that was
 * cut short or damaged anyway (bad flash, a copy tool) is recognised and ignored instead of being trusted.
 */
object AtomicFiles {
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.flush(); it.fd.sync() }
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "could not replace $target" } }
    }

    private val MAGIC = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte())
    /** [payload] framed as MAGIC | length | payload | sha256(payload). */
    fun seal(payload: ByteArray): ByteArray = ByteArrayOutputStream(payload.size + 44).apply {
        write(MAGIC); write(ByteBuffer.allocate(8).putLong(payload.size.toLong()).array()); write(payload); write(sha256(payload))
    }.toByteArray()
    /** The payload, or null when the frame is incomplete, the wrong length or its checksum doesn't match. */
    fun unseal(b: ByteArray): ByteArray? {
        if (b.size < 44 || !b.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val n = ByteBuffer.wrap(b, 4, 8).long
        if (n < 0 || 12 + n + 32 != b.size.toLong()) return null
        val p = b.copyOfRange(12, 12 + n.toInt())
        return p.takeIf { sha256(it).contentEquals(b.copyOfRange(b.size - 32, b.size)) }
    }
    fun writeSealed(target: File, payload: ByteArray) = write(target, seal(payload))
    fun readSealed(f: File): ByteArray? = runCatching { if (f.isFile) unseal(f.readBytes()) else null }.getOrNull()
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}

/**
 * Checkpoints of a long job, one key per stage. Each save goes to the other of two slots (key.0.ck / key.1.ck), so the
 * previous good checkpoint survives while the next one is written; [load] returns the newest valid one and simply skips
 * a damaged or truncated slot (falling back to the one before it).
 */
class CheckpointStore(val dir: File) {
    fun save(key: String, step: Int, payload: ByteArray) {
        val prev = slots(key).mapNotNull { f -> read(f)?.let { f to it.first } }.maxByOrNull { it.second }
        val slot = if (prev == null) 0 else 1 - prev.first.name.substringAfter("$key.").substringBefore(".ck").toInt()
        val out = ByteArrayOutputStream(); DataOutputStream(out).use { it.writeInt(step); it.write(payload) }
        AtomicFiles.writeSealed(File(dir, "$key.$slot.ck"), out.toByteArray())
    }
    /** (step, payload) of the newest valid checkpoint for [key], or null. */
    fun load(key: String): Pair<Int, ByteArray>? = slots(key).mapNotNull(::read).maxByOrNull { it.first }
    fun has(key: String) = load(key) != null
    fun clear() { dir.deleteRecursively() }
    private fun slots(key: String) = listOf(File(dir, "$key.0.ck"), File(dir, "$key.1.ck"))
    private fun read(f: File): Pair<Int, ByteArray>? = AtomicFiles.readSealed(f)?.let { p ->
        if (p.size < 4) null else DataInputStream(p.inputStream()).use { it.readInt() } to p.copyOfRange(4, p.size) }

    companion object {
        fun floats(a: FloatArray): ByteArray = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(a) }.array()
        fun floats(b: ByteArray, off: Int = 0, n: Int = (b.size - off) / 4): FloatArray =
            FloatArray(n).also { ByteBuffer.wrap(b, off, n * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        fun ints(a: IntArray): ByteArray = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(a) }.array()
        fun ints(b: ByteArray): IntArray = IntArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) }
    }
}
