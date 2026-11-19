package android.opengl

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33

class EGLDisplay internal constructor()
class EGLConfig internal constructor()
class EGLContext internal constructor(internal val window: Long)
class EGLSurface internal constructor()

/**
 * Desktop stand-in for the slice of EGL14 the 3D renderer uses: an off-screen OpenGL 3.3 core context in a hidden
 * GLFW window (the renderer draws into its own framebuffer object and reads the pixels back, exactly as on Android).
 * One VAO is bound for the context's lifetime because core profile has no default vertex array object.
 */
object EGL14 {
    const val EGL_DEFAULT_DISPLAY = 0; const val EGL_NONE = 0x3038; const val EGL_WIDTH = 0x3057; const val EGL_HEIGHT = 0x3056
    const val EGL_SURFACE_TYPE = 0x3033; const val EGL_PBUFFER_BIT = 0x0001; const val EGL_RENDERABLE_TYPE = 0x3040
    const val EGL_RED_SIZE = 0x3024; const val EGL_GREEN_SIZE = 0x3023; const val EGL_BLUE_SIZE = 0x3022; const val EGL_ALPHA_SIZE = 0x3021
    const val EGL_DEPTH_SIZE = 0x3025; const val EGL_CONTEXT_CLIENT_VERSION = 0x3098; const val EGL_OPENGL_ES2_BIT = 0x0004
    @JvmField val EGL_NO_SURFACE = EGLSurface(); @JvmField val EGL_NO_CONTEXT = EGLContext(0); @JvmField val EGL_NO_DISPLAY = EGLDisplay()
    private val display = EGLDisplay()
    @Volatile private var glfwReady = false

    @JvmStatic fun eglGetDisplay(id: Int) = display
    @JvmStatic fun eglInitialize(d: EGLDisplay?, major: IntArray, mo: Int, minor: IntArray, mi: Int): Boolean {
        synchronized(this) { if (!glfwReady) { check(GLFW.glfwInit()) { "GLFW could not start (no display or GPU driver)" }; glfwReady = true } }
        major[mo] = 1; minor[mi] = 4; return true
    }
    @JvmStatic fun eglChooseConfig(d: EGLDisplay?, attrs: IntArray, ao: Int, cfg: Array<EGLConfig?>, co: Int, size: Int, num: IntArray, no: Int): Boolean {
        cfg[co] = EGLConfig(); num[no] = 1; return true
    }
    @JvmStatic fun eglCreateContext(d: EGLDisplay?, c: EGLConfig?, share: EGLContext?, attrs: IntArray, o: Int): EGLContext {
        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3); GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
        val w = GLFW.glfwCreateWindow(16, 16, "avatar3d", 0, 0)
        check(w != 0L) { "no OpenGL 3.3 context (graphics driver too old?)" }
        return EGLContext(w)
    }
    @JvmStatic fun eglCreatePbufferSurface(d: EGLDisplay?, c: EGLConfig?, attrs: IntArray, o: Int) = EGLSurface()
    @JvmStatic fun eglMakeCurrent(d: EGLDisplay?, draw: EGLSurface?, read: EGLSurface?, c: EGLContext?): Boolean {
        if (c == null || c.window == 0L) { GLFW.glfwMakeContextCurrent(0); return true }
        GLFW.glfwMakeContextCurrent(c.window); GL.createCapabilities()
        GL33.glBindVertexArray(GL33.glGenVertexArrays())
        return true
    }
    @JvmStatic fun eglDestroySurface(d: EGLDisplay?, s: EGLSurface?) = true
    @JvmStatic fun eglDestroyContext(d: EGLDisplay?, c: EGLContext?): Boolean { if (c != null && c.window != 0L) GLFW.glfwDestroyWindow(c.window); return true }
    @JvmStatic fun eglGetError() = 0x3000
}
