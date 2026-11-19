package app.belle.assistant.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.belle.assistant.core.ActionResult

/**
 * Lets the assistant act inside any app: tap a button by its label, type, scroll,
 * go back/home, read the screen. Only acts when you ask; it does not log or send
 * screen content anywhere.
 */
class BelleAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun perform(op: String, target: String): ActionResult = when (op) {
        "back" -> global(GLOBAL_ACTION_BACK, "Going back.")
        "home" -> global(GLOBAL_ACTION_HOME, "Going home.")
        "recents" -> global(GLOBAL_ACTION_RECENTS, "Here are your recent apps.")
        "notifications" -> global(GLOBAL_ACTION_NOTIFICATIONS, "Here are your notifications.")
        "quick_settings" -> global(GLOBAL_ACTION_QUICK_SETTINGS, "Opening quick settings.")
        "lock" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN, "Locking.") else ActionResult(false, "Can't lock on this Android version.")
        "screenshot" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken.") else ActionResult(false, "Can't take screenshots here.")
        "scroll_down" -> scroll(true)
        "scroll_up" -> scroll(false)
        "tap" -> tap(target, long = false)
        "long_press" -> tap(target, long = true)
        "type" -> type(target)
        "read_screen" -> ActionResult(true, readScreen().ifEmpty { "I don't see any text on the screen." })
        else -> ActionResult(false, "I can't do that on the screen.")
    }

    private fun global(a: Int, s: String) = ActionResult(performGlobalAction(a), s)

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val roots = windows?.mapNotNull { it.root }?.ifEmpty { null } ?: listOfNotNull(rootInActiveWindow)
        for (root in roots) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (stack.isNotEmpty() && out.size < 3000) {
                val n = stack.removeLast()
                out += n
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return out
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()

    /** Best on-screen element whose text/description matches what was said. */
    fun findTarget(target: String): AccessibilityNodeInfo? {
        val q = target.lowercase().trim()
        if (q.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            val l = label(n).lowercase()
            if (l.isEmpty()) continue
            val s = when {
                l == q -> 100
                l.startsWith(q) -> 80
                l.contains(q) -> 60
                q.contains(l) && l.length > 2 -> 40
                AppMatcher.editDistance(q, l) <= maxOf(1, q.length / 4) -> 50
                else -> 0
            }
            if (s > bestScore) { bestScore = s; best = n }
        }
        return best
    }

    private fun tap(target: String, long: Boolean): ActionResult {
        val node = findTarget(target) ?: return ActionResult(false, "I don't see $target on the screen.")
        var n: AccessibilityNodeInfo? = node
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (n != null) {
            if ((if (long) n.isLongClickable else n.isClickable) && n.performAction(action)) return ActionResult(true, "Done.")
            n = n.parent
        }
        // Not clickable via the tree (e.g. games, web views): tap its centre with a gesture.
        val r = Rect(); node.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, if (long) 800 else 60)).build()
        return ActionResult(dispatchGesture(g, null, null), "Done.")
    }

    private fun type(text: String): ActionResult {
        val field = allNodes().firstOrNull { it.isFocused && it.isEditable }
            ?: allNodes().firstOrNull { it.isEditable && it.isVisibleToUser }
            ?: return ActionResult(false, "I don't see a text box.")
        val existing = field.text?.toString().orEmpty().let { if (field.isShowingHintText) "" else it }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, (existing + (if (existing.isEmpty()) "" else " ") + text))
        }
        return ActionResult(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "Typed it.")
    }

    private fun scroll(down: Boolean): ActionResult {
        val scrollable = allNodes().filter { it.isScrollable && it.isVisibleToUser }
            .maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width() * r.height() }
        if (scrollable != null) {
            val a = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (scrollable.performAction(a)) return ActionResult(true, "Scrolling.")
        }
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (down) dm.heightPixels * 0.75f to dm.heightPixels * 0.25f else dm.heightPixels * 0.25f to dm.heightPixels * 0.75f
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
        return ActionResult(dispatchGesture(g, null, null), "Scrolling.")
    }

    fun readScreen(): String {
        val seen = LinkedHashSet<String>()
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            val t = n.text?.toString()?.trim() ?: continue
            if (t.length > 1) seen += t
            if (seen.size >= 40) break
        }
        return seen.joinToString(". ").take(1200)
    }

    companion object {
        @Volatile var instance: BelleAccessibilityService? = null
    }
}
