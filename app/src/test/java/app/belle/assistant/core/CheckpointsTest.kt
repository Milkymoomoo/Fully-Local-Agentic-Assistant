package app.belle.assistant.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CheckpointsTest {
    private fun tmp(): File = Files.createTempDirectory("ck").toFile().also { it.deleteOnExit() }

    @Test fun atomicWriteLeavesOldFileWhenKilledMidWrite() {
        val d = tmp(); val f = File(d, "a.bin")
        AtomicFiles.write(f, "old".toByteArray())
        File(d, "a.bin.tmp").writeBytes("half-writ".toByteArray())        // a kill before the rename
        assertEquals("old", f.readText())
        AtomicFiles.write(f, "new".toByteArray())                          // the next write simply replaces the stale temp
        assertEquals("new", f.readText()); assertEquals(false, File(d, "a.bin.tmp").exists())
    }

    @Test fun sealedRejectsTruncatedAndCorrupt() {
        val p = ByteArray(1000) { it.toByte() }; val s = AtomicFiles.seal(p)
        assertArrayEquals(p, AtomicFiles.unseal(s))
        assertNull(AtomicFiles.unseal(s.copyOf(s.size - 1)))                // truncated
        assertNull(AtomicFiles.unseal(s.copyOf(20)))
        assertNull(AtomicFiles.unseal(s.clone().also { it[500] = (it[500] + 1).toByte() }))   // flipped byte
        assertNull(AtomicFiles.unseal(ByteArray(0)))
    }

    @Test fun storeFallsBackToPreviousSlotWhenNewestIsDamaged() {
        val st = CheckpointStore(tmp())
        st.save("k", 3, byteArrayOf(3)); st.save("k", 4, byteArrayOf(4))
        assertEquals(4, st.load("k")!!.first)
        // the slot holding step 4 is cut short (bad flash / a copy tool): step 3 is used instead
        val newest = st.dir.listFiles()!!.first { AtomicFiles.readSealed(it)?.let { b -> b[3] == 4.toByte() } == true }
        newest.writeBytes(newest.readBytes().copyOf(10))
        assertEquals(3 to 3.toByte(), st.load("k")!!.let { it.first to it.second[0] })
        // both damaged: nothing, so the job starts that stage over
        st.dir.listFiles()!!.forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(st.load("k"))
        // saving again after damage works and alternates slots
        st.save("k", 7, byteArrayOf(7)); st.save("k", 8, byteArrayOf(8)); st.save("k", 9, byteArrayOf(9))
        assertEquals(9, st.load("k")!!.first); assertEquals(2, st.dir.listFiles()!!.count { it.name.endsWith(".ck") })
    }

    @Test fun floatsRoundTrip() {
        val a = FloatArray(77) { it * 0.37f - 5f }
        assertArrayEquals(a, CheckpointStore.floats(CheckpointStore.floats(a)), 0f)
        val i = IntArray(9) { it * 1001 - 3 }
        assertArrayEquals(i, CheckpointStore.ints(CheckpointStore.ints(i)))
    }
}
