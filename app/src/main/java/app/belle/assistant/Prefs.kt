package app.belle.assistant

import android.content.Context
import android.content.SharedPreferences
import app.belle.assistant.core.PersonaPrompt

/**
 * All settings of one assistant profile, stored only on this phone.
 * Prefs(ctx) follows whichever profile is answering right now (see [ProfileStore]);
 * Prefs(ctx, id) pins a specific profile (settings screens, export).
 */
class Prefs(c: Context, private val profileId: String? = null) {
    private val ctx: Context = c.applicationContext
    private val sp: SharedPreferences
        get() = SettingsRev.observe().let { ctx.getSharedPreferences(ProfileStore.fileFor(profileId ?: ProfileStore.currentId(ctx)), Context.MODE_PRIVATE) }
    val id: String get() = profileId ?: ProfileStore.currentId(ctx)

    var assistantName: String
        get() = sp.getString("name", DEFAULT_NAME)!!
        set(v) = sp.edit().putString("name", v.trim().ifEmpty { DEFAULT_NAME }).save()
    /** Any number of wake phrases, all active at once. */
    var wakePhrases: List<String>
        get() = sp.getString("wake_phrases", null)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: app.belle.assistant.core.WakeWord.defaultPhrases(assistantName)
        set(v) = sp.edit().putString("wake_phrases", v.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).save()
    var sensitivity: Float
        get() = sp.getFloat("sensitivity", 0.5f)
        set(v) = sp.edit().putFloat("sensitivity", v).save()
    var wakeWordEnabled: Boolean
        get() = sp.getBoolean("wake_enabled", true)
        set(v) = sp.edit().putBoolean("wake_enabled", v).save()
    var serviceWanted: Boolean
        get() = sp.getBoolean("service_wanted", true)
        set(v) = sp.edit().putBoolean("service_wanted", v).save()
    var voiceDir: String
        get() = sp.getString("voice", app.belle.assistant.models.ModelFiles.DEFAULT_VOICE)!!
        set(v) = sp.edit().putString("voice", v).save()
    var voiceSpeed: Float
        get() = sp.getFloat("voice_speed", 1.0f)
        set(v) = sp.edit().putFloat("voice_speed", v).save()
    /** Speaker of the current voice (multi-speaker voices: Kokoro, LibriTTS); saved per voice. */
    var voiceSpeaker: Int
        get() = speakerFor(voiceDir)
        set(v) = setSpeakerFor(voiceDir, v)
    /** Saved per voice; falls back to the old single "voice_speaker" value for the voice in use. */
    fun speakerFor(voice: String): Int = sp.getInt("voice_speaker_$voice", if (voice == voiceDir) sp.getInt("voice_speaker", 0) else 0).coerceAtLeast(0)
    fun setSpeakerFor(voice: String, v: Int) = sp.edit().putInt("voice_speaker_$voice", v.coerceAtLeast(0)).save()
    /** Masculine (-1) ↔ feminine (+1) voice shift, saved per voice. */
    fun voiceShift(voice: String): Float = sp.getFloat("voice_shift_$voice", 0f)
    fun setVoiceShift(voice: String, v: Float) = sp.edit().putFloat("voice_shift_$voice", v.coerceIn(-1f, 1f)).save()
    var llmFile: String
        get() = sp.getString("llm_file", "")!!
        set(v) { sp.edit().putString("llm_file", v).save(); if (v.isNotEmpty()) ProfileStore.setLastLlm(ctx, v) }
    var llmThreads: Int
        get() = sp.getInt("llm_threads", 0)
        set(v) = sp.edit().putInt("llm_threads", v).save()
    /** ON: only the few-MB wake-word listener stays in memory; STT/TTS/LLM load on wake and unload when idle. */
    var loadOnDemand: Boolean
        get() = sp.getBoolean("on_demand", true)
        set(v) = sp.edit().putBoolean("on_demand", v).save()
    var unloadAfterSec: Int
        get() = sp.getInt("unload_after", 60)
        set(v) = sp.edit().putInt("unload_after", v.coerceIn(10, 3600)).save()
    var confirmMessages: Boolean
        get() = sp.getBoolean("confirm_sms", true)
        set(v) = sp.edit().putBoolean("confirm_sms", v).save()
    var chime: Boolean
        get() = sp.getBoolean("chime", true)
        set(v) = sp.edit().putBoolean("chime", v).save()
    var stopChime: Boolean
        get() = sp.getBoolean("stop_chime", true)
        set(v) = sp.edit().putBoolean("stop_chime", v).save()

