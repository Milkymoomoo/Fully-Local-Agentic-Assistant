package android.media

/**
 * Desktop stand-in for Android's built-in face finder. The PC has no system face detector, so it finds none and
 * the auto-rig uses its picture-shape rules instead (as on a phone where detection fails); the rig editor can
 * always place the mouth and joints by hand.
 */
class FaceDetector(w: Int, h: Int, max: Int) {
    class Face { fun getMidPoint(p: android.graphics.PointF) {}; fun confidence() = 0f; fun eyesDistance() = 0f }
    fun findFaces(b: android.graphics.Bitmap, faces: Array<Face?>): Int = 0
}
