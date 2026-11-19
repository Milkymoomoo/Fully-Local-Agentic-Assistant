package app.belle.desktop.platform

import app.belle.desktop.AtomicFile
import app.belle.desktop.Paths
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Random AES-256 keys, one per alias, stored wrapped: on Windows with DPAPI (CryptProtectData, current user), so only
 * your Windows account on this PC can unwrap them; on Linux the file is readable by your user only (0600) — the
 * libsecret keyring isn't wired up yet, and the docs say so.
 */
object SecretKeys {
    private val dir get() = File(Paths.data, "keys").apply { mkdirs() }
    private fun file(alias: String) = File(dir, "$alias.key")

    @Synchronized fun aes(alias: String): SecretKey? = runCatching {
        val f = file(alias)
        val raw = if (f.isFile) unwrap(f.readBytes()) else ByteArray(32).also { SecureRandom().nextBytes(it); AtomicFile.write(f, wrap(it)); restrict(f) }
        SecretKeySpec(raw, "AES")
    }.getOrNull()

    @Synchronized fun destroy(alias: String) { file(alias).let { if (it.isFile) { it.writeBytes(ByteArray(it.length().toInt())); it.delete() } } }
    @Synchronized fun destroyAll(prefix: String) { dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { destroy(it.name.removeSuffix(".key")) } }

    private fun wrap(b: ByteArray): ByteArray = if (Paths.isWindows) byteArrayOf(1) + com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(b) else byteArrayOf(0) + b
    private fun unwrap(b: ByteArray): ByteArray = when (b[0].toInt()) {
        1 -> com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(b.copyOfRange(1, b.size))
        else -> b.copyOfRange(1, b.size)
    }
    private fun restrict(f: File) { if (!Paths.isWindows) runCatching { java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) } }
}