    // ----- avatar & listening indicator -----
    var avatarEnabled: Boolean
        get() = sp.getBoolean("avatar_on", true)
        set(v) = sp.edit().putBoolean("avatar_on", v).save()
    /** "always" or "active" (only while listening/thinking/speaking). */
    var avatarMode: String
        get() = sp.getString("avatar_mode", "always")!!
        set(v) = sp.edit().putString("avatar_mode", v).save()
    var avatarSizeDp: Int
        get() = sp.getInt("avatar_size", 72)
        set(v) = sp.edit().putInt("avatar_size", v.coerceIn(40, 200)).save()
    var avatarOpacity: Float
        get() = sp.getFloat("avatar_opacity", 0.95f)
        set(v) = sp.edit().putFloat("avatar_opacity", v.coerceIn(0.2f, 1f)).save()
    /** Seconds idle before the bubble tucks into the screen edge; 0 = never. */
    var avatarAutoHideSec: Int
        get() = sp.getInt("avatar_autohide", 8)
        set(v) = sp.edit().putInt("avatar_autohide", v.coerceIn(0, 600)).save()
    var avatarX: Int
        get() = sp.getInt("avatar_x", -1)
        set(v) = sp.edit().putInt("avatar_x", v).save()
    var avatarY: Int
        get() = sp.getInt("avatar_y", 400)
        set(v) = sp.edit().putInt("avatar_y", v).save()
    /** File name of the user's avatar image inside filesDir ("" = built-in). */
    var avatarFile: String
        get() = sp.getString("avatar_file", "")!!
        set(v) = sp.edit().putString("avatar_file", v).save()
    /** Tapping the bubble during a conversation ends it (long-press menu always has End). */
    /** Talking mouth + body animation (breathing, head moves, gestures). Off = still picture, saves battery. */
    var avatarAnimate: Boolean
        get() = sp.getBoolean("avatar_animate", true)
        set(v) = sp.edit().putBoolean("avatar_animate", v).save()
    var avatarMouth: Boolean
        get() = sp.getBoolean("avatar_mouth", true)
        set(v) = sp.edit().putBoolean("avatar_mouth", v).save()
    /** Animated GIFs: "play" = play the GIF, mouth overlay only; "still" = first frame, fully rigged. */
    var avatarGifMode: String
        get() = sp.getString("avatar_gif_mode", "play")!!
        set(v) = sp.edit().putString("avatar_gif_mode", v).save()
    /** Rig (joints + mouth) per avatar picture. */
    fun rig(key: String): String? = sp.getString("rig_$key", null)
    fun setRig(key: String, v: String?) = sp.edit().apply { if (v == null) remove("rig_$key") else putString("rig_$key", v) }.save()
    val avatarKey: String get() = avatarFile.ifEmpty { "default" }

    /** Framing per surface ("home", "bubble", "widget"), or one for all. */
    var framingSameForAll: Boolean
        get() = sp.getBoolean("framing_same", true)
        set(v) = sp.edit().putBoolean("framing_same", v).save()
    fun framing(surface: String): app.belle.assistant.core.Framing {
        val key = if (framingSameForAll) "all" else surface
        return app.belle.assistant.core.Framing.parse(sp.getString("framing_${avatarKey}_$key", null))
            ?: app.belle.assistant.core.Framing.parse(sp.getString("framing_${avatarKey}_all", null))
            ?: app.belle.assistant.core.Framing.DEFAULT
    }
    fun setFraming(surface: String, f: app.belle.assistant.core.Framing) {
        val key = if (framingSameForAll) "all" else surface
        sp.edit().putString("framing_${avatarKey}_$key", f.toLine()).save()
    }

