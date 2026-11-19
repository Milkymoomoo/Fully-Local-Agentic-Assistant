package android.app

import android.content.DialogInterface
import javax.swing.JOptionPane

/** Desktop stand-in for android.app.AlertDialog: a native-looking confirm box (Swing, modal to the app). */
class AlertDialog private constructor() : DialogInterface {
    override fun dismiss() {}
    override fun cancel() {}
    class Builder(ctx: android.content.Context) {
        private var title = ""; private var message: String? = null
        private val buttons = ArrayList<Pair<String, ((DialogInterface, Int) -> Unit)?>>()
        fun setTitle(t: CharSequence) = apply { title = t.toString() }
        fun setMessage(m: CharSequence) = apply { message = m.toString() }
        fun setPositiveButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { buttons.add(0, t.toString() to l) }
        fun setNeutralButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { buttons.add(t.toString() to l) }
        fun setNegativeButton(t: CharSequence, l: ((DialogInterface, Int) -> Unit)?) = apply { buttons.add(t.toString() to l) }
        fun setCancelable(b: Boolean) = this
        fun show(): AlertDialog {
            val d = AlertDialog()
            app.belle.desktop.ui.UiThread.post {
                val opts = buttons.map { it.first }.toTypedArray()
                val r = JOptionPane.showOptionDialog(null, message ?: "", title, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts.ifEmpty { arrayOf("OK") }, opts.firstOrNull())
                if (r >= 0 && r < buttons.size) buttons[r].second?.invoke(d, r)
            }
            return d
        }
    }
}
class Activity { companion object { const val RESULT_OK = -1; const val RESULT_CANCELED = 0 } }
/** Exact alarms need no permission on a PC (reminders run inside the program). */
class AlarmManager { fun canScheduleExactAlarms() = true }
