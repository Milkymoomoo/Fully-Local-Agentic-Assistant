package app.belle.assistant.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.belle.assistant.Prefs
import app.belle.assistant.ProfileStore
import app.belle.assistant.R
import app.belle.assistant.avatar.Avatar3DFiles
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantService
import java.io.File

/**
 * ONE home-screen widget type. It always shows whichever assistant is active
 * and follows automatically when the active assistant changes.
 * Per widget option: 2D picture or 3D avatar (3D is greyed out when the active
 * assistant has no 3D mesh; it then shows 2D).
 */
class AssistantWidget : AppWidgetProvider() {

    // Rendering decodes the avatar and frames it: keep it off the main thread (it caused "executing service" ANRs
    // when the service's state listener refreshed the widget on the main looper).
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { val app = ctx.applicationContext; Animator.post { ids.forEach { runCatching { render(app, mgr, it) } }; Animator.ensure(app) } }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle?) { val app = ctx.applicationContext; Animator.post { runCatching { render(app, mgr, id) }; Animator.reset() } }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(); ids.forEach { e.remove("mode_$it") }; e.apply()
    }

    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == ACTION_TOGGLE) {
            val id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val active = ProfileStore.activeId(ctx)
            val now = effectiveMode(sp.getString("mode_$id", "2d")!!, Avatar3DFiles.has(ctx, active))
            when (val next = toggleTarget(now, Avatar3DFiles.has(ctx, active))) {
                null -> { convHint = null; hintUntil = System.currentTimeMillis() + HINT_MS }     // no 3D mesh: stay on 2D, show a short hint
                else -> sp.edit().putString("mode_$id", next).apply()
            }
            updateAll(ctx); return
        }
        if (i.action == ACTION_CONV) {
            val p = Prefs(ctx, ProfileStore.activeId(ctx))
            p.continuous = !p.continuous
            hintUntil = System.currentTimeMillis() + HINT_MS; convHint = if (p.continuous) "Continuous conversation on" else "Continuous conversation off"
            updateAll(ctx); return
        }
        super.onReceive(ctx, i)
    }

    companion object {
        private const val PREFS = "belle_widgets"
        private const val ACTION_TOGGLE = "app.belle.assistant.WIDGET_TOGGLE"
        private const val ACTION_CONV = "app.belle.assistant.WIDGET_CONV"
        /** Short confirmation after the conversation toggle (shown instead of the 3D hint until [hintUntil]). */
        @Volatile private var convHint: String? = null

        /** Settings › Avatar › Home-screen widget › "Moving picture". Off = one still picture: no idle loop, no live
         *  frames while talking (saves battery). The name, state line and buttons still update. */
        fun animated(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("animate", true)
        fun setAnimated(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("animate", on).apply(); updateAll(ctx)
        }
        /** What moves on the widget: the launcher-played idle loop when idle, live frames during a conversation. */
        data class Motion(val idleLoop: Boolean, val liveFrames: Boolean)
        fun motion(animate: Boolean, inConversation: Boolean) = Motion(idleLoop = animate && !inConversation, liveFrames = animate && inConversation)

        /** Continuous-conversation toggle under the 2D/3D one: a speech-bubble button, lit when on. */
        fun convBitmap(ctx: Context, on: Boolean): Bitmap {
            val d = ctx.resources.displayMetrics.density
            val w = (34 * d).toInt()
            val b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b)
            val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val r = w / 2f
            pnt.color = if (on) 0xFFB388FF.toInt() else 0xCC17101A.toInt(); c.drawCircle(r, r, r - 1, pnt)
            pnt.style = android.graphics.Paint.Style.STROKE; pnt.strokeWidth = 1.5f * d; pnt.color = 0x66FFFFFF; c.drawCircle(r, r, r - 1, pnt)
            // bubble with a loop arrow feel: two stacked bubbles = "keeps talking"
            pnt.style = android.graphics.Paint.Style.FILL; pnt.color = if (on) 0xFF1A0E22.toInt() else 0xFFFFFFFF.toInt()
            c.drawRoundRect(w * 0.22f, w * 0.26f, w * 0.70f, w * 0.56f, 5 * d, 5 * d, pnt)
            val path = android.graphics.Path().apply { moveTo(w * 0.30f, w * 0.54f); lineTo(w * 0.28f, w * 0.68f); lineTo(w * 0.42f, w * 0.55f); close() }
            c.drawPath(path, pnt)
            pnt.alpha = if (on) 255 else 170
            c.drawRoundRect(w * 0.46f, w * 0.48f, w * 0.80f, w * 0.72f, 4 * d, 4 * d, pnt)
            if (!on) { pnt.color = 0xFFFF8A80.toInt(); pnt.strokeWidth = 2 * d; pnt.style = android.graphics.Paint.Style.STROKE; c.drawLine(w * 0.2f, w * 0.8f, w * 0.8f, w * 0.2f, pnt) }
            return b
        }
        const val WAKE_ROTATE_MS = 10_000L
        private const val HINT_MS = 3_500L
        private var lastKey = ""
        @Volatile private var hintUntil = 0L

        /** Redraw every widget (active assistant changed, avatar changed, state changed). */
        @Volatile private var bgHooked = false
        /** The active assistant's avatar changed (picture, 3D import/removal, a new active assistant): drop cached frames and redraw. */
        fun avatarChanged(ctx: Context) { WidgetIdleLoop.forget(); Animator.reset(); updateAll(ctx) }
        fun updateAll(ctx: Context) {
            if (!bgHooked) { bgHooked = true; val a = ctx.applicationContext; app.belle.assistant.avatar.Backgrounds.onDecoded += { updateAll(a) } }
            val app = ctx.applicationContext
            Animator.post {
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(ComponentName(app, AssistantWidget::class.java))
                ids.forEach { runCatching { render(app, mgr, it) }.onFailure { e -> android.util.Log.w("Widget", "render failed", e) } }
                Animator.ensure(app)
            }
        }

        /** The one 2D/3D toggle: where a tap goes. null = can't (no 3D mesh for this assistant) → stay on 2D with a hint. */
        fun toggleTarget(current: String, has3d: Boolean): String? = if (current == "3d") "2d" else if (has3d) "3d" else null

        /** Which wake word the widget suggests right now: rotates through the list every [WAKE_ROTATE_MS]. */
        fun wakeHint(phrases: List<String>, fallback: String, nowMs: Long): String =
            if (phrases.isEmpty()) fallback else phrases[((nowMs / WAKE_ROTATE_MS) % phrases.size).toInt()]

        /** The line under the name. */
        fun stateLine(p: Prefs, has3d: Boolean, nowMs: Long): String = when {
            nowMs < hintUntil && convHint != null -> convHint!!
            nowMs < hintUntil && !has3d -> "No 3D model for ${p.assistantName} yet. Add one in the app."
            AssistantRuntime.inSession && AssistantRuntime.ui == AssistantRuntime.Ui.SPEAKING -> "Speaking"
            AssistantRuntime.inSession && AssistantRuntime.ui == AssistantRuntime.Ui.THINKING -> "Thinking…"
            AssistantRuntime.inSession -> "Listening"
            // "Read to me": the book and chapter; tapping the line plays / pauses it
            AssistantRuntime.bookNow() != null -> AssistantRuntime.bookNow()!!.let { (line, playing) -> (if (playing) "\u25B6 " else "\u275A\u275A ") + line }
            else -> "Say \u201C${wakeHint(p.wakePhrases, p.assistantName, nowMs)}\u201D"
        }

        /**
         * Animates the widget avatar (2D puppet or 3D) by pushing small frames with partiallyUpdateAppWidget.
         * Frame rate follows the widget's detail setting: ~2 fps idle, 4–12 fps in a conversation; frames ≤ 360 px.
         * It also re-renders the text part when the suggested wake word rotates (every 10 s) or a hint expires, and
         * animates an RGB-piping name. With animation off it only wakes up for those text changes.
         */
        object Animator {
            private val thread = android.os.HandlerThread("widget-anim").apply { start() }
            private val h = android.os.Handler(thread.looper)
            @Volatile private var running = false
            /** Debug only: when set, every pushed widget frame is also saved here as <epochMs>_<ui>.jpg (proof videos). */
            @Volatile var frameDumpDir: File? = null
            private val views = HashMap<String, app.belle.assistant.avatar.AvatarView>()
            private var textKey = ""
            private var wasInConv = false
            // Proof rendering: the same RemoteViews the launcher gets, applied to a real view tree off screen and drawn
            // to a picture — what the launcher would show, without needing the launcher (slow emulators).
            private var proofView: View? = null; private var proofGeo: Geo? = null
            fun proofFull(ctx: Context, id: Int, rv: RemoteViews, g: Geo) = h.post { runCatching {
                val parent = android.widget.FrameLayout(ctx)
                proofView = rv.apply(ctx, parent); proofGeo = g; proofSave(ctx, "FULL")
            }.onFailure { android.util.Log.w("Widget", "proof render failed", it) } }
            private fun proofPartial(ctx: Context, rv: RemoteViews, tag: String) = runCatching {
                val v = proofView ?: return@runCatching; rv.reapply(ctx, v); proofSave(ctx, tag)
            }
            private fun proofSave(ctx: Context, tag: String) {
                val v = proofView ?: return; val g = proofGeo ?: return; val d = frameDumpDir ?: return
                v.measure(View.MeasureSpec.makeMeasureSpec(g.wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(g.hPx, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, g.wPx, g.hPx)
                val b = Bitmap.createBitmap(g.wPx, g.hPx, Bitmap.Config.ARGB_8888); v.draw(android.graphics.Canvas(b))
                d.mkdirs(); File(d, "${System.currentTimeMillis()}_$tag.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }
            /** Proof: render the whole widget at a given size (dp) to a PNG, through the normal render path. */
            fun renderAt(ctx: Context, wDp: Int, hDp: Int, out: File) = h.post { runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                val id = mgr.getAppWidgetIds(ComponentName(ctx, AssistantWidget::class.java)).firstOrNull() ?: -1
                val old = sizeOverride; sizeOverride = wDp to hDp
                val g = geom(ctx, mgr, id); val rv = build(ctx, mgr, id); sizeOverride = old
                val v = rv.apply(ctx, android.widget.FrameLayout(ctx))
                v.measure(View.MeasureSpec.makeMeasureSpec(g.wPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(g.hPx, View.MeasureSpec.EXACTLY)); v.layout(0, 0, g.wPx, g.hPx)
                val b = Bitmap.createBitmap(g.wPx, g.hPx, Bitmap.Config.ARGB_8888); v.draw(android.graphics.Canvas(b))
                out.parentFile?.mkdirs(); out.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                AssistantRuntime.event("widget-render: ${wDp}x${hDp}dp tall=${g.tall} -> $out")
            }.onFailure { AssistantRuntime.event("widget-render failed: $it") } }
            fun reset() = h.post { views.clear() }
            fun post(r: () -> Unit) = h.post(r)
            fun ensure(ctx: Context) {
                if (running) return
                running = true; val app = ctx.applicationContext; h.post { tick(app) }
            }
            private fun tick(ctx: Context) {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, AssistantWidget::class.java))
                if (ids.isEmpty()) { running = false; return }
                // screen off: push nothing (no frames, no text) and check again in a few seconds
                if (!(ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive) { h.postDelayed({ tick(ctx) }, 5000); return }
                val animate = animated(ctx)
                val active = ProfileStore.activeId(ctx); val p = Prefs(ctx, active)
                val inConv = AssistantRuntime.inSession
                val now = System.currentTimeMillis()
                // text part: full re-render only when what it says changes
                val tk = "$active|${now / WAKE_ROTATE_MS}|${now < hintUntil}|${p.assistantName}|${p.wakePhrases}|${p.nameStyle}|${p.nameFont}"
                if (tk != textKey) {
                    val first = textKey.isEmpty() || textKey.substringBefore('|') != active
                    textKey = tk
                    // a new profile needs a full update; a rotating wake word only re-sends the name + text (not the loop frames)
                    ids.forEach { id -> runCatching { if (first) render(ctx, mgr, id) else {
                        val g = geom(ctx, mgr, id)
                        val rv = RemoteViews(ctx.packageName, if (g.tall) R.layout.widget_assistant_tall else R.layout.widget_assistant)
                        rv.setImageViewBitmap(R.id.w_name, nameBitmap(ctx, p, g, now)); rv.setTextViewText(R.id.w_state, stateLine(p, Avatar3DFiles.has(ctx, active), now))
                        rv.setViewVisibility(R.id.w_state, if (g.compact && !inConv && now >= hintUntil && AssistantRuntime.bookNow() == null) View.GONE else View.VISIBLE)
                        mgr.partiallyUpdateAppWidget(id, rv) } } }
                }
                // conversation started/ended: switch between the launcher-played idle loop and live frames
                if (inConv != wasInConv) { wasInConv = inConv; if (!inConv) ids.forEach { runCatching { render(ctx, mgr, it) } } }
                if (frameDumpDir != null && proofView == null) runCatching { val id0 = ids.first(); proofFull(ctx, id0, build(ctx, mgr, id0), geom(ctx, mgr, id0)) }
                val rgb = p.nameStyle == "rgb"
                // idle motion is the launcher-played loop; only a conversation needs live frames from here
                val moving = motion(animate, inConv).liveFrames
                for (id in ids) runCatching {
                    val g = geom(ctx, mgr, id)
                    val rv = RemoteViews(ctx.packageName, if (g.tall) R.layout.widget_assistant_tall else R.layout.widget_assistant)
                    var push = false
                    if (rgb) { rv.setImageViewBitmap(R.id.w_name, nameBitmap(ctx, p, g, now)); push = true }
                    if (moving) {
                        val mode = effectiveMode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode_$id", "2d")!!, Avatar3DFiles.has(ctx, active))
                        val (w, hh) = g.animSize()
                        val key = "$active|$mode|$w|$hh|${p.avatarFile}|${Avatar3DFiles.mesh(ctx, active).lastModified()}|${p.framing("widget").toLine()}"
                        val v = views.getOrPut(key) { app.belle.assistant.avatar.AvatarView(ctx, "widget", fill = true, profileId = active).apply {
                            force3d = mode == "3d"; syncRender = true; setImageFile(p.avatarFile.takeIf { it.isNotEmpty() }?.let { File(ctx.filesDir, it) })
                            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY)); layout(0, 0, w, hh) } }
                        val ui = if (inConv) AssistantRuntime.ui else AssistantRuntime.Ui.IDLE
                        if (v.state != ui) v.state = ui
                        val bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888); v.draw(android.graphics.Canvas(bmp))
                        rv.setImageViewBitmap(R.id.w_avatar, bmp); rv.setViewVisibility(R.id.w_avatar, View.VISIBLE); rv.setViewVisibility(R.id.w_idle, View.GONE); push = true
                    }
                    if (push) { mgr.partiallyUpdateAppWidget(id, rv); if (frameDumpDir != null && id == ids.first()) proofPartial(ctx, rv, if (inConv) AssistantRuntime.ui.name else "IDLE") }
                }.onFailure { android.util.Log.w("Widget", "frame failed", it) }
                val delay = when {
                    moving -> 1000L / app.belle.assistant.core.Detail3D(p.detailFor("widget"), "widget").fps(inConv).coerceAtLeast(1)
                    rgb -> 500L
                    else -> minOf(WAKE_ROTATE_MS - now % WAKE_ROTATE_MS + 20, if (now < hintUntil) hintUntil - now + 20 else Long.MAX_VALUE)
                }
                h.postDelayed({ tick(ctx) }, delay)
            }
        }

        /** Widget geometry in px: tall (avatar above the name) or short (avatar left, name centre); toggle on the right. */
        data class Geo(val tall: Boolean, val wPx: Int, val hPx: Int, val avatarW: Int, val avatarH: Int, val nameW: Int, val nameMax: Float,
                       val nameFloor: Float = nameMax * 0.5f, val nameLines: Int = 2, val compact: Boolean = false) {
            fun animSize(): Pair<Int, Int> { val k = minOf(1f, 360f / maxOf(avatarW, avatarH)); return (avatarW * k).toInt().coerceAtLeast(64) to (avatarH * k).toInt().coerceAtLeast(64) }
        }
        fun geom(ctx: Context, mgr: AppWidgetManager, id: Int): Geo = sizeOverride?.let { geomDp(ctx, it.first, it.second) } ?: run {
            val o = mgr.getAppWidgetOptions(id)
            geomDp(ctx, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110))
        }
        /** Debug/proof only: pretend every widget has this size in dp (the launcher normally decides). */
        @Volatile var sizeOverride: Pair<Int, Int>? = null
        fun geomDp(ctx: Context, w: Int, h: Int): Geo {
            val d = ctx.resources.displayMetrics.density
            val wDp = w.coerceAtLeast(80); val hDp = h.coerceAtLeast(60)
            val tall = hDp >= TALL_DP
            return if (tall) {
                val nameW = ((wDp - 12 - 40) * d).toInt()          // keep clear of the toggle on the right
                Geo(true, (wDp * d).toInt(), (hDp * d).toInt(), ((wDp - 12) * d).toInt(), ((hDp - 12 - 52) * d).toInt().coerceAtLeast((40 * d).toInt()), nameW, 20 * d,
                    nameFloor = 11 * d, nameLines = 2)
            } else {
                // short: the avatar takes at most ~30 % of the width (a portrait crop of the full height) so the full name keeps
                // room; under 100 dp tall the wake-word line goes and the name may use up to 3 lines, never under 10 dp
                val compact = hDp < 100
                val avW = minOf(hDp - 12, ((wDp - 12) * (if (compact) 0.30f else 0.36f)).toInt()).coerceAtLeast(40)
                val nameW = ((wDp - 12 - avW - 34 - 12) * d).toInt().coerceAtLeast((56 * d).toInt())
                Geo(false, (wDp * d).toInt(), (hDp * d).toInt(), (avW * d).toInt(), ((hDp - 12) * d).toInt(), nameW, 18 * d,
                    nameFloor = 10 * d, nameLines = if (compact) 3 else 2, compact = compact)
            }
        }
        const val TALL_DP = 120

        /** The name art, trimmed to its drawn pixels so only the name itself is a tap target (no invisible margins). */
        private fun nameBitmap(ctx: Context, p: Prefs, g: Geo, now: Long) =
            trimTransparent(NameArt.render(ctx, p.assistantName, p.nameStyle, p.nameFont, g.nameW, g.nameMax, phase = (now % 3000L) / 3000f,
                floorPx = g.nameFloor, maxLines = g.nameLines), pad = (2 * ctx.resources.displayMetrics.density).toInt())

        /** Crops fully transparent rows/columns (keeps [pad] px). Returns [b] unchanged when nothing to trim. */
        fun trimTransparent(b: Bitmap, pad: Int = 0): Bitmap {
            val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 8) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
            if (x1 < 0) return b
            x0 = maxOf(0, x0 - pad); y0 = maxOf(0, y0 - pad); x1 = minOf(w - 1, x1 + pad); y1 = minOf(h - 1, y1 + pad)
            if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) return b
            return Bitmap.createBitmap(b, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        }

        /** Cheap state-only refresh, skipped when nothing visible changed. */
        fun onStateChanged(ctx: Context) {
            val key = "${ProfileStore.activeId(ctx)}|${AssistantRuntime.ui}|${AssistantRuntime.inSession}|${AssistantRuntime.bookNow()}"
            if (key == lastKey) return
            lastKey = key; updateAll(ctx)
        }

        /** What the widget shows for one widget id (pure decision, unit-tested via [effectiveMode]). */
        fun effectiveMode(chosen: String, has3d: Boolean) = if (chosen == "3d" && has3d) "3d" else "2d"

        /** The single 2D/3D switch on the right, drawn as a picture (vertical pill: 2D on top, 3D below). */
        fun toggleBitmap(ctx: Context, mode: String, has3d: Boolean): Bitmap {
            val d = ctx.resources.displayMetrics.density
            val w = (34 * d).toInt(); val h = (58 * d).toInt()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b)
            val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val r = w / 2f
            pnt.color = 0xCC17101A.toInt(); c.drawRoundRect(1f, 1f, w - 1f, h - 1f, r, r, pnt)
            pnt.style = android.graphics.Paint.Style.STROKE; pnt.strokeWidth = 1.5f * d; pnt.color = 0x66FFFFFF
            c.drawRoundRect(1f, 1f, w - 1f, h - 1f, r, r, pnt); pnt.style = android.graphics.Paint.Style.FILL
            val top = mode != "3d"
            val ky = if (top) h * 0.29f else h * 0.71f
            pnt.color = 0xFFB388FF.toInt(); c.drawCircle(w / 2f, ky, r - 3 * d, pnt)
            pnt.textAlign = android.graphics.Paint.Align.CENTER; pnt.typeface = android.graphics.Typeface.DEFAULT_BOLD; pnt.textSize = 11.5f * d
            fun label(t: String, y: Float, on: Boolean, enabled: Boolean) {
                pnt.color = when { on -> 0xFF1A0E22.toInt(); enabled -> 0xFFFFFFFF.toInt(); else -> 0x66FFFFFF }
                c.drawText(t, w / 2f, y - (pnt.descent() + pnt.ascent()) / 2, pnt)
            }
            label("2D", h * 0.29f, top, true); label("3D", h * 0.71f, !top, has3d)
            return b
        }

        /** "Choose book" on the left (mirrors the 2D/3D switch on the right): same pill, an open book and "Book". */
        fun bookBitmap(ctx: Context): Bitmap {
            val d = ctx.resources.displayMetrics.density
            val w = (34 * d).toInt(); val h = (58 * d).toInt()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b)
            val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val r = w / 2f
            pnt.color = 0xCC17101A.toInt(); c.drawRoundRect(1f, 1f, w - 1f, h - 1f, r, r, pnt)
            pnt.style = android.graphics.Paint.Style.STROKE; pnt.strokeWidth = 1.5f * d; pnt.color = 0x66FFFFFF
            c.drawRoundRect(1f, 1f, w - 1f, h - 1f, r, r, pnt); pnt.style = android.graphics.Paint.Style.FILL
            // open book: two pages meeting at the spine
            val cx = w / 2f; val cy = h * 0.36f; val bw = w * 0.30f; val bh = w * 0.36f
            pnt.color = 0xFFB388FF.toInt()
            val left = android.graphics.Path().apply { moveTo(cx - 0.6f * d, cy - bh * 0.42f); quadTo(cx - bw * 0.5f, cy - bh * 0.62f, cx - bw, cy - bh * 0.45f); lineTo(cx - bw, cy + bh * 0.55f)
                quadTo(cx - bw * 0.5f, cy + bh * 0.38f, cx - 0.6f * d, cy + bh * 0.58f); close() }
            val right = android.graphics.Path().apply { moveTo(cx + 0.6f * d, cy - bh * 0.42f); quadTo(cx + bw * 0.5f, cy - bh * 0.62f, cx + bw, cy - bh * 0.45f); lineTo(cx + bw, cy + bh * 0.55f)
                quadTo(cx + bw * 0.5f, cy + bh * 0.38f, cx + 0.6f * d, cy + bh * 0.58f); close() }
            c.drawPath(left, pnt); c.drawPath(right, pnt)
            pnt.textAlign = android.graphics.Paint.Align.CENTER; pnt.typeface = android.graphics.Typeface.DEFAULT_BOLD; pnt.textSize = 10.5f * d; pnt.color = 0xFFFFFFFF.toInt()
            c.drawText("Book", cx, h * 0.74f - (pnt.descent() + pnt.ascent()) / 2, pnt)
            return b
        }

        /** Book play / pause under it (mirrors the conversation toggle): lit with a pause sign while reading, a play sign otherwise. */
        fun bookPlayBitmap(ctx: Context, playing: Boolean, hasBook: Boolean): Bitmap {
            val d = ctx.resources.displayMetrics.density
            val w = (34 * d).toInt()
            val b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b)
            val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val r = w / 2f
            pnt.color = if (playing) 0xFFB388FF.toInt() else 0xCC17101A.toInt(); c.drawCircle(r, r, r - 1, pnt)
            pnt.style = android.graphics.Paint.Style.STROKE; pnt.strokeWidth = 1.5f * d; pnt.color = 0x66FFFFFF; c.drawCircle(r, r, r - 1, pnt)
            pnt.style = android.graphics.Paint.Style.FILL
            pnt.color = when { playing -> 0xFF1A0E22.toInt(); hasBook -> 0xFFFFFFFF.toInt(); else -> 0x66FFFFFF }
            if (playing) { c.drawRect(w * 0.33f, w * 0.30f, w * 0.45f, w * 0.70f, pnt); c.drawRect(w * 0.55f, w * 0.30f, w * 0.67f, w * 0.70f, pnt) }
            else c.drawPath(android.graphics.Path().apply { moveTo(w * 0.39f, w * 0.28f); lineTo(w * 0.39f, w * 0.72f); lineTo(w * 0.74f, w * 0.5f); close() }, pnt)
            return b
        }

        /** Book Stop (top left, mirroring the background picker): a square in a circle; dim when nothing is open. */
        fun bookStopBitmap(ctx: Context, open: Boolean): Bitmap {
            val d = ctx.resources.displayMetrics.density
            val w = (32 * d).toInt()
            val b = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b)
            val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val r = w / 2f
            pnt.color = 0xCC17101A.toInt(); c.drawCircle(r, r, r - 1, pnt)
            pnt.style = android.graphics.Paint.Style.STROKE; pnt.strokeWidth = 1.5f * d; pnt.color = 0x66FFFFFF; c.drawCircle(r, r, r - 1, pnt)
            pnt.style = android.graphics.Paint.Style.FILL; pnt.color = if (open) 0xFFFFFFFF.toInt() else 0x66FFFFFF
            c.drawRoundRect(w * 0.34f, w * 0.34f, w * 0.66f, w * 0.66f, 1.5f * d, 1.5f * d, pnt)
            return b
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val rv = build(ctx, mgr, id)
            mgr.updateAppWidget(id, rv)
            if (Animator.frameDumpDir != null) Animator.proofFull(ctx, id, rv, geom(ctx, mgr, id))
        }

        private fun build(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            val active = ProfileStore.activeId(ctx)
            val p = Prefs(ctx, active)
            val has3d = Avatar3DFiles.has(ctx, active)
            val chosen = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode_$id", "2d")!!
            val plan = app.belle.assistant.core.WidgetPlan.decide(active, chosen, has3d)
            val mode = plan.mode
            val g = geom(ctx, mgr, id)
            val now = System.currentTimeMillis()
            val rv = RemoteViews(ctx.packageName, if (g.tall) R.layout.widget_assistant_tall else R.layout.widget_assistant)
            rv.setImageViewBitmap(R.id.w_name, nameBitmap(ctx, p, g, now))
            rv.setContentDescription(R.id.w_name, "${p.assistantName}. Open the app")
            rv.setTextViewText(R.id.w_state, stateLine(p, has3d, now))
            // tiny widget: the wake-word line goes so the full name stays readable (it's still in the content description)
            rv.setViewVisibility(R.id.w_state, if (g.compact && !AssistantRuntime.inSession && now >= hintUntil) android.view.View.GONE else android.view.View.VISIBLE)
            if (g.compact) rv.setContentDescription(R.id.w_name, "${p.assistantName}. ${stateLine(p, has3d, now)}. Open the app")
            // Idle: the pre-rendered loop the launcher plays itself (always moving while visible). Until it's ready, or
            // while talking (live frames come from the Animator), the single image view shows a still/live frame.
            val animate = animated(ctx)
            val (lw, lh) = g.animSize()
            // a new look (background, avatar, framing, shader…) renders off-thread; until it's ready the previous loop
            // keeps playing — the widget never goes blank or freezes on a still while it waits
            val loop = if (animate) (WidgetIdleLoop.get(ctx, p, mode, lw, lh) { updateAll(ctx) } ?: WidgetIdleLoop.lastFor(plan)) else null
            rv.removeAllViews(R.id.w_idle)
            if (loop != null && !AssistantRuntime.inSession) {
                loop.frames.forEach { b -> rv.addView(R.id.w_idle, RemoteViews(ctx.packageName, R.layout.widget_idle_frame).apply { setImageViewBitmap(R.id.w_frame, b) }) }
                rv.setInt(R.id.w_idle, "setFlipInterval", loop.intervalMs)
                rv.setViewVisibility(R.id.w_idle, View.VISIBLE); rv.setViewVisibility(R.id.w_avatar, View.GONE)
            } else {
                val k = minOf(1f, (if (animate) 360f else 640f) / maxOf(g.avatarW, g.avatarH))
                val bmp = (if (mode == "3d") loadFile(Avatar3DFiles.preview(ctx, active)) else null) ?: avatar2d(ctx, p)
                rv.setImageViewBitmap(R.id.w_avatar, framed(bmp, p.framing("widget"), (g.avatarW * k).toInt().coerceAtLeast(64), (g.avatarH * k).toInt().coerceAtLeast(64),
                    app.belle.assistant.avatar.Backgrounds.frame(ctx, p, 0)))   // never blocks: a decode in progress redraws when done
                rv.setViewVisibility(R.id.w_idle, View.GONE); rv.setViewVisibility(R.id.w_avatar, View.VISIBLE)
            }
            // ONE 2D/3D toggle on the right; without a 3D mesh a tap stays on 2D and shows a hint
            rv.setImageViewBitmap(R.id.w_toggle, toggleBitmap(ctx, mode, has3d))
            rv.setContentDescription(R.id.w_toggle, when { mode == "3d" -> "3D avatar on. Tap for 2D"; has3d -> "2D picture on. Tap for 3D"; else -> "2D picture. No 3D model for ${p.assistantName} yet" })
            rv.setImageViewBitmap(R.id.w_conv, convBitmap(ctx, p.continuous))
            rv.setContentDescription(R.id.w_conv, if (p.continuous) "Continuous conversation on. Tap to turn off" else "Continuous conversation off. Tap to turn on")
            rv.setOnClickPendingIntent(R.id.w_conv, PendingIntent.getBroadcast(ctx, 100000 + id,
                Intent(ctx, AssistantWidget::class.java).setAction(ACTION_CONV).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            rv.setOnClickPendingIntent(R.id.w_toggle, PendingIntent.getBroadcast(ctx, id,
                Intent(ctx, AssistantWidget::class.java).setAction(ACTION_TOGGLE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            // the one extra control: a tiny pixel-art picture frame that opens the background picker for the active profile
            rv.setOnClickPendingIntent(R.id.w_bg, PendingIntent.getActivity(ctx, 3,
                Intent(ctx, MainActivity::class.java).putExtra("page", "background").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            rv.setContentDescription(R.id.w_bg, "Choose a background for ${p.assistantName}")
            // left pair (mirrors the right one): choose a book (the Read to me library), and play / pause it — the play
            // button goes straight to the book's playback service (fast: no assistant start, no model loading)
            rv.setImageViewBitmap(R.id.w_book, bookBitmap(ctx))
            rv.setOnClickPendingIntent(R.id.w_book, PendingIntent.getActivity(ctx, 8,
                Intent(ctx, MainActivity::class.java).putExtra("page", "books").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            rv.setContentDescription(R.id.w_book, "Choose a book to read aloud")
            val bookNow = AssistantRuntime.bookNow(); val hasBook = bookNow != null || AssistantRuntime.hasBooks(ctx)
            rv.setImageViewBitmap(R.id.w_bookplay, bookPlayBitmap(ctx, bookNow?.second == true, hasBook))
            rv.setOnClickPendingIntent(R.id.w_bookplay, app.belle.assistant.service.BookPlaybackService.pending(ctx, "toggle", 9))
            rv.setContentDescription(R.id.w_bookplay, when { bookNow?.second == true -> "Pause the book"; hasBook -> "Play the book"; else -> "No book yet: add one with Choose book" })
            rv.setImageViewBitmap(R.id.w_bookstop, bookStopBitmap(ctx, bookNow != null))
            rv.setOnClickPendingIntent(R.id.w_bookstop, app.belle.assistant.service.BookPlaybackService.pending(ctx, "stop", 10))
            rv.setContentDescription(R.id.w_bookstop, if (bookNow != null) "Stop the book (keeps your place)" else "Stop the book")
            // Tap avatar = talk / end; tap name = open the app
            val talk = Intent(ctx, AssistantService::class.java).setAction(if (AssistantRuntime.inSession) AssistantService.ACTION_END else AssistantService.ACTION_LISTEN).putExtra(AssistantService.EXTRA_FROM, "widget")
            // the whole avatar area is one tap target: the transparent top layer, plus the box, the still and the flipper
            // underneath (in case a launcher hands the touch to a child). A widget tap is a user-visible start, so the
            // microphone works afterwards even with the app closed.
            val talkPi = PendingIntent.getForegroundService(ctx, 1, talk, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            for (v in intArrayOf(R.id.w_tap, R.id.w_avatar_box, R.id.w_avatar, R.id.w_idle)) rv.setOnClickPendingIntent(v, talkPi)
            // Name = open the app on Home (not whatever page was last open). Nothing else on the widget is clickable:
            // empty space, the wake-word line and the background do nothing.
            rv.setOnClickPendingIntent(R.id.w_name, PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java).putExtra("tab", "home")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            rv.setViewVisibility(R.id.w_live, if (AssistantRuntime.inSession) View.VISIBLE else View.GONE)
            AssistantRuntime.bookNow()?.let { (_, playing) ->
                rv.setViewVisibility(R.id.w_state, View.VISIBLE)
                rv.setOnClickPendingIntent(R.id.w_state, app.belle.assistant.service.BookPlaybackService.pending(ctx, "toggle", 7))
                rv.setContentDescription(R.id.w_state, (if (playing) "Reading " else "Paused ") + AssistantRuntime.bookNow()?.first + ". Tap to " + if (playing) "pause" else "play")
            }
            return rv
        }

        /** Crop/zoom/pan the picture into a w×h frame with the chosen shape (transparent outside). */
        fun framed(src: Bitmap, f: app.belle.assistant.core.Framing, w: Int, h: Int, bg: Bitmap? = null): Bitmap {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            val side = minOf(w, h).toFloat()
            val path = android.graphics.Path()
            // circle stays round; rounded/full use the whole widget area (taller/wider when resized)
            val fw = if (f.shape == "circle") side else w.toFloat(); val fh = if (f.shape == "circle") side else h.toFloat()
            val l = (w - fw) / 2; val t = (h - fh) / 2
            when (f.shape) {
                "circle" -> { path.addCircle(w / 2f, h / 2f, side / 2, android.graphics.Path.Direction.CW); c.clipPath(path) }
                "rounded" -> { path.addRoundRect(l, t, l + fw, t + fh, side * 0.16f, side * 0.16f, android.graphics.Path.Direction.CW); c.clipPath(path) }
            }
            if (bg != null) app.belle.assistant.avatar.Backgrounds.drawCover(c, bg, android.graphics.RectF(l, t, l + fw, t + fh), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            // zoom 1 = whole picture fits (the default shows Bob's full upper scene); the user's zoom/pan apply on top.
            val pl = f.place(src.width.toFloat(), src.height.toFloat(), fw, fh)
            c.drawBitmap(src, null, android.graphics.RectF(l + pl[0], t + pl[1], l + pl[0] + pl[2], t + pl[1] + pl[3]), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            return out
        }

        private fun loadFile(f: File): Bitmap? = if (!f.exists()) null else runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = 640f / maxOf(info.size.width, info.size.height); if (s < 1) d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }.getOrNull()

        private fun avatar2d(ctx: Context, p: Prefs): Bitmap {
            val f = p.avatarFile.takeIf { it.isNotEmpty() }?.let { File(ctx.filesDir, it) }?.takeIf { it.exists() }
            val b = f?.let { loadFile(it) }   // GIF → first frame
                ?: BitmapFactory.decodeResource(ctx.resources, R.drawable.avatar_default).let { Bitmap.createScaledBitmap(it, 640, 640 * it.height / it.width, true) }
            // a chosen background has to show behind the avatar: cut the picture's own background out
            if (app.belle.assistant.avatar.Backgrounds.file(ctx, p) == null) return b
            app.belle.assistant.avatar.Cutout.init(ctx)
            return app.belle.assistant.avatar.Cutout.of(b, f?.let { "${it.path}|${it.lastModified()}" } ?: "default")
        }
    }
}
