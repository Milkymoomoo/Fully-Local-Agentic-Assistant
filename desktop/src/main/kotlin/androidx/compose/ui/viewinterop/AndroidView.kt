package androidx.compose.ui.viewinterop

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/**
 * Desktop stand-in for Compose's AndroidView: hosts one of the app's custom-drawn [View]s (their onDraw runs on the
 * Skia canvas Compose draws with) and forwards mouse input to their touch handling.
 */
@Composable
fun <T : View> AndroidView(factory: (android.content.Context) -> T, modifier: Modifier = Modifier, update: (T) -> Unit = {}) {
    val ctx = LocalContext.current
    val v = remember { factory(ctx) }
    update(v)
    DisposableEffect(v) { v.attached = true; v.onAttachedToWindow(); onDispose { v.attached = false; v.onDetachedFromWindow() } }
    Canvas(modifier.pointerInput(v) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(); val p = e.changes.firstOrNull() ?: continue
                val a = when (e.type) { PointerEventType.Press -> MotionEvent.ACTION_DOWN; PointerEventType.Release -> MotionEvent.ACTION_UP
                    PointerEventType.Move -> if (p.pressed) MotionEvent.ACTION_MOVE else continue; else -> continue }
                if (v.isClickable && v.onTouchEvent(MotionEvent(a, p.position.x, p.position.y))) p.consume()
                if (a == MotionEvent.ACTION_UP && v.isClickable) v.performClick()
            }
        }
    }) {
        v.frames.intValue   // redraw when the view invalidates itself
        v.resize(size.width.toInt(), size.height.toInt())
        drawIntoCanvas { c -> v.draw(android.graphics.Canvas(c.nativeCanvas, v.width, v.height)) }
    }
}
