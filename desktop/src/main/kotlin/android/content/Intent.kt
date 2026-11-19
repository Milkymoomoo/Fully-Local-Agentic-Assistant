package android.content

/**
 * Desktop stand-in: an action, data, extras and optionally a target screen class. What a desktop "start" does is
 * decided by [Context.startActivity]: web links open the browser, a shared file is shown in Explorer, an app screen
 * (e.g. the framing editor) opens its desktop window.
 */
class Intent(val action: String? = null, val data: android.net.Uri? = null) {
    constructor(ctx: Context, c: Class<*>) : this(null, null) { component = c }
    var component: Class<*>? = null; private set
    var type: String? = null; private set
    private val extras = HashMap<String, Any?>()
    fun addFlags(f: Int) = this
    fun setFlags(f: Int) = this
    fun setAction(a: String) = Intent(a, data).also { it.extras.putAll(extras); it.component = component; it.type = type }
    fun setType(t: String) = apply { type = t }
    fun setComponent(c: ComponentName) = this
    fun putExtra(k: String, v: String?) = apply { extras[k] = v }
    fun putExtra(k: String, v: Int) = apply { extras[k] = v }
    fun putExtra(k: String, v: Long) = apply { extras[k] = v }
    fun putExtra(k: String, v: Boolean) = apply { extras[k] = v }
    fun putExtra(k: String, v: Any?) = apply { extras[k] = v }
    fun getStringExtra(k: String) = extras[k] as? String
    fun getIntExtra(k: String, d: Int) = extras[k] as? Int ?: d
    fun getLongExtra(k: String, d: Long) = extras[k] as? Long ?: d
    fun getBooleanExtra(k: String, d: Boolean) = extras[k] as? Boolean ?: d
    @Suppress("UNCHECKED_CAST") fun <T> getParcelableExtra(k: String): T? = extras[k] as? T
    fun <T> getParcelableExtra(k: String, c: Class<T>): T? = c.cast(extras[k])
    fun hasExtra(k: String) = extras.containsKey(k)
    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"; const val ACTION_SEND = "android.intent.action.SEND"; const val ACTION_MAIN = "android.intent.action.MAIN"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"; const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"; const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME"; const val EXTRA_TITLE = "android.intent.extra.TITLE"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000; const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000; const val FLAG_GRANT_READ_URI_PERMISSION = 1
        const val CATEGORY_OPENABLE = "android.intent.category.OPENABLE"
        @JvmStatic fun createChooser(target: Intent, title: CharSequence?): Intent = target
    }
}
