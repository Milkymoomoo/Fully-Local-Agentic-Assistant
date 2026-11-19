package app.belle.assistant

import android.content.Context
import android.content.SharedPreferences
import app.belle.assistant.core.ProfileWake
import org.json.JSONObject
import java.util.UUID

/**
 * Multiple assistants. Each profile keeps ALL of its settings in its own
 * private SharedPreferences file; [Prefs] always reads the profile that is
 * currently answering (the session profile if a wake word picked one, else the
 * active one). The original single-assistant settings become profile "default".
 */
object ProfileStore {
    private const val GLOBAL = "belle_global"
    const val DEFAULT_ID = "default"

    /** Profile answering the current conversation (set when a wake word picks a non-active assistant). */
    @Volatile var sessionProfile: String? = null

    private fun g(ctx: Context): SharedPreferences { SettingsRev.observe(); return ctx.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE) }
    fun fileFor(id: String) = if (id == DEFAULT_ID) "belle" else "belle_profile_$id"

    fun ids(ctx: Context): List<String> = g(ctx).getString("ids", null)?.split(",")?.filter { it.isNotBlank() } ?: listOf(DEFAULT_ID)
    private fun setIds(ctx: Context, v: List<String>) = g(ctx).edit().putString("ids", v.distinct().joinToString(",")).save()

    fun activeId(ctx: Context): String = g(ctx).getString("active", DEFAULT_ID)!!.takeIf { it in ids(ctx) } ?: DEFAULT_ID
    fun setActive(ctx: Context, id: String) {
        g(ctx).edit().putString("active", id).commit().also { SettingsRev.bump() }; sessionProfile = null
        // the one widget type always follows the active assistant: drop the previous assistant's cached frames and redraw
        runCatching { app.belle.assistant.ui.AssistantWidget.avatarChanged(ctx) }
    }

    /** The profile whose settings are in effect right now. */
    fun currentId(ctx: Context): String = sessionProfile?.takeIf { it in ids(ctx) } ?: activeId(ctx)

    /** "Let each assistant answer its own wake words" (off by default). */
    fun multiWake(ctx: Context) = g(ctx).getBoolean("multi_wake", false)
    fun setMultiWake(ctx: Context, v: Boolean) = g(ctx).edit().putBoolean("multi_wake", v).save()
    fun priority(ctx: Context): List<String> = g(ctx).getString("priority", null)?.split(",")?.filter { it.isNotBlank() } ?: ids(ctx)
    fun setPriority(ctx: Context, v: List<String>) = g(ctx).edit().putString("priority", v.joinToString(",")).save()

    fun prefsOf(ctx: Context, id: String) = Prefs(ctx, id)
    /** The thinking model chosen most recently in any profile (fallback for profiles that never picked one). */
    fun lastLlm(ctx: Context): String = g(ctx).getString("last_llm", "")!!
    fun setLastLlm(ctx: Context, v: String) = g(ctx).edit().putString("last_llm", v).save()
    /** Settings shown as the one-page Simple screen (default) or the full Advanced list. App-wide. */
    fun simpleSettings(ctx: Context) = g(ctx).getBoolean("simple_settings", true)
    fun setSimpleSettings(ctx: Context, v: Boolean) = g(ctx).edit().putBoolean("simple_settings", v).save()
    fun name(ctx: Context, id: String) = prefsOf(ctx, id).assistantName

    fun create(ctx: Context, name: String, copyFrom: String? = null): String {
        val id = UUID.randomUUID().toString().take(8)
        val dst = ctx.getSharedPreferences(fileFor(id), Context.MODE_PRIVATE).edit()
        if (copyFrom != null) copyAll(ctx.getSharedPreferences(fileFor(copyFrom), Context.MODE_PRIVATE), dst)
        dst.save()
        setIds(ctx, ids(ctx) + id)
        runCatching { app.belle.assistant.ui.AssistantWidget.updateAll(ctx) }
        val p = Prefs(ctx, id)
        val oldName = if (copyFrom != null) Prefs(ctx, copyFrom).assistantName else null
        p.assistantName = name
        if (copyFrom == null || oldName == null) {
            p.wakePhrases = app.belle.assistant.core.WakeWord.defaultPhrases(name)
            p.sleepPhrases = Prefs.defaultSleepPhrases(name)
        } else {
            val re = Regex("\\b${Regex.escape(oldName.lowercase())}\\b")
            p.wakePhrases = p.wakePhrases.map { it.replace(re, name.lowercase()) }
            p.sleepPhrases = p.sleepPhrases.map { it.replace(re, name.lowercase()) }
        }
        return id
    }

    /**
     * First run: the two bundled default assistants. Bob From Accounting is the built-in profile; Boob Dragon is added
     * next to it with her own picture (keeping its painted background), a female voice and her own wake words.
     * Both get their bundled 3D models (assets/default_avatars, ~4 MB each: 70k triangles, 1024 px textures) imported
     * in the background. Runs once; everything stays user-editable and deletable afterwards.
     */
    fun ensureDefaults(ctx: Context) {
        val ac = ctx.applicationContext
        app.belle.assistant.avatar.Avatar3DFiles.upgradeDetailLevelsInBackground(ac)
        if (g(ac).getBoolean("defaults_v2", false)) return
        g(ac).edit().putBoolean("defaults_v2", true).commit().also { SettingsRev.bump() }
        val boob = ids(ac).firstOrNull { name(ac, it).equals(BOOB_NAME, true) } ?: run {
            val id = create(ac, BOOB_NAME)
            val p = Prefs(ac, id)
            p.wakePhrases = listOf("hey boob dragon", "okay boob dragon", "hey boob")
            p.sleepPhrases = Prefs.defaultSleepPhrases(BOOB_NAME)
            p.voiceDir = app.belle.assistant.models.ModelFiles.DEFAULT_FEMALE_VOICE
            p.bgMode = "own"
            runCatching {
                val f = java.io.File(ac.filesDir, "avatar_boob_dragon.webp")
                ac.assets.open("default_avatars/boob_dragon.webp").use { i -> f.outputStream().use { i.copyTo(it) } }
                p.avatarFile = f.name
            }
            id
        }
        Thread({
            app.belle.assistant.avatar.Avatar3DFiles.builtIn = true
            try { for ((id, asset) in listOf(DEFAULT_ID to "bob.glb", boob to "boob_dragon.glb")) {
                if (id !in ids(ac) || app.belle.assistant.avatar.Avatar3DFiles.has(ac, id)) continue
                runCatching {
                    val bytes = ac.assets.open("default_avatars/$asset").use { it.readBytes() }
                    app.belle.assistant.avatar.Avatar3DFiles.import(ac, id, mapOf(asset to bytes))
                }.onFailure { android.util.Log.w("ProfileStore", "default 3D avatar $asset failed", it) }
            } } finally { app.belle.assistant.avatar.Avatar3DFiles.builtIn = false }
            runCatching { app.belle.assistant.ui.AssistantWidget.updateAll(ac) }
        }, "belle-defaults").apply { priority = Thread.MIN_PRIORITY }.start()
    }
    const val BOOB_NAME = "Boob Dragon"

    fun duplicate(ctx: Context, id: String) = create(ctx, name(ctx, id) + " 2", copyFrom = id)

    fun delete(ctx: Context, id: String) {
        if (ids(ctx).size <= 1) return
        ctx.getSharedPreferences(fileFor(id), Context.MODE_PRIVATE).edit().clear().save()
        setIds(ctx, ids(ctx) - id)
        if (activeId(ctx) == id) setActive(ctx, ids(ctx).first())
        setPriority(ctx, priority(ctx) - id)
    }

    fun wakeTable(ctx: Context) = ids(ctx).map { ProfileWake(it, name(ctx, it), Prefs(ctx, it).wakePhrases) }

    /** Export one profile (all settings) as JSON. Secrets: there are none (no API keys anywhere). */
    fun export(ctx: Context, id: String): String {
        val o = JSONObject().put("belle_profile", 1)
        val data = JSONObject()
        for ((k, v) in ctx.getSharedPreferences(fileFor(id), Context.MODE_PRIVATE).all) when (v) {
            is Set<*> -> data.put(k, JSONObject().put("set", org.json.JSONArray(v.toList())))
            else -> data.put(k, v)
        }
        return o.put("settings", data).toString(2)
    }

    fun import(ctx: Context, json: String): String {
        val o = JSONObject(json)
        require(o.optInt("belle_profile") == 1) { "Not a profile file" }
        val data = o.getJSONObject("settings")
        val id = UUID.randomUUID().toString().take(8)
        val e = ctx.getSharedPreferences(fileFor(id), Context.MODE_PRIVATE).edit()
        // exactly one 3D shader, whatever the file says
        val map = LinkedHashMap<String, Any?>(); for (k in data.keys()) map[k] = data.get(k)
        app.belle.assistant.core.Shader3D.sanitize(map)
        for ((k, v) in map) when (v) {
            is Float -> e.putFloat(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Double -> e.putFloat(k, v.toFloat())
            is String -> e.putString(k, v)
            is JSONObject -> v.optJSONArray("set")?.let { a -> e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
        }
        e.save()
        setIds(ctx, ids(ctx) + id)
        return id
    }

    private fun copyAll(src: SharedPreferences, dst: SharedPreferences.Editor) {
        for ((k, v) in src.all) when (v) {
            is Boolean -> dst.putBoolean(k, v); is Int -> dst.putInt(k, v); is Long -> dst.putLong(k, v)
            is Float -> dst.putFloat(k, v); is String -> dst.putString(k, v)
            is Set<*> -> dst.putStringSet(k, v.map { it.toString() }.toSet())
        }
    }
}
