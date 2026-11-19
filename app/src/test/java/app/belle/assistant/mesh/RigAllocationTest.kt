package app.belle.assistant.mesh

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The auto-rig must not churn the garbage collector: Android's runtime doesn't remove boxed temporaries the way the
 * desktop JVM does, and in 0.1.4 the first-launch import of the built-in avatars made gigabytes of short-lived
 * objects (1.3 GB for Bob, 2.7 GB for Boob Dragon, measured here), which also stalled the app's UI while it ran.
 * Now it's about 0.3–0.4 GB (mostly whole arrays per pass).
 */
class RigAllocationTest {
    private fun asset(n: String) = listOf(File("src/main/assets/default_avatars/$n"), File("app/src/main/assets/default_avatars/$n")).first { it.exists() }

    @Test fun builtInAvatarsRigWithoutGarbageChurn() {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        for ((f, budgetMb) in listOf("bob.glb" to 600, "boob_dragon.glb" to 800)) {
            val pm = ProgressiveMesh.build(Glb.read(asset(f).readBytes()).withNormals())
            val id = Thread.currentThread().id; val b0 = mx.getThreadAllocatedBytes(id); val t = System.nanoTime()
            AutoRig3D.rig(pm)
            val mb = (mx.getThreadAllocatedBytes(id) - b0) / 1_000_000
            println("$f: rig ${(System.nanoTime() - t) / 1_000_000} ms, allocated $mb MB")
            assertTrue("$f rig allocated $mb MB (budget $budgetMb MB)", mb < budgetMb)
        }
    }
}
