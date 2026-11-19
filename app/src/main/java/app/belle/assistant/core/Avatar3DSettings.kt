package app.belle.assistant.core

import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

/** The retro look for a profile's 3D avatar. Stored as ONE value, so only one can ever be on. */
enum class Shader3D(val id: String, val label: String) {
    NONE("none", "None"), RETROSTATION("retrostation", "Retrostation (PSX look)"), RETRO64("retro64", "Retro64 (N64 look)");
    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: NONE
        /**
         * Normalises imported settings JSON so exactly one shader is selected: a valid "shader3d" wins; otherwise
         * legacy/hand-edited booleans ("retrostation": true, "retro64": true …) are folded in, first one wins, and
         * the boolean keys are dropped.
         */
        fun sanitize(settings: MutableMap<String, Any?>) {
            val legacy = listOf("retrostation", "shader_retrostation", "psx", "retro64", "shader_retro64", "n64")
            var pick = (settings["shader3d"] as? String)?.let { s -> entries.firstOrNull { it.id == s } }
            if (pick == null) for (k in legacy) if (settings[k] == true || settings[k] == "true") { pick = if (k.contains("64")) RETRO64 else RETROSTATION; break }
            legacy.forEach { settings.remove(it) }
            settings["shader3d"] = (pick ?: NONE).id
            (settings["shader3d_intensity"] as? Number)?.let { settings["shader3d_intensity"] = it.toFloat().coerceIn(0f, 1f) }
        }
    }
}

/**
 * What a detail-slider position (0 = lowest … 1 = highest) costs at runtime. Every knob cuts real work:
 * triangles drawn + vertices skinned (progressive mesh prefix), texture mip level, render resolution, and frame rate.
 */
data class Detail3D(val level: Float, val surface: String) {
    /**
     * Triangles to draw, on a log scale from the lowest the model can go ([floor]; ~1.5k when unknown) to the full
     * mesh, so every step of the slider actually changes something.
     */
    fun triangles(total: Int, floor: Int = 1500): Int {
        val lo = minOf(floor.coerceAtLeast(1), total).coerceAtLeast(1).toDouble()
        return exp(ln(lo) + (ln(total.toDouble()) - ln(lo)) * level).roundToInt().coerceIn(1, total)
    }
    /** Texture mip level used as the base: 0 = full (≤2048), 1 = 1024, 2 = 512, 3 = 256. */
    val mipBias: Int get() = when { level >= 0.8f -> 0; level >= 0.55f -> 1; level >= 0.3f -> 2; else -> 3 }
    /** Fraction of the view's pixels actually rendered (then scaled up). */
    val renderScale: Float get() = (0.35f + 0.65f * level).let { if (surface == "widget") minOf(it, 0.75f) else it }
    /** Frame-rate cap; idle animation runs slower than an active conversation. The widget is cheapest. */
    fun fps(active: Boolean): Int = when (surface) {
        "widget" -> if (active) (4 + 8 * level).roundToInt() else 2
        "bubble" -> if (active) (8 + 16 * level).roundToInt() else (4 + 6 * level).roundToInt()
        else -> if (active) (12 + 18 * level).roundToInt() else (6 + 9 * level).roundToInt()
    }

    companion object {
        /** Auto default per device: more RAM / a GLES 3.2 GPU gets more detail; the widget is always cheap. */
        fun auto(ramGb: Double, gles32: Boolean, surface: String): Float {
            val tier = when { ramGb >= 11 && gles32 -> 2; ramGb >= 7 -> 1; else -> 0 }
            return when (surface) { "home" -> floatArrayOf(0.45f, 0.65f, 0.85f)[tier]; "bubble" -> floatArrayOf(0.3f, 0.45f, 0.6f)[tier]; else -> floatArrayOf(0.15f, 0.25f, 0.35f)[tier] }
        }
        fun label(level: Float) = when { level >= 0.85f -> "Ultra"; level >= 0.6f -> "High"; level >= 0.35f -> "Medium"; level >= 0.15f -> "Low"; else -> "Lowest" }
    }
}
