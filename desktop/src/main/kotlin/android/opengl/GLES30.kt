package android.opengl

import org.lwjgl.opengl.GL33 as G
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Desktop stand-in for android.opengl.GLES30, over desktop OpenGL 3.3 core (LWJGL). Only the calls the 3D renderer
 * makes. Shaders written for GLSL ES 3.00 are switched to GLSL 3.30 core in [glShaderSource] (same language subset).
 */
object GLES30 {
    const val GL_TEXTURE_2D = 0x0DE1; const val GL_RENDERBUFFER = 0x8D41; const val GL_RGBA = 0x1908; const val GL_FRAMEBUFFER = 0x8D40
    const val GL_ELEMENT_ARRAY_BUFFER = 0x8893; const val GL_ARRAY_BUFFER = 0x8892; const val GL_UNSIGNED_BYTE = 0x1401
    const val GL_TEXTURE_MIN_FILTER = 0x2801; const val GL_TEXTURE_MAG_FILTER = 0x2800; const val GL_RGBA8 = 0x8058; const val GL_REPEAT = 0x2901
    const val GL_DEPTH_TEST = 0x0B71; const val GL_VERTEX_SHADER = 0x8B31; const val GL_FRAGMENT_SHADER = 0x8B30; const val GL_UNSIGNED_INT = 0x1405
    const val GL_UNPACK_ALIGNMENT = 0x0CF5; const val GL_PACK_ALIGNMENT = 0x0D05; const val GL_TRIANGLES = 0x0004; const val GL_TEXTURE_WRAP_T = 0x2803
    const val GL_TEXTURE_WRAP_S = 0x2802; const val GL_TEXTURE_MAX_LEVEL = 0x813D; const val GL_TEXTURE0 = 0x84C0; const val GL_STATIC_DRAW = 0x88E4
    const val GL_NEAREST_MIPMAP_NEAREST = 0x2700; const val GL_NEAREST = 0x2600; const val GL_MAX_VERTEX_UNIFORM_VECTORS = 0x8DFB
    const val GL_LINK_STATUS = 0x8B82; const val GL_LINEAR_MIPMAP_LINEAR = 0x2703; const val GL_LINEAR = 0x2601; const val GL_FLOAT = 0x1406
    const val GL_DYNAMIC_DRAW = 0x88E8; const val GL_DEPTH_COMPONENT24 = 0x81A6; const val GL_DEPTH_BUFFER_BIT = 0x0100; const val GL_DEPTH_ATTACHMENT = 0x8D00
    const val GL_CULL_FACE = 0x0B44; const val GL_COMPILE_STATUS = 0x8B81; const val GL_COLOR_BUFFER_BIT = 0x4000; const val GL_COLOR_ATTACHMENT0 = 0x8CE0
    const val GL_BLEND = 0x0BE2; const val GL_NO_ERROR = 0; const val GL_CLAMP_TO_EDGE = 0x812F

