package android.content

class ComponentName(val packageName: String, val className: String) {
    constructor(ctx: Context, c: Class<*>) : this("app.belle.assistant", c.name)
    fun flattenToString() = "$packageName/$className"
}
