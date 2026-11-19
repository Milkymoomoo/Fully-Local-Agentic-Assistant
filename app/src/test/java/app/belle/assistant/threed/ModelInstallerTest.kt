package app.belle.assistant.threed

import app.belle.assistant.models.Fetcher
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.random.Random

/** The "Install 3D generation" downloader against a local HTTP server: Range resume, 200/416 handling, redirects,
 *  host blocking, split files, checksum failure + per-file retry, retry-all and cancel. */
class ModelInstallerTest {
    private lateinit var server: HttpServer
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val dropAfter = ConcurrentHashMap<String, Int>()      // path -> bytes to send before cutting the connection (once)
    private val ignoreRange = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var slowMs = 0L
    private val ranges = CopyOnWriteArrayList<String>()
    private lateinit var base: String
    private lateinit var dir: File
    private val fetcher = Fetcher(hostOk = { u, _ -> u.host == "127.0.0.1" }, connectTimeoutMs = 3000, readTimeoutMs = 1500)

    @Before fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/")
            try {
                if (path.startsWith("redirect/")) { ex.responseHeaders.add("Location", "/" + path.removePrefix("redirect/")); ex.sendResponseHeaders(302, -1); return@createContext }
                if (path.startsWith("evil/")) { ex.responseHeaders.add("Location", "https://example.com/x"); ex.sendResponseHeaders(302, -1); return@createContext }
                val body = files[path] ?: run { ex.sendResponseHeaders(404, -1); return@createContext }
                val range = ex.requestHeaders.getFirst("Range")
                var from = 0
                if (range != null && path !in ignoreRange) {
                    ranges += "$path:$range"
                    from = range.removePrefix("bytes=").substringBefore('-').toInt()
                    if (from >= body.size) { ex.responseHeaders.add("Content-Range", "bytes */${body.size}"); ex.sendResponseHeaders(416, -1); return@createContext }
                    ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                    ex.sendResponseHeaders(206, (body.size - from).toLong())
                } else ex.sendResponseHeaders(200, body.size.toLong())
                val cut = dropAfter.remove(path)
                ex.responseBody.use { o ->
                    var i = from
                    while (i < body.size) {
                        val n = minOf(8192, body.size - i)
                        if (cut != null && i - from + n > cut) { o.write(body, i, cut - (i - from)); o.flush(); throw java.io.IOException("cut") }
                        o.write(body, i, n); i += n
                        if (slowMs > 0) Thread.sleep(slowMs)
                    }
                }
            } catch (_: Exception) { } finally { ex.close() }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/"
        dir = Files.createTempDirectory("p3inst").toFile()
    }
    @After fun down() { server.stop(0); dir.deleteRecursively() }

    private fun rnd(n: Int, seed: Int) = Random(seed).nextBytes(n)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun resumesWithRangeAfterADrop() {
        val data = rnd(300_000, 1); files["a.bin"] = data; dropAfter["a.bin"] = 120_000
        val dest = File(dir, "a.bin")
        try { fetcher.fetch(base + "a.bin", dest, data.size.toLong(), sha(data)); fail("should report the drop") } catch (e: Fetcher.Failed) { assertTrue(e.message!!.contains("dropped")) }
        val have = File(dir, "a.bin.part").length()
        assertTrue("partial kept: $have", have in 1..299_999)
        fetcher.fetch(base + "a.bin", dest, data.size.toLong(), sha(data))
        assertTrue(ranges.contains("a.bin:bytes=$have-"))
        assertTrue(dest.readBytes().contentEquals(data)); assertFalse(File(dir, "a.bin.part").exists())
    }

    @Test fun serverIgnoringRangeRestartsCleanly() {
        val data = rnd(100_000, 2); files["b.bin"] = data; ignoreRange += "b.bin"
        File(dir, "b.bin.part").writeBytes(data.copyOf(40_000).also { it.fill(7) })   // stale garbage partial
        fetcher.fetch(base + "b.bin", File(dir, "b.bin"), data.size.toLong(), sha(data))
        assertTrue(File(dir, "b.bin").readBytes().contentEquals(data))
    }

    @Test fun completePartGets416AndIsStillVerified() {
        val data = rnd(50_000, 3); files["c.bin"] = data
        File(dir, "c.bin.part").writeBytes(data)
        fetcher.fetch(base + "c.bin", File(dir, "c.bin"), data.size.toLong(), sha(data))
        assertTrue(File(dir, "c.bin").readBytes().contentEquals(data))
    }

    @Test fun followsAllowedRedirectsAndBlocksOthers() {
        val data = rnd(10_000, 4); files["d.bin"] = data
        fetcher.fetch(base + "redirect/d.bin", File(dir, "d.bin"), data.size.toLong(), sha(data))
        assertTrue(File(dir, "d.bin").exists())
        try { fetcher.fetch(base + "evil/x", File(dir, "x"), 1, ""); fail() } catch (e: Fetcher.Failed) { assertTrue(e.message!!.startsWith("Blocked redirect")) }
        try { Fetcher(hostOk = { u, _ -> u.host == "github.com" }).fetch(base + "d.bin", File(dir, "y"), 1, ""); fail() } catch (e: Fetcher.Failed) { assertTrue(e.message!!.startsWith("Blocked")) }
    }

    @Test fun checksumMismatchDeletesTheFile() {
        val data = rnd(20_000, 5); files["e.bin"] = data
        try { fetcher.fetch(base + "e.bin", File(dir, "e.bin"), data.size.toLong(), "00".repeat(32)); fail() } catch (e: Fetcher.Failed) { assertTrue(e.message!!.contains("Checksum")) }
        assertFalse(File(dir, "e.bin").exists()); assertFalse(File(dir, "e.bin.part").exists())
    }

    private fun manifest(vararg f: JSONObject) = ModelManifest.parse(JSONObject().put("version", "t").put("release", base)
        .put("files", JSONArray(f.toList()))
        .put("licenses", JSONArray().put(JSONObject().put("id", "dinov3").put("title", "DINOv3 License").put("mustAccept", true).put("files", JSONArray().put("one.onnx")))).toString())

    private fun waitIdle(i: ModelInstaller) { val t = System.currentTimeMillis(); Thread.sleep(50); while (i.busy) { Thread.sleep(20); if (System.currentTimeMillis() - t > 20_000) fail("timeout") } }

    @Test fun installsSplitAndPlainFilesRetriesFailuresAndResumesAfterCancel() {
        val one = rnd(200_000, 10); val big = rnd(500_000, 11); val bad = rnd(30_000, 12)
        files["one.onnx"] = one; dropAfter["one.onnx"] = 50_000             // auto-retry resumes it
        files["big.001"] = big.copyOfRange(0, 300_000); files["big.002"] = big.copyOfRange(300_000, 500_000)
        files["bad.onnx"] = bad.copyOf().also { it[5] = (it[5] + 1).toByte() }  // served damaged
        val m = manifest(
            JSONObject().put("name", "one.onnx").put("size", one.size).put("sha256", sha(one)).put("url", base + "one.onnx"),
            JSONObject().put("name", "big.onnx.data").put("size", big.size).put("sha256", sha(big)).put("parts", JSONArray()
                .put(JSONObject().put("asset", "big.001").put("url", base + "big.001").put("size", 300_000).put("sha256", sha(files["big.001"]!!)))
                .put(JSONObject().put("asset", "big.002").put("url", base + "redirect/big.002").put("size", 200_000).put("sha256", sha(files["big.002"]!!)))),
            JSONObject().put("name", "bad.onnx").put("size", bad.size).put("sha256", sha(bad)).put("url", base + "bad.onnx"))
        assertTrue(m.licenses.single().mustAccept)
        val inst = ModelInstaller(dir, m, fetcher, autoRetries = 1, backoffMs = { 10 })
        assertTrue(m.files.all { inst.status[it.name]!!.phase == ModelInstaller.Phase.WAITING })
        inst.installAll(); waitIdle(inst)
        assertEquals(ModelInstaller.Phase.DONE, inst.status["one.onnx"]!!.phase)
        assertEquals(ModelInstaller.Phase.DONE, inst.status["big.onnx.data"]!!.phase)
        assertEquals(ModelInstaller.Phase.FAILED, inst.status["bad.onnx"]!!.phase)
        assertTrue(inst.status["bad.onnx"]!!.msg.contains("Checksum"))
        assertTrue(File(dir, "big.onnx.data").readBytes().contentEquals(big))
        assertEquals(sha(big), File(dir, "big.onnx.data.sha256").readText())
        assertTrue(File(dir, ".parts").listFiles()!!.isEmpty())
        assertTrue(ranges.any { it.startsWith("one.onnx:bytes=") })
        assertFalse(inst.allDone)
        files["bad.onnx"] = bad                                            // fixed upstream: the per-file retry succeeds
        inst.retry("bad.onnx"); waitIdle(inst)
        assertTrue(inst.allDone)
        // A fresh installer (app restart) sees everything installed without downloading.
        val again = ModelInstaller(dir, m, fetcher); assertTrue(again.allDone); assertEquals(0L, again.remainingBytes())
    }

    @Test fun cancelKeepsThePartialAndRetryAllFinishes() {
        val data = rnd(400_000, 20); files["slow.onnx"] = data; slowMs = 15
        val m = manifest(JSONObject().put("name", "slow.onnx").put("size", data.size).put("sha256", sha(data)).put("url", base + "slow.onnx"))
        val inst = ModelInstaller(dir, m, fetcher, autoRetries = 0)
        inst.installAll()
        val t = System.currentTimeMillis(); while (File(dir, "slow.onnx.part").length() < 60_000) { Thread.sleep(10); if (System.currentTimeMillis() - t > 10_000) fail("no progress") }
        inst.cancel(); waitIdle(inst)
        assertEquals(ModelInstaller.Phase.CANCELLED, inst.status["slow.onnx"]!!.phase)
        val kept = File(dir, "slow.onnx.part").length(); assertTrue(kept in 60_000 until data.size)
        assertTrue(inst.remainingBytes() < data.size)
        slowMs = 0; inst.retryAll(); waitIdle(inst)
        assertTrue(inst.allDone); assertTrue(ranges.contains("slow.onnx:bytes=$kept-") || ranges.any { it.startsWith("slow.onnx:bytes=") })
    }

    @Test fun shippedManifestPointsAtTheReleaseAndFitsGithubLimits() {
        val f = listOf(File("src/main/assets/pixal3d_models.json"), File("app/src/main/assets/pixal3d_models.json")).first { it.exists() }
        val m = ModelManifest.parse(f.readText())
        val rel = "https://github.com/Milkymoomoo/Fully-Local-Agentic-Assistant/releases/download/"
        assertTrue(m.files.size >= 18)
        for (e in m.files) {
            val urls = if (e.parts.isEmpty()) listOf(e.url) else e.parts.map { it.url }
            urls.forEach { assertTrue(it, it.startsWith(rel)) }
            (if (e.parts.isEmpty()) listOf(e.size) else e.parts.map { it.size }).forEach { assertTrue(it < 2L * 1024 * 1024 * 1024) }
            assertEquals(64, e.sha256.length)
        }
        assertEquals(Pixal3DJob.REQUIRED.toSet() - m.files.map { it.name }.toSet(), emptySet<String>())
        assertTrue(m.licenses.any { it.id == "dinov3" && it.mustAccept && it.files.isNotEmpty() })
    }

    // ---- t529u: one after another, automatic retries, resume after the app is killed / the phone restarts ----

    private fun plain(name: String, data: ByteArray) = JSONObject().put("name", name).put("size", data.size).put("sha256", sha(data)).put("url", base + name)

    @Test fun downloadsOneFileAtATimeInManifestOrder() {
        val a = rnd(120_000, 30); val b = rnd(120_000, 31); val c = rnd(120_000, 32)
        files["a.onnx"] = a; files["b.onnx"] = b; files["c.onnx"] = c; slowMs = 2
        val inst = ModelInstaller(dir, manifest(plain("a.onnx", a), plain("b.onnx", b), plain("c.onnx", c)), fetcher)
        val maxActive = java.util.concurrent.atomic.AtomicInteger(0); val order = CopyOnWriteArrayList<String>()
        inst.addListener {
            val active = inst.status.filter { it.value.phase == ModelInstaller.Phase.DOWNLOADING }.keys
            maxActive.accumulateAndGet(active.size) { x, y -> maxOf(x, y) }
            active.forEach { if (it !in order) order += it }
        }
        inst.installAll(); waitIdle(inst)
        assertTrue(inst.allDone)
        assertEquals("never two files at once", 1, maxActive.get())
        assertEquals(listOf("a.onnx", "b.onnx", "c.onnx"), order.toList())
    }

    @Test fun transientDropsAreRetriedAutomaticallyWithoutTheRetryButton() {
        val data = rnd(250_000, 33); files["flaky.onnx"] = data; dropAfter["flaky.onnx"] = 40_000
        val inst = ModelInstaller(dir, manifest(plain("flaky.onnx", data)), fetcher, backoffMs = { 10 })   // default retry count
        inst.installAll(); waitIdle(inst)
        assertEquals(ModelInstaller.Phase.DONE, inst.status["flaky.onnx"]!!.phase)
        assertTrue("resumed with Range", ranges.any { it.startsWith("flaky.onnx:bytes=") })
    }

    @Test fun defaultBackoffGrowsAndIsCapped() {
        val inst = ModelInstaller(dir, manifest())
        val f = ModelInstaller::class.java.getDeclaredField("backoffMs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val b = f.get(inst) as (Int) -> Long
        assertEquals(2000L, b(0)); assertEquals(4000L, b(1)); assertEquals(8000L, b(2)); assertEquals(60_000L, b(10))
    }

    @Test fun anInstallCutShortByAKilledAppResumesInANewInstaller() {
        val data = rnd(400_000, 34); files["big.onnx"] = data; slowMs = 15
        val m = manifest(plain("big.onnx", data))
        val first = ModelInstaller(dir, m, fetcher, autoRetries = 0)
        assertFalse(first.wanted)
        first.installAll()
        val t = System.currentTimeMillis(); while (File(dir, "big.onnx.part").length() < 60_000) { Thread.sleep(10); if (System.currentTimeMillis() - t > 10_000) fail("no progress") }
        assertTrue(first.wanted)
        // Simulate the process dying: the worker stops without the user cancelling, so the wish to install remains.
        first.javaClass.getDeclaredField("cancelAll").apply { isAccessible = true }.setBoolean(first, true); waitIdle(first)
        assertTrue(File(dir, ".install_wanted").exists())
        slowMs = 0
        val after = ModelInstaller(dir, m, fetcher)          // app restarted / phone rebooted
        assertTrue(after.wanted)
        assertTrue(after.resumeIfWanted()); waitIdle(after)
        assertTrue(after.allDone); assertFalse(after.wanted); assertFalse(File(dir, ".install_wanted").exists())
        assertTrue(ranges.any { it.startsWith("big.onnx:bytes=") })
        assertFalse("nothing left to resume", ModelInstaller(dir, m, fetcher).resumeIfWanted())
    }

    @Test fun userCancelMeansNoAutomaticResume() {
        val data = rnd(400_000, 35); files["c2.onnx"] = data; slowMs = 15
        val m = manifest(plain("c2.onnx", data))
        val inst = ModelInstaller(dir, m, fetcher, autoRetries = 0)
        inst.installAll()
        val t = System.currentTimeMillis(); while (File(dir, "c2.onnx.part").length() < 30_000) { Thread.sleep(10); if (System.currentTimeMillis() - t > 10_000) fail("no progress") }
        inst.cancel(); waitIdle(inst)
        assertFalse(inst.wanted)
        assertFalse(ModelInstaller(dir, m, fetcher).resumeIfWanted())
    }

    @Test fun readyCountCountsVerifiedFiles() {
        val a = rnd(10_000, 36); val b = rnd(10_000, 37); files["ra.onnx"] = a; files["rb.onnx"] = b
        val m = manifest(plain("ra.onnx", a), plain("rb.onnx", b))
        val inst = ModelInstaller(dir, m, fetcher)
        assertEquals(0, inst.readyCount())
        inst.retry("ra.onnx"); waitIdle(inst)
        assertEquals(1, inst.readyCount()); assertTrue(inst.wanted)
        inst.installAll(); waitIdle(inst)
        assertEquals(2, inst.readyCount()); assertFalse(inst.wanted)
    }
}
