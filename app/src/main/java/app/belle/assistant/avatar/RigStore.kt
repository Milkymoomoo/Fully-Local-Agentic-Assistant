package app.belle.assistant.avatar

import android.content.Context
import android.graphics.Bitmap
import app.belle.assistant.Prefs
import app.belle.assistant.core.Rig2D

/** Loads the saved rig for the current avatar, or auto-rigs it the first time. */
object RigStore {
    fun rigFor(ctx: Context, prefs: Prefs, b: Bitmap): Rig2D {
        val key = prefs.avatarKey
        Rig2D.parse(prefs.rig(key))?.let { return it }
        if (key == "default") return Rig2D.DRAGON
        if (prefs.avatarFile == "avatar_boob_dragon.webp") return Rig2D.BOOB_DRAGON
        val r = autoRig(b)
        prefs.setRig(key, r.toLine())
        return r
    }

    /**
     * Auto placement: Android's built-in face detector (android.media.FaceDetector,
     * part of the OS, no Google services) for human-like faces; otherwise the alpha
     * silhouette heuristic (narrowest row under the head = neck, mouth just above).
     */
    fun autoRig(b: Bitmap): Rig2D {
        val sw = 160; val sh = (b.height * sw / b.width).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val hasAlpha = px.count { it ushr 24 < 128 } > px.size / 20
        val mask = if (hasAlpha) BooleanArray(px.size) { px[it] ushr 24 >= 128 } else null
        val face = runCatching {
            val fw = (b.width / 2) * 2
            val f565 = Bitmap.createScaledBitmap(b, fw.coerceAtMost(640) / 2 * 2, (b.height * fw.coerceAtMost(640) / b.width), true).copy(Bitmap.Config.RGB_565, false)
            val faces = arrayOfNulls<android.media.FaceDetector.Face>(1)
            if (android.media.FaceDetector(f565.width, f565.height, 1).findFaces(f565, faces) > 0) {
                val fc = faces[0]!!; val mid = android.graphics.PointF(); fc.getMidPoint(mid)
                if (fc.confidence() > 0.4f) Triple(mid.x / f565.width, mid.y / f565.height, fc.eyesDistance() / f565.width) else null
            } else null
        }.getOrNull()
        return Rig2D.auto(mask, sw, sh, face)
    }
}
