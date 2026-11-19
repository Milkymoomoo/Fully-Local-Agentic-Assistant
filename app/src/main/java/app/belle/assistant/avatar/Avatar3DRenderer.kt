package app.belle.assistant.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import app.belle.assistant.core.Detail3D
import app.belle.assistant.core.Framing
import app.belle.assistant.core.Shader3D
import app.belle.assistant.mesh.TextureMips
import app.belle.assistant.mesh.Glb
import app.belle.assistant.mesh.Mesh3D
import app.belle.assistant.mesh.Pose3D
import app.belle.assistant.mesh.ProgressiveMesh
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Off-screen OpenGL ES 3.0 renderer for 3D avatars. One GL context on one background thread renders frames
 * into a bitmap for the Home avatar, the floating bubble and the widget (widgets can't host GL views).
 *
 * Detail slider → real work cut: progressive-mesh prefix (fewer triangles drawn AND fewer vertices skinned —
 * glDrawRangeElements only touches vertices [0, n)), texture base mip level, render resolution, and the
 * caller's frame-rate cap. Shaders: None / Retrostation (PSX) / Retro64 (N64), exactly one at a time.
 */
object Avatar3DRenderer {
    class Request(
        val file: File, val surface: String, val width: Int, val height: Int, val framing: Framing,
        val shader: Shader3D, val intensity: Float, val psxNoDepth: Boolean, val n64Fog: Boolean,
        val detail: Float, val timeSec: Float, val state: Int, val level: Float,
        /** animation strength (rig v6): 1 = full motion weighting, 0 = no-stretch weighting, mixed in between */
        val strength: Float = 1f,
    )
    data class Stats(val frameMs: Float, val cpuMs: Float, val gpuMs: Float, val triangles: Int, val vertices: Int,
                     val renderW: Int, val renderH: Int, val mip: Int, val smooth: Boolean)
    class Frame(val bitmap: Bitmap, val stats: Stats)

    private const val TAG = "Avatar3D"
    private val thread = HandlerThread("avatar3d").apply { start() }
    val handler = Handler(thread.looper)
    private var display: EGLDisplay? = null; private var context: EGLContext? = null; private var surface: EGLSurface? = null
    private var program = 0; private var fbo = 0; private var colorRb = 0; private var depthRb = 0; private var fbW = 0; private var fbH = 0
    private var samplerLinear = 0; private var samplerNearest = 0; private var ubo = 0
    private val models = HashMap<String, Gpu>()
    @Volatile var lastError: String? = null; private set
    var lastStats: Stats? = null; private set

    private class Gpu(val key: String, val mesh: Mesh3D, val vbo: Int, val ibo: Int, val tex: Int, val texSize: Int, val mips: Int,
                      val center: FloatArray, val radius: Float, val pose: Pose3D?, val safeLod: Int = 99) {
        var levelVerts = -1; var levelTris = 0; var floorTris = -1; val idx = IntArray(mesh.idx.size); val remap = IntArray(mesh.vertexCount)
        val bones = FloatArray(128 * 16); var sortedFor = -1
    }

    /** Renders on the GL thread and waits (≤ [timeoutMs]). Returns null if 3D isn't possible. */
    fun renderBlocking(r: Request, timeoutMs: Long = 4000): Frame? {
        if (Thread.currentThread() == thread) return runCatching { render(r) }.onFailure { fail(it) }.getOrNull()
        var out: Frame? = null; val l = CountDownLatch(1)
        handler.post { out = runCatching { render(r) }.onFailure { fail(it) }.getOrNull(); l.countDown() }
        l.await(timeoutMs, TimeUnit.MILLISECONDS); return out
    }
    private fun fail(t: Throwable) { lastError = t.message ?: t.toString(); Log.w(TAG, "render failed", t) }

    /** Drop cached GPU data (after a new import). */
    fun forget(file: File) = handler.post { models.remove(file.absolutePath)?.let { g -> GLES30.glDeleteBuffers(2, intArrayOf(g.vbo, g.ibo), 0); GLES30.glDeleteTextures(1, intArrayOf(g.tex), 0) } }

    private fun ensureGl() {
        if (context != null && program != 0) return
        if (context != null) { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); context = null; models.clear(); fbW = 0; fbH = 0 }
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY); val v = IntArray(2); EGL14.eglInitialize(d, v, 0, v, 1)
        val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(d, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
        require(n[0] > 0) { "No OpenGL ES 3 support" }
        val c = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val s = EGL14.eglCreatePbufferSurface(d, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        require(EGL14.eglMakeCurrent(d, s, s, c)) { "eglMakeCurrent failed" }
        display = d; context = c; surface = s
        // Bone palette size from the GPU's vertex-uniform budget (ES 3.0 guarantees only 256 vec4; SwiftShader and some
        // older Mali count the Bones block against it): 128 where possible, otherwise as many as fit (58 at 256).
        val mv = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_VERTEX_UNIFORM_VECTORS, mv, 0)
        maxBones = ((mv[0] - 24) / 4).coerceIn(16, 128)
        program = link(VS.replace("uBones[128]", "uBones[$maxBones]").replace("MAXB", "${maxBones - 1}"), FS)
        val sm = IntArray(2); GLES30.glGenSamplers(2, sm, 0); samplerLinear = sm[0]; samplerNearest = sm[1]
        GLES30.glSamplerParameteri(samplerLinear, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glSamplerParameteri(samplerLinear, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glSamplerParameteri(samplerNearest, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST_MIPMAP_NEAREST)
        GLES30.glSamplerParameteri(samplerNearest, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        for (smp in sm) { GLES30.glSamplerParameteri(smp, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT); GLES30.glSamplerParameteri(smp, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT) }
        // Bones go in a plain uniform array (glUniformMatrix4fv): a uniform buffer drew nothing on the goldfish/SwiftShader GPU.
        uBonesLoc = GLES30.glGetUniformLocation(program, "uBones")
    }

    private fun model(file: File): Gpu {
        var key = file.absolutePath + ":" + file.lastModified()
        models[file.absolutePath]?.takeIf { it.key == key }?.let { return it }
        models.remove(file.absolutePath)?.let { g -> GLES30.glDeleteBuffers(2, intArrayOf(g.vbo, g.ibo), 0); GLES30.glDeleteTextures(1, intArrayOf(g.tex), 0) }
        var m = Glb.read(file.readBytes()).withNormals()
        if (!m.hasPm) {
            // saved by an older version (or a bare file): rebuild the detail levels once and save them
            m = ProgressiveMesh.build(m)
            runCatching { val tmp = File(file.path + ".pm"); tmp.writeBytes(Glb.write(m)); tmp.renameTo(file) }.onFailure { Log.w(TAG, "could not save rebuilt detail levels", it) }
        }
        key = file.absolutePath + ":" + file.lastModified()
        val V = m.vertexCount; val F = 27
        val vb = ByteBuffer.allocateDirect(V * F * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until V) {
            vb.put(m.pos, i * 3, 3); vb.put(m.nrm!!, i * 3, 3)
            if (m.uv != null) vb.put(m.uv, i * 2, 2) else { vb.put(0f); vb.put(0f) }
            if (m.col != null) vb.put(m.col, i * 3, 3) else { vb.put(1f); vb.put(1f); vb.put(1f) }
            if (m.joints != null && m.weights != null) { for (k in 0..3) vb.put(m.joints[i * 4 + k].coerceAtMost(127).toFloat()); vb.put(m.weights, i * 4, 4) }
            else { repeat(4) { vb.put(0f) }; vb.put(1f); repeat(3) { vb.put(0f) } }
            // the second (no-stretch) weighting; the same one again when the model has only one
            val j2 = m.joints2 ?: m.joints; val w2 = m.weights2 ?: m.weights
            if (j2 != null && w2 != null && m.joints != null) { for (k in 0..3) vb.put(j2[i * 4 + k].coerceAtMost(127).toFloat()); vb.put(w2, i * 4, 4) }
            else { repeat(4) { vb.put(0f) }; vb.put(1f); repeat(3) { vb.put(0f) } }
        }
        vb.position(0)
        val bufs = IntArray(2); GLES30.glGenBuffers(2, bufs, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufs[0]); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, V * F * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, bufs[1]); GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.idx.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
        // texture: decoded at ≤2048 px (the detail slider then picks a mip level), white if none
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        var size = 1; var safe = 99
        val bmp = m.texture?.let { bytes ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var ss = 1; while (max(o.outWidth, o.outHeight) / ss > 2048) ss *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss; inPremultiplied = false })
        }
        if (bmp != null) {
            // Our own chart-aware mip chain (see TextureMips) instead of glGenerateMipmap: atlas gutters no longer
            // smear dark patches into the lower mips that the detail slider and the retro shaders sample.
            val w = bmp.width; val h = bmp.height; val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
            val used = if (m.uv != null) TextureMips.coverage(w, h, m.uv, m.idx) else BooleanArray(w * h) { true }
            val levels = TextureMips.build(w, h, px, used)
            // The coarsest level that still looks like the full texture: lower detail and the retro shaders never
            // sample below it, so fragmented atlases don't blur into their neighbouring islands (any model).
            safe = TextureMips.safeLevel(TextureMips.levelErrors(levels, used))
            android.util.Log.i("Avatar3D", "texture ${w}x$h: ${levels.size} levels, sharpest-safe level $safe")
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            for ((li, lv) in levels.withIndex()) {
                val bb = ByteBuffer.allocateDirect(lv.w * lv.h * 4).order(ByteOrder.nativeOrder())
                for (c in lv.px) { bb.put(((c shr 16) and 255).toByte()); bb.put(((c shr 8) and 255).toByte()); bb.put((c and 255).toByte()); bb.put((c ushr 24).toByte()) }
                bb.position(0); GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, li, GLES30.GL_RGBA8, lv.w, lv.h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bb)
            }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, levels.size - 1)
            size = max(w, h)
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1)).position(0))
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        }
        val b = m.bounds(); val c = floatArrayOf((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2)
        val r = max(b[3] - b[0], max(b[4] - b[1], b[5] - b[2])) / 2
        val pose = m.skeleton?.takeIf { m.joints != null }?.let { Pose3D(it, m.animations) }
        return Gpu(key, m, bufs[0], bufs[1], t[0], size, (ln(size.toDouble()) / ln(2.0)).toInt(), c, r.coerceAtLeast(1e-6f), pose, safe).also { models[file.absolutePath] = it }
    }

    private fun render(r: Request): Frame {
        val t0 = SystemClock.elapsedRealtimeNanos(); val c0 = Debug.threadCpuTimeNanos()
        ensureGl()
        val g = model(r.file); val m = g.mesh
        val det = Detail3D(r.detail, r.surface); val inten = r.intensity.coerceIn(0f, 1f)
        // ---- detail: progressive-mesh prefix ----
        if (g.floorTris < 0) g.floorTris = if (!m.hasPm) m.triCount else maxOf(m.pmCleanTris, ProgressiveMesh.trim(m, ProgressiveMesh.verticesForTriangles(m, 1), IntArray(m.idx.size)))
        val wantTris = det.triangles(m.triCount, g.floorTris)
        val n = ProgressiveMesh.verticesForTriangles(m, wantTris)
        if (n != g.levelVerts || (r.shader == Shader3D.RETROSTATION && r.psxNoDepth)) {
            if (n != g.levelVerts) { g.levelTris = ProgressiveMesh.trim(m, n, g.idx, g.remap); g.levelVerts = n; g.sortedFor = -1 }
        }
        // ---- render size ----
        var rw = max(16, (r.width * det.renderScale).roundToInt()); var rh = max(16, (r.height * det.renderScale).roundToInt())
        val retroMax = when (r.shader) { Shader3D.RETROSTATION -> 320f - 96f * inten; Shader3D.RETRO64 -> 320f; else -> 0f }
        if (retroMax > 0) { val k = 1f + (min(1f, retroMax / max(rw, rh)) - 1f) * inten   // ~320×240 internal resolution
            rw = max(16, (rw * k).roundToInt()); rh = max(16, (rh * k).roundToInt()) }
        if (rw != fbW || rh != fbH) allocFbo(rw, rh)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo); GLES30.glViewport(0, 0, rw, rh)
        GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // ---- camera from the framing (distance × model size, height, orbit angle, zoom/pan) ----
        val fr = r.framing; val rad = Math.toRadians(fr.angle.toDouble()).toFloat()
        val d = g.radius * fr.dist.coerceIn(0.6f, 8f)
        val ey = g.center[1] + fr.height * g.radius
        val eye = floatArrayOf(g.center[0] + d * sin(rad), ey, g.center[2] + d * cos(rad))
        val view = FloatArray(16); Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], g.center[0], g.center[1] + fr.height * g.radius * 0.4f, g.center[2], 0f, 1f, 0f)
        val proj = FloatArray(16); val aspect = rw.toFloat() / rh
        val fov = (2 * Math.toDegrees(kotlin.math.atan(1.0 / fr.dist.coerceIn(0.6f, 8f) * 1.15))).toFloat() / max(0.3f, fr.zoom)
        Matrix.perspectiveM(proj, 0, fov.coerceIn(5f, 120f), aspect, max(0.01f, d - g.radius * 3), d + g.radius * 3)
        // pan (framing centre) and flip Y so glReadPixels gives a top-down bitmap
        val pan = FloatArray(16); Matrix.setIdentityM(pan, 0); pan[12] = -(fr.cx - 0.5f) * 2f; pan[13] = (fr.cy - 0.5f) * 2f; pan[5] = -1f
        val mvp = FloatArray(16); val tmp = FloatArray(16); Matrix.multiplyMM(tmp, 0, proj, 0, view, 0); Matrix.multiplyMM(mvp, 0, pan, 0, tmp, 0)
        // ---- pose ----
        GLES30.glUseProgram(program)
        val skinned = g.pose != null && !debugNoSkin
        if (skinned) { g.pose!!.evaluate(r.timeSec, r.state, r.level, g.bones)
            GLES30.glUniformMatrix4fv(uBonesLoc, min(m.skeleton!!.size, maxBones), false, g.bones, 0) }
        else if (r.state == 3) { // no skeleton: a gentle bob while speaking
            val bob = FloatArray(16); Matrix.setIdentityM(bob, 0); Matrix.translateM(bob, 0, 0f, r.level * g.radius * 0.03f, 0f); Matrix.multiplyMM(tmp, 0, mvp, 0, bob, 0); tmp.copyInto(mvp) }
        // ---- PSX no-depth mode: painter's order (back to front) on the CPU, like the PS1's ordering tables ----
        val noDepth = r.shader == Shader3D.RETROSTATION && r.psxNoDepth
        if (noDepth) sortBackToFront(g, mvp)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo)
        if (noDepth || g.sortedFor != g.levelVerts) { GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0, g.levelTris * 12, ByteBuffer.allocateDirect(g.levelTris * 12).order(ByteOrder.nativeOrder()).asIntBuffer().put(g.idx, 0, g.levelTris * 3).position(0)); g.sortedFor = g.levelVerts }
        // ---- uniforms ----
        fun u(name: String) = GLES30.glGetUniformLocation(program, name)
        GLES30.glUniformMatrix4fv(u("uMVP"), 1, false, mvp, 0)
        GLES30.glUniform1i(u("uSkinned"), if (skinned) 1 else 0)
        GLES30.glUniform1f(u("uStrength"), r.strength.coerceIn(0f, 1f))
        GLES30.glUniform1i(u("uMode"), r.shader.ordinal); GLES30.glUniform1f(u("uIntensity"), inten)
        GLES30.glUniform2f(u("uSnapRes"), rw / (2f + 4f * inten), rh / (2f + 4f * inten))
        val l = floatArrayOf(-0.4f + sin(rad) * 0.5f, 0.7f, 0.6f + cos(rad) * 0.3f); GLES30.glUniform3f(u("uLightDir"), l[0], l[1], l[2]); GLES30.glUniform3f(u("uEye"), eye[0], eye[1], eye[2])
        GLES30.glUniform1f(u("uFogNear"), d - g.radius * 0.6f); GLES30.glUniform1f(u("uFogFar"), d + g.radius * 1.8f)
        GLES30.glUniform1i(u("uFog"), if (r.shader == Shader3D.RETRO64 && r.n64Fog) 1 else 0)
        GLES30.glUniform1i(u("uHasTex"), if (m.texture != null) 1 else 0)
        // texture detail = mip level. The retro looks cap the WHOLE atlas at ~512 → 256 px with intensity (a console
        // texture page); smaller made a whole-body atlas collapse into blotches.
        var lod = det.mipBias.toFloat()
        if (r.shader == Shader3D.RETRO64 || r.shader == Shader3D.RETROSTATION) lod = max(lod, (g.mips - (9f - inten)).coerceAtLeast(0f))
        lod = min(lod, min(g.mips, g.safeLod).toFloat())
        if (debugLod >= 0f) lod = debugLod
        GLES30.glUniform1f(u("uLod"), lod); val ts = max(1f, g.texSize / (1 shl lod.toInt()).toFloat()); GLES30.glUniform2f(u("uTexSize"), ts, ts)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, g.tex)
        GLES30.glBindSampler(0, if (r.shader == Shader3D.NONE) samplerLinear else samplerNearest); GLES30.glUniform1i(u("uTex"), 0)
        // ---- draw: only vertices [0, n) are touched ----
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo)
        val stride = 27 * 4
        for ((loc, comps, off) in listOf(Triple(0, 3, 0), Triple(1, 3, 3), Triple(2, 2, 6), Triple(3, 3, 8), Triple(4, 4, 11), Triple(5, 4, 15), Triple(6, 4, 19), Triple(7, 4, 23))) {
            GLES30.glEnableVertexAttribArray(loc); GLES30.glVertexAttribPointer(loc, comps, GLES30.GL_FLOAT, false, stride, off * 4) }
        if (noDepth) GLES30.glDisable(GLES30.GL_DEPTH_TEST) else GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDrawRangeElements(GLES30.GL_TRIANGLES, 0, g.levelVerts - 1, g.levelTris * 3, GLES30.GL_UNSIGNED_INT, 0)
        val tDraw = SystemClock.elapsedRealtimeNanos()
        // ---- read back ----
        val buf = ByteBuffer.allocateDirect(rw * rh * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, rw, rh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)  // implies finish
        val tGpu = SystemClock.elapsedRealtimeNanos()
        val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888); buf.position(0); bmp.copyPixelsFromBuffer(buf)
        val err = GLES30.glGetError(); if (err != 0) Log.w(TAG, "GL error $err")
        val st = Stats((SystemClock.elapsedRealtimeNanos() - t0) / 1e6f, (Debug.threadCpuTimeNanos() - c0) / 1e6f, (tGpu - tDraw) / 1e6f,
            g.levelTris, g.levelVerts, rw, rh, lod.toInt(), r.shader != Shader3D.RETROSTATION)
        lastStats = st; lastError = null
        return Frame(bmp, st)
    }

    private fun sortBackToFront(g: Gpu, mvp: FloatArray) {
        val T = g.levelTris; val p = g.mesh.pos; val key = FloatArray(T)
        for (t in 0 until T) { var w = 0f; for (k in 0..2) { val v = g.idx[t * 3 + k] * 3; w += mvp[3] * p[v] + mvp[7] * p[v + 1] + mvp[11] * p[v + 2] + mvp[15] }; key[t] = w }
        val order = (0 until T).sortedByDescending { key[it] }; val copy = g.idx.copyOf(T * 3)
        for ((i, t) in order.withIndex()) { g.idx[i * 3] = copy[t * 3]; g.idx[i * 3 + 1] = copy[t * 3 + 1]; g.idx[i * 3 + 2] = copy[t * 3 + 2] }
        g.levelVerts = -2 // re-trim next frame (sorting scrambled the prefix order)
        g.sortedFor = -3
    }

    private fun allocFbo(w: Int, h: Int) {
        if (fbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0); GLES30.glDeleteRenderbuffers(2, intArrayOf(colorRb, depthRb), 0) }
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); fbo = f[0]
        val rb = IntArray(2); GLES30.glGenRenderbuffers(2, rb, 0); colorRb = rb[0]; depthRb = rb[1]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, colorRb); GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb); GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, colorRb)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRb)
        fbW = w; fbH = h
    }

    @Volatile var maxBones = 128; private set
    private var uBonesLoc = -1
    /** Debug only: draw the bind pose (isolates skinning problems on odd GPUs). */
    @Volatile var debugNoSkin = false
    /** Debug only: force the texture mip level (−1 = normal). */
    @Volatile var debugLod = -1f
    private fun link(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES30.glCreateShader(type).also { GLES30.glShaderSource(it, src); GLES30.glCompileShader(it)
            val ok = IntArray(1); GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, ok, 0); require(ok[0] != 0) { "Shader: " + GLES30.glGetShaderInfoLog(it) } }
        val p = GLES30.glCreateProgram(); GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs)); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0); require(ok[0] != 0) { "Link: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    // uMode: 0 = None, 1 = Retrostation (PSX), 2 = Retro64 (N64)
    private const val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec2 aUv; layout(location=3) in vec3 aCol;
layout(location=4) in vec4 aJ; layout(location=5) in vec4 aW; layout(location=6) in vec4 aJ2; layout(location=7) in vec4 aW2;
uniform float uStrength;
uniform mat4 uMVP; uniform int uSkinned; uniform int uMode; uniform float uIntensity; uniform vec2 uSnapRes; uniform vec3 uLightDir;
uniform float uFogNear; uniform float uFogFar; uniform vec3 uEye;
uniform mat4 uBones[128];
out vec2 vUv; out vec3 vNrm; out vec3 vCol; out vec3 vGouraud; out vec3 vGouraudBack; out float vFog; out vec3 vAff; out vec3 vEye;
void main() {
  vec4 p = vec4(aPos, 1.0); vec3 n = aNrm;
  if (uSkinned == 1) {
    ivec4 j = min(ivec4(aJ), ivec4(MAXB));
    mat4 s = aW.x * uBones[j.x] + aW.y * uBones[j.y] + aW.z * uBones[j.z] + aW.w * uBones[j.w];
    if (uStrength < 0.999) {   // animation strength: mix in the no-stretch weighting
      ivec4 k = min(ivec4(aJ2), ivec4(MAXB));
      mat4 s2 = aW2.x * uBones[k.x] + aW2.y * uBones[k.y] + aW2.z * uBones[k.z] + aW2.w * uBones[k.w];
      s = uStrength * s + (1.0 - uStrength) * s2;
    }
    p = s * p; n = mat3(s) * n;
  }
  vec4 c = uMVP * p;
  if (uMode == 1) {            // PSX: snap vertices to a coarse screen grid (the famous jitter)
    vec2 ndc = c.xy / c.w; vec2 snapped = floor(ndc * uSnapRes + 0.5) / uSnapRes;
    c.xy = mix(c.xy, snapped * c.w, uIntensity);
  }
  gl_Position = c;
  vUv = aUv; vNrm = normalize(n); vCol = aCol;
  vAff = vec3(aUv * c.w, c.w);   // affine (screen-linear) UVs: divide back in the fragment shader
  float l = max(dot(normalize(n), normalize(uLightDir)), 0.0);
  vGouraud = vec3(0.38) + vec3(0.72) * l;     // per-vertex (Gouraud) lighting
  vGouraudBack = vec3(0.38) + vec3(0.72) * max(-dot(normalize(n), normalize(uLightDir)), 0.0);
  vEye = uEye - p.xyz;
  vFog = clamp((c.w - uFogNear) / (uFogFar - uFogNear), 0.0, 1.0);
}"""

    private const val FS = """#version 300 es
precision highp float; precision highp int;
in vec2 vUv; in vec3 vNrm; in vec3 vCol; in vec3 vGouraud; in vec3 vGouraudBack; in float vFog; in vec3 vAff; in vec3 vEye;
uniform sampler2D uTex; uniform int uHasTex; uniform int uMode; uniform float uIntensity; uniform vec2 uTexSize; uniform float uLod;
uniform vec3 uLightDir; uniform int uFog;
out vec4 o;
// Texture footprint of this pixel (set in main). At low detail one pixel covers several texels of the sharpest level
// we allow (see safeLod); four taps across the footprint average them instead of picking one at random, which
// showed as single dark/white specks wherever the texture has fine lines.
vec2 gDx, gDy;
vec4 tex(vec2 uv) {
  return 0.25 * (textureLod(uTex, uv + gDx + gDy, uLod) + textureLod(uTex, uv + gDx - gDy, uLod)
               + textureLod(uTex, uv - gDx + gDy, uLod) + textureLod(uTex, uv - gDx - gDy, uLod));
}
vec4 three(vec2 uv) {        // N64 3-point texture filter
  vec2 st = uv * uTexSize - 0.5; vec2 f = fract(st); vec2 b = (floor(st) + 0.5) / uTexSize; vec2 px = 1.0 / uTexSize;
  vec4 a = tex(b), c1 = tex(b + vec2(px.x, 0.0)), c2 = tex(b + vec2(0.0, px.y)), d = tex(b + px);
  return (f.x + f.y < 1.0) ? a + f.x * (c1 - a) + f.y * (c2 - a) : d + (1.0 - f.x) * (c2 - d) + (1.0 - f.y) * (c1 - d);
}
float bayer(vec2 p) {
  int x = int(mod(p.x, 4.0)), y = int(mod(p.y, 4.0)); int i = x + y * 4;
  float m[16] = float[16](0.,8.,2.,10.,12.,4.,14.,6.,3.,11.,1.,9.,15.,7.,13.,5.);
  return m[i] / 16.0;
}
void main() {
  vec4 base;
  gDx = dFdx(vUv) * 0.25; gDy = dFdy(vUv) * 0.25;
  // Two-sided lighting: a surface seen from behind (inner shells of generated meshes that poke through at low
  // detail, open cloth, flipped exports) is lit with its normal turned towards the viewer instead of going grey.
  bool back = dot(vNrm, vEye) < 0.0;
  vec3 nrm = back ? -normalize(vNrm) : normalize(vNrm);
  vec3 gour = back ? vGouraudBack : vGouraud;
  if (uMode == 1) {
    vec2 aff = vAff.xy / vAff.z;                      // affine texture warping
    vec2 uv = mix(vUv, aff, uIntensity);
    base = uHasTex == 1 ? tex(uv) : vec4(1.0);
    vec3 col = base.rgb * vCol * gour;
    float steps = mix(255.0, 31.0, uIntensity);       // 15-bit colour (5 bits/channel) with ordered dithering
    col = floor(col * steps + bayer(gl_FragCoord.xy) * uIntensity) / steps;
    o = vec4(col, 1.0);
  } else if (uMode == 2) {
    base = uHasTex == 1 ? mix(tex(vUv), three(vUv), uIntensity) : vec4(1.0);
    vec3 col = base.rgb * vCol * gour;
    if (uFog == 1) col = mix(col, vec3(0.55, 0.6, 0.68), vFog * 0.75 * uIntensity);
    o = vec4(col, 1.0);
  } else {
    base = uHasTex == 1 ? tex(vUv) : vec4(1.0);
    float l = max(dot(nrm, normalize(uLightDir)), 0.0);
    float rim = pow(1.0 - abs(nrm.z), 3.0) * 0.15;
    o = vec4(base.rgb * vCol * (0.4 + 0.7 * l) + rim, 1.0);
  }
}"""
}
