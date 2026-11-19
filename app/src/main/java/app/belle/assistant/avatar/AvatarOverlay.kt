package app.belle.assistant.avatar

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import app.belle.assistant.Prefs
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantRuntime.Ui
import app.belle.assistant.ui.SettingsActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * Small floating avatar bubble (and optional screen-edge glow) drawn over other
 * apps. The bubble window is only as big as the avatar and is not focusable, so
 * touches anywhere else go straight to the app underneath. Drag to move; it
 * docks to the nearest side. Tap = talk (or end, while a conversation is
 * going); long-press = small menu: Talk / End conversation / Settings / Hide.
 */
class AvatarOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var view: AvatarView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var edge: EdgeGlowView? = null
    private var lastActive = SystemClock.uptimeMillis()
    private var tucked = false
    private val listener: () -> Unit = { onStateChanged() }

    fun canDraw() = Settings.canDrawOverlays(ctx)

    fun show() = main.post {
        hide()
        if (!canDraw()) { AssistantRuntime.event("avatar: overlay permission not granted"); return@post }
        if (prefs.edgeGlow) addEdge()
        if (prefs.avatarEnabled) addBubble()
        AssistantRuntime.addListener(listener)
        onStateChanged()
        main.postDelayed(idleTick, 1000)
    }

    fun hide() = main.post {
        dismissMenu()
        AssistantRuntime.removeListener(listener)
        main.removeCallbacks(idleTick)
        view?.let { runCatching { wm.removeView(it) } }; view = null
        edge?.let { runCatching { wm.removeView(it) } }; edge = null
    }

    private fun px(dp: Int) = (dp * ctx.resources.displayMetrics.density).toInt()
    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = px(prefs.avatarSizeDp)
        val v = AvatarView(ctx).apply {
            glowColor = prefs.glowColor
            alpha = prefs.avatarOpacity
            setImageFile(pictureFile()); shownPicture = pictureKey()
            contentDescription = "Assistant avatar. Tap to talk."
        }
        val p = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.avatarX < 0) screenW - size else prefs.avatarX.coerceIn(-size / 2, screenW - size / 2)
            y = prefs.avatarY.coerceIn(0, screenH - size)
        }
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var downAt = 0L
        val longPress = Runnable { if (!moved) { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); showMenu() }; moved = true }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; downAt = SystemClock.uptimeMillis()
                    untuck(); main.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) { moved = true; main.removeCallbacks(longPress) }
                    if (moved) { p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt().coerceIn(0, screenH - size); wm.updateViewLayout(v, p) }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved && menu == null) dock(v, p) else if (!moved && SystemClock.uptimeMillis() - downAt < 600) {
                        // Tap while listening/talking ends the conversation; otherwise starts one.
                        if (AssistantRuntime.inSession && prefs.avatarTapEnds) AssistantRuntime.endSession(ctx, "avatar tap")
                        else AssistantRuntime.listenNow(ctx)
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
        wm.addView(v, p)
        view = v; lp = p
    }

    /** Snap to the nearest side ("semi-docked"). */
    private fun dock(v: View, p: WindowManager.LayoutParams) {
        val size = p.width
        val target = if (p.x + size / 2 < screenW / 2) 0 else screenW - size
        animateX(v, p, target) { prefs.avatarX = target; prefs.avatarY = p.y }
        lastActive = SystemClock.uptimeMillis()
    }

    private fun animateX(v: View, p: WindowManager.LayoutParams, to: Int, end: () -> Unit = {}) {
        ValueAnimator.ofInt(p.x, to).apply {
            duration = 220
            addUpdateListener { p.x = it.animatedValue as Int; if (v.isAttachedToWindow) wm.updateViewLayout(v, p) }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) = end() })
            start()
        }
    }

    /** After idling, tuck half the bubble into the edge and fade it, so it stays out of the way. */
    private fun tuck() {
        val v = view ?: return; val p = lp ?: return
        if (tucked) return
        tucked = true
        val size = p.width
        val left = p.x + size / 2 < screenW / 2
        animateX(v, p, if (left) -size / 2 else screenW - size / 2)
        v.animate().alpha(prefs.avatarOpacity * 0.5f).setDuration(250).start()
    }

    private fun untuck() {
        val v = view ?: return; val p = lp ?: return
        lastActive = SystemClock.uptimeMillis()
        if (!tucked) return
        tucked = false
        val size = p.width
        animateX(v, p, if (p.x + size / 2 < screenW / 2) 0 else screenW - size)
        v.animate().alpha(prefs.avatarOpacity).setDuration(200).start()
    }

    // the bubble shows whoever is current (the active assistant, or the one that was just called by name); when that
    // changes, or their picture is replaced, swap the picture too (the 3D side follows by itself in AvatarView)
    private var shownPicture = ""
    private fun pictureFile() = prefs.avatarFile.takeIf { it.isNotEmpty() }?.let { File(ctx.filesDir, it) }
    private fun pictureKey() = prefs.id + "|" + prefs.avatarFile + "|" + (pictureFile()?.lastModified() ?: 0)
    private fun syncPicture() { val v = view ?: return; val k = pictureKey(); if (k != shownPicture) { shownPicture = k; v.setImageFile(pictureFile()) } }

    private val idleTick = object : Runnable {
        override fun run() {
            syncPicture()
            val idle = AssistantRuntime.ui == Ui.IDLE
            val hideAfter = prefs.avatarAutoHideSec
            if (idle && prefs.avatarMode == "always" && hideAfter > 0 &&
                SystemClock.uptimeMillis() - lastActive > hideAfter * 1000L) tuck()
            main.postDelayed(this, 1000)
        }
    }

    private fun onStateChanged() {
        syncPicture()
        val ui = AssistantRuntime.ui
        view?.let { v ->
            v.state = ui
            v.contentDescription = if (AssistantRuntime.inSession) "Assistant avatar. Tap to end the conversation, long-press for menu."
                else "Assistant avatar. Tap to talk, long-press for menu."
            if (ui != Ui.IDLE) { untuck(); v.visibility = View.VISIBLE }
            else if (prefs.avatarMode == "active") v.visibility = View.GONE
            else v.visibility = View.VISIBLE
            if (ui != Ui.IDLE) lastActive = SystemClock.uptimeMillis()
        }
        edge?.active = ui == Ui.LISTENING || ui == Ui.WAKING
    }

    private fun addEdge() {
        val e = EdgeGlowView(ctx).apply { color = prefs.glowColor }
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        // Android 12+ blocks touches through overlays above 0.8 opacity unless fully untouchable; this one is untouchable.
        p.alpha = 0.8f
        wm.addView(e, p)
        edge = e
    }

    // ---------- long-press menu ----------
    private var menu: View? = null
    private val dismissMenuRunnable = Runnable { dismissMenu() }

    private fun dismissMenu() {
        main.removeCallbacks(dismissMenuRunnable)
        menu?.let { runCatching { wm.removeView(it) } }; menu = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        dismissMenu()
        val p = lp ?: return
        val dark = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val fg = if (dark) 0xFFF1F1F4.toInt() else 0xFF1C1B1F.toInt()
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(6), px(6), px(6), px(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(18).toFloat(); setColor(if (dark) 0xF02B2930.toInt() else 0xF7FFFFFF.toInt())
            }
            elevation = px(8).toFloat()
        }
        fun item(label: String, action: () -> Unit) {
            box.addView(android.widget.TextView(ctx).apply {
                text = label; textSize = 17f; setTextColor(fg)
                setPadding(px(18), px(12), px(18), px(12))
                minWidth = px(190)
                setOnClickListener { dismissMenu(); action() }
            })
        }
        if (AssistantRuntime.inSession) item("■  End conversation") { AssistantRuntime.endSession(ctx, "avatar menu") }
        else item("🎙  Talk") { AssistantRuntime.listenNow(ctx) }
        item("💬  Conversation") {
            ctx.startActivity(Intent(ctx, app.belle.assistant.ui.MainActivity::class.java).putExtra("tab", "chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        item("⚙  Settings") { openSettings() }
        // Assistant switcher
        val ids = app.belle.assistant.ProfileStore.ids(ctx)
        if (ids.size > 1) {
            val active = app.belle.assistant.ProfileStore.activeId(ctx)
            ids.filter { it != active }.take(4).forEach { id ->
                item("👤  Switch to ${app.belle.assistant.ProfileStore.name(ctx, id)}") {
                    app.belle.assistant.ProfileStore.setActive(ctx, id); AssistantRuntime.reloadWakeWords(ctx); show()
                }
            }
        }
        item("👁  Hide bubble for now") { view?.visibility = View.GONE; main.postDelayed({ onStateChanged() }, 30 * 60_000L) }
        val mp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            val onLeft = p.x + p.width / 2 < screenW / 2
            x = if (onLeft) maxOf(0, p.x) + p.width + px(4) else maxOf(0, p.x - px(210))
            y = p.y.coerceAtMost(screenH - px(260))
        }
        box.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismissMenu(); true } else false }
        wm.addView(box, mp)
        menu = box
        main.postDelayed(dismissMenuRunnable, 6000)
    }

    private fun openSettings() {
        ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Soft pulsing glow around the screen edges while listening. Never receives touches. */
class EdgeGlowView(ctx: Context) : View(ctx) {
    var color = 0xFF4FC3F7.toInt()
    var active = false
        set(v) { field = v; visibility = if (v) VISIBLE else GONE; invalidate() }
    private val paint = Paint()
    private val start = SystemClock.uptimeMillis()
    init { visibility = GONE }

    override fun onDraw(c: Canvas) {
        if (!active) return
        val t = (SystemClock.uptimeMillis() - start) / 1000.0
        val a = (140 + 80 * sin(t * 2 * Math.PI * 1.2)).toInt().coerceIn(0, 255)
        val col = (color and 0x00FFFFFF) or (a shl 24)
        val clear = color and 0x00FFFFFF
        val th = 18 * resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, 0f, th, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, th, paint)
        paint.shader = LinearGradient(0f, h, 0f, h - th, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, h - th, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, th, 0f, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, th, h, paint)
        paint.shader = LinearGradient(w, 0f, w - th, 0f, col, clear, Shader.TileMode.CLAMP); c.drawRect(w - th, 0f, w, h, paint)
        postInvalidateOnAnimation()
    }
}
