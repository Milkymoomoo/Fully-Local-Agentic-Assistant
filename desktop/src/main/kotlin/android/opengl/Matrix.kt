package android.opengl

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan

/** Desktop stand-in for android.opengl.Matrix (column-major 4×4, same conventions as Android's). */
object Matrix {
    @JvmStatic fun setIdentityM(m: FloatArray, o: Int) { for (i in 0 until 16) m[o + i] = if (i % 5 == 0) 1f else 0f }
    @JvmStatic fun multiplyMM(r: FloatArray, ro: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int) {
        val t = FloatArray(16)
        for (i in 0 until 4) for (j in 0 until 4) { var s = 0f; for (k in 0 until 4) s += a[ao + k * 4 + i] * b[bo + j * 4 + k]; t[j * 4 + i] = s }
        t.copyInto(r, ro)
    }
    @JvmStatic fun translateM(m: FloatArray, o: Int, x: Float, y: Float, z: Float) {
        for (i in 0 until 4) m[o + 12 + i] += m[o + i] * x + m[o + 4 + i] * y + m[o + 8 + i] * z
    }
    @JvmStatic fun perspectiveM(m: FloatArray, o: Int, fovy: Float, aspect: Float, zNear: Float, zFar: Float) {
        val f = 1f / tan(fovy * (PI / 360.0)).toFloat(); val rangeReciprocal = 1f / (zNear - zFar)
        for (i in 0 until 16) m[o + i] = 0f
        m[o] = f / aspect; m[o + 5] = f; m[o + 10] = (zFar + zNear) * rangeReciprocal; m[o + 11] = -1f; m[o + 14] = 2f * zFar * zNear * rangeReciprocal
    }
    @JvmStatic fun setLookAtM(rm: FloatArray, o: Int, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float) {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        val rlf = 1f / sqrt(fx * fx + fy * fy + fz * fz); fx *= rlf; fy *= rlf; fz *= rlf
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        val rls = 1f / sqrt(sx * sx + sy * sy + sz * sz); sx *= rls; sy *= rls; sz *= rls
        val vx = sy * fz - sz * fy; val vy = sz * fx - sx * fz; val vz = sx * fy - sy * fx
        rm[o] = sx; rm[o + 1] = vx; rm[o + 2] = -fx; rm[o + 3] = 0f
        rm[o + 4] = sy; rm[o + 5] = vy; rm[o + 6] = -fy; rm[o + 7] = 0f
        rm[o + 8] = sz; rm[o + 9] = vz; rm[o + 10] = -fz; rm[o + 11] = 0f
        rm[o + 12] = 0f; rm[o + 13] = 0f; rm[o + 14] = 0f; rm[o + 15] = 1f
        translateM(rm, o, -ex, -ey, -ez)
    }
}