    private const val GL_MAX_VERTEX_UNIFORM_COMPONENTS = 0x8B4A
    @JvmStatic fun glGetIntegerv(p: Int, v: IntArray, o: Int) {
        // GL_MAX_VERTEX_UNIFORM_VECTORS is ES-only: desktop core reports components (4 per vector)
        v[o] = if (p == GL_MAX_VERTEX_UNIFORM_VECTORS) G.glGetInteger(GL_MAX_VERTEX_UNIFORM_COMPONENTS) / 4 else G.glGetInteger(p)
    }
    @JvmStatic fun glGenSamplers(n: Int, v: IntArray, o: Int) { for (i in 0 until n) v[o + i] = G.glGenSamplers() }
    @JvmStatic fun glSamplerParameteri(s: Int, p: Int, v: Int) = G.glSamplerParameteri(s, p, v)
    @JvmStatic fun glBindSampler(u: Int, s: Int) = G.glBindSampler(u, s)
    @JvmStatic fun glGetUniformLocation(p: Int, n: String) = G.glGetUniformLocation(p, n)
    @JvmStatic fun glDeleteBuffers(n: Int, v: IntArray, o: Int) = G.glDeleteBuffers(v.copyOfRange(o, o + n))
    @JvmStatic fun glDeleteTextures(n: Int, v: IntArray, o: Int) = G.glDeleteTextures(v.copyOfRange(o, o + n))
    @JvmStatic fun glDeleteFramebuffers(n: Int, v: IntArray, o: Int) = G.glDeleteFramebuffers(v.copyOfRange(o, o + n))
    @JvmStatic fun glDeleteRenderbuffers(n: Int, v: IntArray, o: Int) = G.glDeleteRenderbuffers(v.copyOfRange(o, o + n))
    @JvmStatic fun glGenBuffers(n: Int, v: IntArray, o: Int) { for (i in 0 until n) v[o + i] = G.glGenBuffers() }
    @JvmStatic fun glGenTextures(n: Int, v: IntArray, o: Int) { for (i in 0 until n) v[o + i] = G.glGenTextures() }
    @JvmStatic fun glGenFramebuffers(n: Int, v: IntArray, o: Int) { for (i in 0 until n) v[o + i] = G.glGenFramebuffers() }
    @JvmStatic fun glGenRenderbuffers(n: Int, v: IntArray, o: Int) { for (i in 0 until n) v[o + i] = G.glGenRenderbuffers() }
    @JvmStatic fun glBindBuffer(t: Int, b: Int) = G.glBindBuffer(t, b)
    @JvmStatic fun glBufferData(t: Int, size: Int, data: Buffer?, usage: Int) {
        when (data) {
            null -> G.glBufferData(t, size.toLong(), usage)
            is ByteBuffer -> G.glBufferData(t, data.duplicate().limit(data.position() + size) as ByteBuffer, usage)
            is FloatBuffer -> G.glBufferData(t, data.duplicate().limit(data.position() + size / 4) as FloatBuffer, usage)
            is IntBuffer -> G.glBufferData(t, data.duplicate().limit(data.position() + size / 4) as IntBuffer, usage)
            else -> error("buffer type ${data.javaClass}")
        }
    }
    @JvmStatic fun glBufferSubData(t: Int, off: Int, size: Int, data: Buffer) {
        when (data) {
            is ByteBuffer -> G.glBufferSubData(t, off.toLong(), data.duplicate().limit(data.position() + size) as ByteBuffer)
            is FloatBuffer -> G.glBufferSubData(t, off.toLong(), data.duplicate().limit(data.position() + size / 4) as FloatBuffer)
            is IntBuffer -> G.glBufferSubData(t, off.toLong(), data.duplicate().limit(data.position() + size / 4) as IntBuffer)
            else -> error("buffer type ${data.javaClass}")
        }
    }
    @JvmStatic fun glBindTexture(t: Int, x: Int) = G.glBindTexture(t, x)
    @JvmStatic fun glPixelStorei(p: Int, v: Int) = G.glPixelStorei(p, v)
    @JvmStatic fun glTexImage2D(t: Int, l: Int, ifmt: Int, w: Int, h: Int, b: Int, fmt: Int, type: Int, px: Buffer?) =
        G.glTexImage2D(t, l, ifmt, w, h, b, fmt, type, px as ByteBuffer?)
    @JvmStatic fun glTexParameteri(t: Int, p: Int, v: Int) = G.glTexParameteri(t, p, v)
    @JvmStatic fun glGenerateMipmap(t: Int) = G.glGenerateMipmap(t)
    @JvmStatic fun glBindFramebuffer(t: Int, f: Int) = G.glBindFramebuffer(t, f)
    @JvmStatic fun glBindRenderbuffer(t: Int, r: Int) = G.glBindRenderbuffer(t, r)
    @JvmStatic fun glRenderbufferStorage(t: Int, f: Int, w: Int, h: Int) = G.glRenderbufferStorage(t, f, w, h)
    @JvmStatic fun glFramebufferRenderbuffer(t: Int, a: Int, rt: Int, r: Int) = G.glFramebufferRenderbuffer(t, a, rt, r)
    @JvmStatic fun glViewport(x: Int, y: Int, w: Int, h: Int) = G.glViewport(x, y, w, h)
    @JvmStatic fun glClearColor(r: Float, g: Float, b: Float, a: Float) = G.glClearColor(r, g, b, a)
    @JvmStatic fun glClear(m: Int) = G.glClear(m)
    @JvmStatic fun glUseProgram(p: Int) = G.glUseProgram(p)
    @JvmStatic fun glUniformMatrix4fv(l: Int, n: Int, tr: Boolean, v: FloatArray, o: Int) = G.glUniformMatrix4fv(l, tr, v.copyOfRange(o, o + 16 * n))
    @JvmStatic fun glUniform1i(l: Int, v: Int) = G.glUniform1i(l, v)
    @JvmStatic fun glUniform1f(l: Int, v: Float) = G.glUniform1f(l, v)
    @JvmStatic fun glUniform2f(l: Int, a: Float, b: Float) = G.glUniform2f(l, a, b)
    @JvmStatic fun glUniform3f(l: Int, a: Float, b: Float, c: Float) = G.glUniform3f(l, a, b, c)
    @JvmStatic fun glActiveTexture(t: Int) = G.glActiveTexture(t)
    @JvmStatic fun glEnableVertexAttribArray(i: Int) = G.glEnableVertexAttribArray(i)
    @JvmStatic fun glDisableVertexAttribArray(i: Int) = G.glDisableVertexAttribArray(i)
    @JvmStatic fun glVertexAttribPointer(i: Int, n: Int, t: Int, norm: Boolean, stride: Int, off: Int) = G.glVertexAttribPointer(i, n, t, norm, stride, off.toLong())
    @JvmStatic fun glEnable(c: Int) = G.glEnable(c)
    @JvmStatic fun glDisable(c: Int) = G.glDisable(c)
    @JvmStatic fun glDrawRangeElements(m: Int, s: Int, e: Int, n: Int, t: Int, off: Int) = G.glDrawRangeElements(m, s, e, n, t, off.toLong())
    @JvmStatic fun glReadPixels(x: Int, y: Int, w: Int, h: Int, f: Int, t: Int, b: Buffer) { G.glPixelStorei(GL_PACK_ALIGNMENT, 1); G.glReadPixels(x, y, w, h, f, t, b as ByteBuffer) }
    @JvmStatic fun glGetError() = G.glGetError()
    @JvmStatic fun glCreateShader(t: Int) = G.glCreateShader(t)
    @JvmStatic fun glShaderSource(s: Int, src: String) = G.glShaderSource(s, toDesktopGlsl(src))
    @JvmStatic fun glCompileShader(s: Int) = G.glCompileShader(s)
    @JvmStatic fun glGetShaderiv(s: Int, p: Int, v: IntArray, o: Int) { v[o] = G.glGetShaderi(s, p) }
    @JvmStatic fun glGetShaderInfoLog(s: Int): String = G.glGetShaderInfoLog(s)
    @JvmStatic fun glCreateProgram() = G.glCreateProgram()
    @JvmStatic fun glAttachShader(p: Int, s: Int) = G.glAttachShader(p, s)
    @JvmStatic fun glLinkProgram(p: Int) = G.glLinkProgram(p)
    @JvmStatic fun glGetProgramiv(p: Int, q: Int, v: IntArray, o: Int) { v[o] = G.glGetProgrami(p, q) }
    @JvmStatic fun glGetProgramInfoLog(p: Int): String = G.glGetProgramInfoLog(p)

    /** GLSL ES 3.00 → GLSL 3.30 core: only the version line differs for what the renderer uses. */
    fun toDesktopGlsl(src: String): String = src.replaceFirst(Regex("^\\s*#version 300 es"), "#version 330 core")
}
