package app.belle.desktop

import android.content.res.AssetManager
import app.belle.assistant.Prefs
import app.belle.assistant.ProfileStore
import app.belle.assistant.avatar.Avatar3DFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every file of the phone app's assets/ folder reaches the Windows program: readable and listable through the
 * AssetManager shim (dev runs read the source folder; the installers carry the copy in desktop/resources/common/assets),
 * and the program's bundled-assets.txt names exactly those files. Plus: a first run whose default-avatar import failed
 * (Windows builds before 0.1.6) is repaired on the next start without touching an assistant the user made.
 */
class BundledAssetsTest {
    private val src = listOf(File("../app/src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private fun files(root: File) = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    init { TestHome.use() }

    @Test fun everyPhoneAssetOpensAndLists() {
        val am = DesktopContext.assets
        val all = files(src); assertTrue(all.size >= 10, "only ${all.size} assets in $src")
        for (p in all) {
            assertContentEquals(File(src, p).readBytes(), am.open(p).use { it.readBytes() }, p)
            val dir = p.substringBeforeLast('/', "")
            assertTrue(p.substringAfterLast('/') in am.list(dir), "$p not in list('$dir')")
        }
        // what the shared code does with them: the kws model folder, the cut-out model, the 3D model list
        assertTrue(am.list("models/kws").contains("tokens.txt"))
        assertTrue(am.list("default_avatars").toList().containsAll(listOf("bob.glb", "boob_dragon.glb", "boob_dragon.webp")))
        assertTrue(am.list("pixal3d_models.json").isEmpty() && am.list("no/such").isEmpty())
    }

    @Test fun programListMatchesTheAssetsFolder() {
        val listed = BundledAssets.expected()
        assertEquals(files(src), listed.map { it.path })
        listed.forEach { assertEquals(File(src, it.path).length(), it.size, it.path) }
        println("BundledAssets (dev): " + BundledAssets.verify(DesktopContext.assets))
    }

    /** The copy the MSI / setup program / portable folder carry (build: syncAppAssets → resources/common/assets). */
    @Test fun installerCopyIsComplete() {
        val copy = File("resources/common/assets")
        assertTrue(copy.isDirectory, "syncAppAssets didn't run: no $copy")
        assertEquals(files(src), files(copy))
        println("BundledAssets (installer copy): " + BundledAssets.verify(AssetManager(copy)))
    }

    @Test fun brokenFirstRunIsRepaired() {
        app.belle.assistant.core.Platform.desktop = true
        val ctx = DesktopContext
        // the state a 0.1.5 PC was left in: defaults done, Boob Dragon without her picture, no 3D models
        val g = ctx.getSharedPreferences("belle_global", 0)
        g.edit().putBoolean("defaults_v2", true).remove("defaults_repair_v3").remove("defaults_pending").commit()
        val boob = ProfileStore.ids(ctx).firstOrNull { ProfileStore.name(ctx, it) == ProfileStore.BOOB_NAME } ?: ProfileStore.create(ctx, ProfileStore.BOOB_NAME)
        val mine = ProfileStore.create(ctx, "My Own")
        Avatar3DFiles.remove(ctx, ProfileStore.DEFAULT_ID); Avatar3DFiles.remove(ctx, boob)
        assertEquals("", Prefs(ctx, boob).avatarFile)

        ProfileStore.ensureDefaults(ctx)
        assertEquals("avatar_boob_dragon.webp", Prefs(ctx, boob).avatarFile)
        assertTrue(File(ctx.filesDir, "avatar_boob_dragon.webp").length() > 0)
        val end = System.currentTimeMillis() + 240_000
        while (ProfileStore.pending(ctx).isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(250)
        assertEquals(emptyList(), ProfileStore.pending(ctx), "default 3D imports didn't finish")
        assertTrue(Avatar3DFiles.has(ctx, ProfileStore.DEFAULT_ID), "Bob has no 3D model")
        assertTrue(Avatar3DFiles.has(ctx, boob), "Boob Dragon has no 3D model")
        assertTrue(!Avatar3DFiles.has(ctx, mine) && Prefs(ctx, mine).avatarFile.isEmpty(), "a user-made assistant was changed")

        // the repair runs once: removing a default model afterwards is the user's choice and sticks
        Avatar3DFiles.remove(ctx, boob)
        ProfileStore.ensureDefaults(ctx); Thread.sleep(500)
        assertTrue(!Avatar3DFiles.has(ctx, boob) && ProfileStore.pending(ctx).isEmpty())
    }
}
