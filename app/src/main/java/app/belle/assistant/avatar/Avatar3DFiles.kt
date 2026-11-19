package app.belle.assistant.avatar

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import app.belle.assistant.Prefs
import app.belle.assistant.core.Framing
import app.belle.assistant.core.Shader3D
import app.belle.assistant.mesh.AutoRig3D
import app.belle.assistant.mesh.Glb
import app.belle.assistant.mesh.Import3D
import app.belle.assistant.mesh.Mesh3D
import app.belle.assistant.mesh.ProgressiveMesh
import org.json.JSONObject
import java.io.File

/**
 * A profile's 3D avatar: ONE stored mesh (avatar.glb, standard glTF with the progressive-mesh tables and the rig
 * inside), its info (avatar3d.json) and a still preview (preview.png, the widget's fallback).
 * Import is the first-class path: a PC-made .glb/.gltf/.obj/.ply needs none of the generator downloads.
 */
object Avatar3DFiles {
    fun dir(ctx: Context, profileId: String) = File(ctx.filesDir, "avatar3d/$profileId")
    fun mesh(ctx: Context, profileId: String) = File(dir(ctx, profileId), "avatar.glb")
    fun preview(ctx: Context, profileId: String) = File(dir(ctx, profileId), "preview.png")
    fun info(ctx: Context, profileId: String): JSONObject? = runCatching { JSONObject(File(dir(ctx, profileId), "avatar3d.json").readText()) }.getOrNull()
    @Volatile private var upgrading = false
    /**
     * 3D avatars saved by an older app version carry older detail-level tables; they're ignored on load. Rebuild
     * them once, quietly, so the first 3D frame isn't delayed (the renderer would otherwise rebuild on first use).
     */
    fun upgradeDetailLevelsInBackground(ctx: Context) {
        if (upgrading) return; upgrading = true
        Thread({
            try {
                for (id in app.belle.assistant.ProfileStore.ids(ctx)) {
                    val f = mesh(ctx, id); if (f.length() == 0L) continue
                    runCatching {
                        var m = app.belle.assistant.mesh.Glb.read(f.readBytes())
                        var changed = false
                        if (!m.hasPm) { m = app.belle.assistant.mesh.ProgressiveMesh.build(m.withNormals()); changed = true
                            android.util.Log.i("Avatar3D", "upgraded detail levels for $id") }
                        // auto-rigged by an older rigger (e.g. before the whole-lower-jaw hinge): rig again
                        val inf = info(ctx, id)
                        if (inf != null && inf.optString("rig") != "file" && inf.optInt("rigVersion", 1) < AutoRig3D.VERSION) {
                            val r = AutoRig3D.rig(m.withNormals(), inf.optString("rig", "skinned").ifEmpty { "skinned" }); m = r.mesh; changed = true
                            inf.put("rigVersion", AutoRig3D.VERSION).put("jawVertices", r.jawVertices).put("mouth", org.json.JSONArray(r.mouth.toList()))
                            File(dir(ctx, id), "avatar3d.json").writeText(inf.toString(2))
                            android.util.Log.i("Avatar3D", "re-rigged $id (rig v${AutoRig3D.VERSION}, jaw ${r.jawVertices} vertices, snout=${r.jaw?.snout})")
                        }
                        if (changed) {
                            val tmp = File(f.path + ".pm"); tmp.writeBytes(app.belle.assistant.mesh.Glb.write(m))
                            Avatar3DRenderer.forget(f); tmp.renameTo(f)
                        }
                    }.onFailure { android.util.Log.w("Avatar3D", "detail-level upgrade failed for $id", it) }
                }
            } finally { upgrading = false }
        }, "belle-pm-upgrade").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun has(ctx: Context, profileId: String) = mesh(ctx, profileId).length() > 0
    fun remove(ctx: Context, profileId: String) {
        Avatar3DRenderer.forget(mesh(ctx, profileId)); dir(ctx, profileId).deleteRecursively()
        runCatching { app.belle.assistant.ui.AssistantWidget.avatarChanged(ctx) }   // back to the 2D picture right away
    }

    /** The imported ComfyUI TRELLIS 2 / Pixal3D workflow (shared by all assistants); drives the photo → 3D run. */
    fun workflow(ctx: Context) = File(ctx.filesDir, "workflow3d.json")
    /** Your per-value edits on top of the workflow (see core.Workflow3DSettings.withOverrides). */
    fun overrides(ctx: Context) = File(ctx.filesDir, "workflow3d_overrides.json")
    /** Name of the imported workflow file and when it was imported ("name|epochMs"). */
    fun workflowSource(ctx: Context) = File(ctx.filesDir, "workflow3d_source.txt")
    /** The workflow as imported (no edits), or the phone defaults when none is imported or it can't be read. */
    fun baseSettings(ctx: Context): app.belle.assistant.core.Workflow3DSettings =
        runCatching { app.belle.assistant.core.Workflow3DSettings.parse(workflow(ctx).readText()) }.getOrDefault(app.belle.assistant.core.Workflow3DSettings.PHONE_DEFAULT)
    fun overridesMap(ctx: Context): MutableMap<String, Any?> = app.belle.assistant.core.Workflow3DOverrides.read(runCatching { overrides(ctx).readText() }.getOrNull())
    fun saveOverrides(ctx: Context, o: Map<String, Any?>) { if (o.isEmpty()) overrides(ctx).delete() else overrides(ctx).writeText(app.belle.assistant.core.Workflow3DOverrides.write(o)) }

    /** Import progress for the UI: (step label, 0..1), or null when idle. */
    @Volatile var progress: Pair<String, Float>? = null
    @Volatile var lastResult: String? = null; private set
    /** true while the first launch prepares the bundled avatars (the UI says so instead of "Importing"). */
    @Volatile var builtIn = false

    /**
     * Import pregenerated model files (by lower-case name) for [profileId]: read → progressive mesh → auto-rig
     * (unless the file has its own skeleton, which is kept with its animations) → mouth → save → preview.
     * Blocking; call from a background thread. Returns a one-line summary.
     */
    fun import(ctx: Context, profileId: String, files: Map<String, ByteArray>, rigStyle: String = Prefs(ctx, profileId).rigStyle3d): String {
        val t0 = SystemClock.elapsedRealtime(); val rt = Runtime.getRuntime()
        var peakHeap = 0L; var peakNative = 0L
        fun mem() { peakHeap = maxOf(peakHeap, rt.totalMemory() - rt.freeMemory()); peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize()) }
        try {
            progress = "Reading the model" to 0.02f
            var m: Mesh3D = Import3D.read(files).withNormals(); mem()
            val tRead = SystemClock.elapsedRealtime()
            progress = "Building detail levels (${"%,d".format(m.triCount)} triangles)" to 0.1f
            m = ProgressiveMesh.build(m) { f -> progress = "Building detail levels" to 0.1f + 0.7f * f; mem() }; mem()
            val tPm = SystemClock.elapsedRealtime()
            val ownRig = m.skeleton != null && m.joints != null
            var mouth: FloatArray? = null; var jaw = 0
            if (!ownRig) {
                progress = "Rigging bones and placing the mouth" to 0.82f
                val r = AutoRig3D.rig(m, rigStyle); m = r.mesh; mouth = r.mouth; jaw = r.jawVertices
            }
            mem(); val tRig = SystemClock.elapsedRealtime()
            progress = "Saving" to 0.9f
            val d = dir(ctx, profileId).apply { mkdirs() }
            // the commit: temp file + fsync + rename, so the assistant has either its old model or the whole new one
            val bytes = Glb.write(m); mem()
            Avatar3DRenderer.forget(mesh(ctx, profileId)); app.belle.assistant.core.AtomicFiles.write(mesh(ctx, profileId), bytes)
            val info = JSONObject().put("vertices", m.vertexCount).put("triangles", m.triCount)
                .put("rig", if (ownRig) "file" else rigStyle).put("rigVersion", AutoRig3D.VERSION).put("bones", m.skeleton?.size ?: 0)
                .put("animations", org.json.JSONArray(m.animations.map { it.name })).put("jawVertices", jaw)
                .put("mouth", mouth?.let { org.json.JSONArray(it.toList()) }).put("hasTexture", m.texture != null)
                .put("importMs", SystemClock.elapsedRealtime() - t0).put("readMs", tRead - t0).put("pmMs", tPm - tRead).put("rigMs", tRig - tPm)
                .put("peakJavaHeapMB", peakHeap / 1_000_000).put("peakNativeMB", peakNative / 1_000_000).put("fileMB", mesh(ctx, profileId).length() / 1_000_000)
            app.belle.assistant.core.AtomicFiles.write(File(d, "avatar3d.json"), info.toString(2).toByteArray())
            progress = "Making the preview" to 0.95f
            renderPreview(ctx, profileId)
            val s = "3D avatar ready: ${"%,d".format(m.triCount)} triangles, ${if (ownRig) "its own skeleton (${m.skeleton!!.size} bones, ${m.animations.size} animations)" else "auto-rigged ($rigStyle), mouth placed"} " +
                "in ${(SystemClock.elapsedRealtime() - t0) / 1000.0}s, peak memory ${peakHeap / 1_000_000} MB"
            lastResult = s
            runCatching { app.belle.assistant.ui.AssistantWidget.avatarChanged(ctx) }   // the widget never keeps an older look
            return s
        } finally { progress = null }
    }

    fun renderPreview(ctx: Context, profileId: String) {
        val p = Prefs(ctx, profileId)
        val f = Avatar3DRenderer.renderBlocking(Avatar3DRenderer.Request(mesh(ctx, profileId), "widget", 512, 512, p.framing("widget").let { if (it.shape == "full") it else it },
            p.shader3d, p.shader3dIntensity, p.psxNoDepth, p.n64Fog, 0.6f, 0f, 0, 0f, p.animStrength), 60_000) ?: return
        preview(ctx, profileId).outputStream().use { f.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Request for one frame of [profileId]'s avatar on [surface]. */
    fun request(ctx: Context, p: Prefs, surface: String, w: Int, h: Int, timeSec: Float, state: Int, level: Float, framing: Framing = p.framing(surface)) =
        Avatar3DRenderer.Request(mesh(ctx, p.id), surface, w, h, framing, p.shader3d, p.shader3dIntensity, p.psxNoDepth, p.n64Fog, p.detailFor(surface), timeSec, state, level, p.animStrength)

    @Suppress("unused") private val keepShader = Shader3D.NONE
}