    // ---- 3D avatar look & cost (per profile; the widget and bubble use the same shader) ----
    /** ONE of none / retrostation / retro64 — a single key, so two shaders can never be on together. */
    /** Assistant voice volume 0..1 (per profile). */
    var voiceVolume: Float
        get() = sp.getFloat("voice_volume", 1f)
        set(v) = sp.edit().putFloat("voice_volume", v.coerceIn(0f, 1f)).save()
    /** Background behind the avatar: "own" = the picture's own background (default), "custom" = [bgFile]. Per profile. */
    var bgMode: String
        get() = sp.getString("bg_mode", "own")!!.let { if (it == "custom" && bgFile.isEmpty()) "own" else it }
        set(v) = sp.edit().putString("bg_mode", v).save()
    /** File name under files/backgrounds: .png/.jpg/.webp still, .gif animated, .mp4/.webm video (loops). */
    var bgFile: String
        get() = sp.getString("bg_file", "")!!
        set(v) = sp.edit().putString("bg_file", v).save()
    /** Background sound: muted by default; only videos have sound. */
    var bgSound: Boolean
        get() = sp.getBoolean("bg_sound", false)
        set(v) = sp.edit().putBoolean("bg_sound", v).save()
    var bgVolume: Float
        get() = sp.getFloat("bg_volume", 0.5f)
        set(v) = sp.edit().putFloat("bg_volume", v.coerceIn(0f, 1f)).save()
    /** How the name is drawn on the widget (and its previews): plain / gold / silver / rgb. Per profile. */
    var nameStyle: String
        get() = sp.getString("name_style", "plain")!!.let { v -> if (app.belle.assistant.ui.NameArt.STYLES.any { it.first == v }) v else "plain" }
        set(v) = sp.edit().putString("name_style", v).save()
    /** Name font: a built-in family id or "file:<name>.ttf" for an imported font. Per profile. */
    var nameFont: String
        get() = sp.getString("name_font", "sans")!!
        set(v) = sp.edit().putString("name_font", v).save()
    var shader3d: app.belle.assistant.core.Shader3D
        get() = app.belle.assistant.core.Shader3D.of(sp.getString("shader3d", "none"))
        set(v) = sp.edit().putString("shader3d", v.id).save()
    var shader3dIntensity: Float
        get() = sp.getFloat("shader3d_intensity", 0.8f)
        set(v) = sp.edit().putFloat("shader3d_intensity", v.coerceIn(0f, 1f)).save()
    /** Retrostation: draw without a depth buffer (painter's order) for the PS1 polygon wobble. */
    var psxNoDepth: Boolean
        get() = sp.getBoolean("psx_no_depth", false)
        set(v) = sp.edit().putBoolean("psx_no_depth", v).save()
    /** 3D animation strength (rig v6): 1 Full motion, 0.5 Balanced, 0 No stretching. */
    var animStrength: Float
        get() = sp.getFloat("anim_strength", 1f)
        set(v) = sp.edit().putFloat("anim_strength", v.coerceIn(0f, 1f)).save()
    var n64Fog: Boolean
        get() = sp.getBoolean("n64_fog", true)
        set(v) = sp.edit().putBoolean("n64_fog", v).save()
    /** Detail slider 0..1; -1 = automatic for this phone. Per-surface override: -1 = same as global. */
    var detail3d: Float
        get() = sp.getFloat("detail3d", -1f)
        set(v) = sp.edit().putFloat("detail3d", v).save()
    fun detailOverride(surface: String) = sp.getFloat("detail3d_$surface", -1f)
    fun setDetailOverride(surface: String, v: Float) = sp.edit().putFloat("detail3d_$surface", v).save()
    fun detailFor(surface: String): Float {
        detailOverride(surface).takeIf { it >= 0 }?.let { return it }
        val g = detail3d
        val auto = app.belle.assistant.core.Detail3D.auto(app.belle.assistant.models.Downloads.totalRamGb(ctx),
            (ctx.getSystemService(android.app.ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion) >= 0x30002, surface)
        if (g < 0) return auto
        // the global slider scales each surface's automatic share (the widget always stays cheaper than Home)
        val autoHome = app.belle.assistant.core.Detail3D.auto(app.belle.assistant.models.Downloads.totalRamGb(ctx), true, "home")
        return (g * auto / autoHome).coerceIn(0f, 1f)
    }
    /** Show the 3D avatar (when the profile has one) on Home and in the bubble. */
    var show3d: Boolean
        get() = sp.getBoolean("show3d", true)
        set(v) = sp.edit().putBoolean("show3d", v).save()
    /** Auto-rig style for models without their own skeleton: "skinned" or "rigid" (N64-style parts). */
    var rigStyle3d: String
        get() = sp.getString("rig_style3d", "skinned")!!
        set(v) = sp.edit().putString("rig_style3d", v).save()

    var avatarTapEnds: Boolean
        get() = sp.getBoolean("avatar_tap_ends", true)
        set(v) = sp.edit().putBoolean("avatar_tap_ends", v).save()
    var edgeGlow: Boolean
        get() = sp.getBoolean("edge_glow", true)
        set(v) = sp.edit().putBoolean("edge_glow", v).save()
    var glowColor: Int
        get() = sp.getInt("glow_color", 0xFF4FC3F7.toInt())
        set(v) = sp.edit().putInt("glow_color", v).save()

    // ----- sleep words -----
    /** Any number of phrases that end the conversation immediately ("goodnight belle", "that's all"). */
    var sleepPhrases: List<String>
        get() = sp.getString("sleep_phrases", null)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: defaultSleepPhrases(assistantName)
        set(v) = sp.edit().putString("sleep_phrases", v.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).save()
    /** "voice" = short spoken goodbye, "chime" = sleep chime only, "both". */
    var sleepConfirm: String
        get() = sp.getString("sleep_confirm", "voice")!!
        set(v) = sp.edit().putString("sleep_confirm", v).save()

    // ----- conversation -----
    /** Stop listening if nothing is said for this long after the wake word or a reply. */
    var listenTimeoutOn: Boolean
        get() = sp.getBoolean("listen_timeout_on", true)
        set(v) = sp.edit().putBoolean("listen_timeout_on", v).save()
    var listenTimeoutSec: Int
        get() = sp.getInt("listen_timeout", 8)
        set(v) = sp.edit().putInt("listen_timeout", v.coerceIn(2, 3600)).save()
    /** "s" or "m": only how the timeout is shown/edited. */
    var listenTimeoutUnit: String
        get() = sp.getString("listen_timeout_unit", "s")!!
        set(v) = sp.edit().putString("listen_timeout_unit", v).save()
    /** Keep listening after every reply until a sleep word or the timeout. */
    var continuous: Boolean
        get() = sp.getBoolean("continuous", true)
        set(v) = sp.edit().putBoolean("continuous", v).save()

    // ----- internet (off by default; every request checks this) -----
    var internetEnabled: Boolean
        get() = sp.getBoolean("internet", false)
        set(v) = sp.edit().putBoolean("internet", v).save()
    /** Search providers in the order tried; first = default. */
    var searchOrder: List<String>
        get() = sp.getString("search_order", null)?.split(",")?.filter { it.isNotBlank() }
            ?: app.belle.assistant.core.SearchProvider.DEFAULT_ORDER.map { it.id }
        set(v) = sp.edit().putString("search_order", v.joinToString(",")).save()
    /** Providers the user switched off (kept in the order list so they can be re-enabled). */
    var searchDisabled: Set<String>
        get() = sp.getStringSet("search_disabled", emptySet())!!
        set(v) = sp.edit().putStringSet("search_disabled", v).save()
    /** "headless" = hidden in-app WebView reads the results page; "frontend" = also opens it in your browser. */
    var browsingMode: String
        get() = sp.getString("browsing_mode", "headless")!!
        set(v) = sp.edit().putString("browsing_mode", v).save()
    /** Frontend mode: also read the browser tab's text with Accessibility (lets me tap/scroll/read the screen), if it's on. */
    var frontendScreenRead: Boolean
        get() = sp.getBoolean("frontend_a11y", false)
        set(v) = sp.edit().putBoolean("frontend_a11y", v).save()
    var searxUrl: String
        get() = sp.getString("searx_url", "")!!
        set(v) = sp.edit().putString("searx_url", v.trim()).save()
    var pagesToRead: Int
        get() = sp.getInt("pages_to_read", 3)
        set(v) = sp.edit().putInt("pages_to_read", v.coerceIn(1, 5)).save()
    /** "Name|https://feed.url" per entry. */
    var newsFeeds: List<String>
        get() = sp.getString("news_feeds", null)?.split("\n")?.filter { it.isNotBlank() } ?: app.belle.assistant.core.Rss.DEFAULT_FEEDS
        set(v) = sp.edit().putString("news_feeds", v.filter { it.isNotBlank() }.joinToString("\n")).save()

    /** Hold a partial wake lock while listening so the wake word keeps working with the screen off. */
    var keepCpuAwake: Boolean
        get() = sp.getBoolean("keep_cpu_awake", true)
        set(v) = sp.edit().putBoolean("keep_cpu_awake", v).save()
    /** Piper voice quality (Settings › Voice › Quality): expressiveness and rhythm variation; Piper's own defaults. */
    var ttsNoise: Float
        get() = sp.getFloat("tts_noise", 0.667f)
        set(v) = sp.edit().putFloat("tts_noise", v.coerceIn(0f, 1.2f)).save()
    var ttsNoiseW: Float
        get() = sp.getFloat("tts_noise_w", 0.8f)
        set(v) = sp.edit().putFloat("tts_noise_w", v.coerceIn(0f, 1.2f)).save()
    /** Phrase used for live voice previews. */
    var previewPhrase: String
        get() = sp.getString("preview_phrase", "Hey there, sugar. How does this sound?")!!
        set(v) = sp.edit().putString("preview_phrase", v.take(200)).save()
    var widgetHintDone: Boolean
        get() = sp.getBoolean("widget_hint_done", false)
        set(v) = sp.edit().putBoolean("widget_hint_done", v).save()
    /** Long-term conversation memory for this assistant (on by default; stored only on this phone). */
    var memoryEnabled: Boolean
        get() = sp.getBoolean("memory_on", true)
        set(v) = sp.edit().putBoolean("memory_on", v).save()
    /** Sound slot override: "" = built-in chime, "ring:<uri>" = a phone sound, "file:<name>" = imported into app storage. */
    fun sound(slot: String): String = sp.getString("sound_$slot", "")!!
    fun setSound(slot: String, v: String) = sp.edit().putString("sound_$slot", v).save()
    // ----- system prompt (per assistant, editable in four sections) -----
    /** Effective text of one section ("personality", "style", "address", "extra"): what the user typed, or the default
     *  for this assistant's name when never edited (so renames, new assistants and imports get sensible text). */
    fun promptSection(key: String): String {
        migrateLegacy()
        val d = PersonaPrompt.defaults(assistantName)
        return sp.getString("p_$key", null) ?: when (key) {
            "personality" -> d.personality; "style" -> d.style; "address" -> d.address; else -> d.extra
        }
    }
    fun promptSectionEdited(key: String): Boolean = sp.contains("p_$key")
    fun setPromptSection(key: String, v: String) = sp.edit().putString("p_$key", v.take(PersonaPrompt.MAX_SECTION)).save()
    fun resetPromptSection(key: String) = sp.edit().remove("p_$key").save()
    fun resetPrompt() { val e = sp.edit(); PersonaPrompt.SECTIONS.forEach { e.remove("p_$it") }; e.save() }
    val persona: PersonaPrompt
        get() = PersonaPrompt(promptSection("personality"), promptSection("style"), promptSection("address"), promptSection("extra"))

    /** Old settings: the single "personality" text becomes the personality section (kept exactly), and the removed
     *  "Mature topics" switch is dropped — every assistant is uncensored now. Runs once per profile, also for imports. */
    private fun migrateLegacy() {
        val p = sp
        if (!p.contains("mature") && !p.contains("personality")) return
        val e = p.edit()
        val old = p.getString("personality", "")!!.trim()
        if (old.isNotEmpty() && !p.contains("p_personality")) e.putString("p_personality", old.take(PersonaPrompt.MAX_SECTION))
        e.remove("personality").remove("mature").save()
    }

    // ----- appearance -----
    /** "system", "light" or "dark". */
    var theme: String
        get() = sp.getString("theme", "dark")!!
        set(v) = sp.edit().putString("theme", v).save()
    /** Android 12+: take colours from the wallpaper instead of the app's checked palette. */
    var wallpaperColors: Boolean
        get() = sp.getBoolean("wallpaper_colors", false)
        set(v) = sp.edit().putBoolean("wallpaper_colors", v).save()
    var largeText: Boolean
        get() = sp.getBoolean("large_text", false)
        set(v) = sp.edit().putBoolean("large_text", v).save()

    companion object {
        /** The default assistant: the dragon in the suit. */
        const val DEFAULT_NAME = "Bob From Accounting"
        fun defaultSleepPhrases(name: String): List<String> {
            val n = app.belle.assistant.core.WakeWord.callName(name)
            val full = name.trim().lowercase().ifEmpty { "bob" }
            return (listOf("goodnight $n", "good night $n", "thanks $n that's all") + (if (full != n) listOf("goodnight $full") else emptyList()) +
                listOf("go to sleep", "that's all", "that is all", "goodbye", "stop listening", "never mind")).distinct()
        }
    }
}
