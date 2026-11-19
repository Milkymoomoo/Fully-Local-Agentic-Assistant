package app.belle.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.belle.assistant.service.AssistantRuntime
import app.belle.assistant.service.AssistantService

/**
 * DEBUG BUILDS ONLY. Protected by android.permission.DUMP, which only adb shell
 * holds, so other apps cannot use it. Used by scripts/emulator_test.sh.
 *   am broadcast -n app.belle.assistant/.DebugReceiver -a TEXT --es text "set a timer for 5 minutes"
 *   am broadcast -n app.belle.assistant/.DebugReceiver -a INJECT --es path /data/local/tmp/x.wav
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AssistantRuntime.init(ctx)
        when (intent.action) {
            "TEXT" -> AssistantRuntime.handleText(ctx, intent.getStringExtra("text").orEmpty(), speak = intent.getBooleanExtra("speak", true))
            "INJECT" -> {
                val src = java.io.File(intent.getStringExtra("path").orEmpty())
                val dst = java.io.File(ctx.cacheDir, "inject.wav")
                src.copyTo(dst, overwrite = true)
                app.belle.assistant.speech.AudioOut.dumpDir?.let { d -> d.mkdirs(); src.copyTo(java.io.File(d, "${System.currentTimeMillis()}_mic.wav"), overwrite = true) }
                AssistantRuntime.injectWav(dst.path)
            }
            "START" -> AssistantService.start(ctx)
            "LLM" -> { Prefs(ctx).llmFile = intent.getStringExtra("file").orEmpty(); AssistantRuntime.reloadLlm(ctx) }
            "VOICE" -> { Prefs(ctx).voiceDir = intent.getStringExtra("dir").orEmpty(); AssistantRuntime.load(ctx, reloadLlm = false) }
            "NAME" -> { Prefs(ctx).assistantName = intent.getStringExtra("name").orEmpty(); AssistantRuntime.load(ctx, reloadLlm = false) }
            "END" -> AssistantRuntime.endSession(ctx, "debug")
            "LISTEN" -> AssistantRuntime.listenNow(ctx)
            "SLEEPWORDS" -> Prefs(ctx).sleepPhrases = intent.getStringExtra("list").orEmpty().split(",")
            "WAKEWORDS" -> { Prefs(ctx).wakePhrases = intent.getStringExtra("list").orEmpty().split(","); AssistantRuntime.reloadWakeWords(ctx) }
            "PREF" -> {
                val p = Prefs(ctx)
                intent.getStringExtra("internet")?.let { p.internetEnabled = it == "1" }
                intent.getStringExtra("ondemand")?.let { p.loadOnDemand = it == "1" }
                intent.getStringExtra("continuous")?.let { p.continuous = it == "1" }
                intent.getStringExtra("timeout")?.let { p.listenTimeoutSec = it.toInt() }
                intent.getStringExtra("timeouton")?.let { p.listenTimeoutOn = it == "1" }
                intent.getStringExtra("show3d")?.let { p.show3d = it == "1"; app.belle.assistant.ui.AssistantWidget.updateAll(ctx) }
                intent.getStringExtra("search")?.let { p.searchOrder = it.split(",") }
                intent.getStringExtra("theme")?.let { p.theme = it }
                intent.getStringExtra("voicevol")?.let { p.voiceVolume = it.toFloat() }
            }
            // PROFILE --es create Dixie --es wake "hey dixie,hey belle" | --es active Dixie | --es multi 1 | --es priority "Dixie,Belle" | --es reset 1
            "PROFILE" -> {
                fun idOf(n: String) = ProfileStore.ids(ctx).firstOrNull { ProfileStore.name(ctx, it).equals(n, true) } ?: ProfileStore.ids(ctx).firstOrNull { ProfileStore.name(ctx, it).startsWith(n, true) }
                if (intent.getStringExtra("reset") == "1") { ProfileStore.ids(ctx).filter { it != ProfileStore.DEFAULT_ID }.forEach { ProfileStore.delete(ctx, it) }; ProfileStore.setActive(ctx, ProfileStore.DEFAULT_ID); ProfileStore.setMultiWake(ctx, false) }
                intent.getStringExtra("create")?.let { n ->
                    val id = idOf(n) ?: ProfileStore.create(ctx, n, copyFrom = ProfileStore.DEFAULT_ID)
                    intent.getStringExtra("wake")?.let { Prefs(ctx, id).wakePhrases = it.split(",") }
                    val pp = Prefs(ctx, id)
                    intent.getStringExtra("voice")?.let { pp.voiceDir = it }
                    intent.getStringExtra("style")?.let { pp.nameStyle = it }
                    intent.getStringExtra("font")?.let { pp.nameFont = it }
                    // --es avatar <readable file>: copy in as this profile's own picture
                    intent.getStringExtra("avatar")?.let { src -> val f = java.io.File(ctx.filesDir, "avatar_${System.currentTimeMillis()}.${src.substringAfterLast('.')}")
                        java.io.File(src).copyTo(f, overwrite = true); pp.avatarFile = f.name }
                }
                intent.getStringExtra("active")?.let { n -> idOf(n)?.let { ProfileStore.setActive(ctx, it) } }
                intent.getStringExtra("multi")?.let { ProfileStore.setMultiWake(ctx, it == "1") }
                intent.getStringExtra("priority")?.let { l -> ProfileStore.setPriority(ctx, l.split(",").mapNotNull { idOf(it) }) }
                AssistantRuntime.reloadWakeWords(ctx)
                app.belle.assistant.ui.AssistantWidget.updateAll(ctx)
                AssistantRuntime.event("profiles: active=${ProfileStore.name(ctx, ProfileStore.activeId(ctx))} multi=${ProfileStore.multiWake(ctx)} " +
                    ProfileStore.ids(ctx).joinToString { ProfileStore.name(ctx, it) + Prefs(ctx, it).wakePhrases })
            }
            // CUTOUT [--es file <image>]: time the matting cut-out of a picture (default: the built-in avatar)
            "CUTOUT" -> Thread {
                val b = intent.getStringExtra("file")?.let { android.graphics.BitmapFactory.decodeFile(it) }
                    ?: android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.avatar_default)
                app.belle.assistant.avatar.Cutout.init(ctx)
                val t0 = System.currentTimeMillis(); val m = app.belle.assistant.avatar.Cutout.matte(b, intent.getIntExtra("size", 0))
                // --es out <png>: the picture with the matte applied as alpha, for checking by eye
                if (m != null) intent.getStringExtra("out")?.let { o -> val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                    for (i in px.indices) px[i] = (px[i] and 0xFFFFFF) or ((m[i] * 255).toInt().coerceIn(0, 255) shl 24)
                    java.io.File(o).outputStream().use { android.graphics.Bitmap.createBitmap(px, b.width, b.height, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                AssistantRuntime.event("cutout: matte ${b.width}x${b.height} model=${app.belle.assistant.avatar.Cutout.lastModel} ok=${m != null} cover=${m?.count { it > 0.5f }?.toFloat()?.div(m.size)} in ${System.currentTimeMillis() - t0} ms")
            }.start()
            "STATE" -> AssistantRuntime.event("state: mode=${AssistantRuntime.mode} ui=${AssistantRuntime.ui} voiceVolume=${Prefs(ctx).voiceVolume} lastPlayedAt=${app.belle.assistant.speech.AudioOut.lastVolume} av1=${app.belle.assistant.avatar.Av1.version()} ${AssistantRuntime.loadState()}")
            "SAY" -> Thread { AssistantRuntime.say(intent.getStringExtra("text").orEmpty()) }.start()
            // AUDIODUMP --es on 1 : save every played clip (and injected mic audio) to <external files>/audio/<ms>.wav
            // WIDGETRENDER --ei w 250 --ei h 180 --es out <png>: the full widget at that size, through the real RemoteViews
            "WIDGETRENDER" -> app.belle.assistant.ui.AssistantWidget.Companion.Animator.renderAt(ctx, intent.getIntExtra("w", 250), intent.getIntExtra("h", 180),
                java.io.File(intent.getStringExtra("out") ?: java.io.File(ctx.getExternalFilesDir(null), "widget.png").path))
            // BG --es file <readable path> | --es mode own   [--es sound 1 --es vol 0.4] [--es users 1]: set the active
            // profile's background exactly like the in-app import, decode it, and report what came out
            "BG" -> Thread {
                val p = Prefs(ctx)
                intent.getStringExtra("file")?.let { src -> val sf = java.io.File(src)
                    val d = java.io.File(ctx.filesDir, app.belle.assistant.avatar.Backgrounds.DIR).apply { mkdirs() }
                    val f = java.io.File(d, "bg_${System.currentTimeMillis()}.${sf.extension.lowercase()}"); sf.copyTo(f, overwrite = true)
                    val t0 = System.currentTimeMillis()
                    val r = runCatching { app.belle.assistant.avatar.Backgrounds.load(f) }
                    r.onSuccess { c -> p.bgFile = f.name; p.bgMode = "custom"
                        AssistantRuntime.event("bg: ${sf.name} OK frames=${c.frames.size} size=${c.frames[0].width}x${c.frames[0].height} animated=${c.animated} in ${System.currentTimeMillis() - t0} ms") }
                     .onFailure { AssistantRuntime.event("bg: ${sf.name} FAILED $it"); f.delete() }
                }
                intent.getStringExtra("mode")?.let { p.bgMode = it }
                intent.getStringExtra("sound")?.let { p.bgSound = it == "1" }
                intent.getStringExtra("vol")?.let { p.bgVolume = it.toFloat() }
                intent.getStringExtra("users")?.let { app.belle.assistant.avatar.Backgrounds.soundUser(ctx, it == "1") }
                app.belle.assistant.avatar.Backgrounds.updateSound(ctx)
                AssistantRuntime.event("bg: mode=${p.bgMode} file=${p.bgFile} sound=${p.bgSound} vol=${p.bgVolume} playing=${app.belle.assistant.avatar.Backgrounds.soundPlaying} playerVol=${app.belle.assistant.avatar.Backgrounds.soundVolume}")
                app.belle.assistant.ui.AssistantWidget.updateAll(ctx)
            }.start()
            // FONT --es path <.ttf/.otf> [--es apply 1] | STYLE --es style gold --es font serif : name styling for the active profile
            "FONT" -> { val f = java.io.File(intent.getStringExtra("path").orEmpty())
                val r = runCatching { f.inputStream().use { app.belle.assistant.ui.NameArt.importFont(ctx, f.name, it) }.getOrThrow() }
                r.onSuccess { id -> if (intent.getStringExtra("apply") == "1") Prefs(ctx).nameFont = id }
                AssistantRuntime.event("font: ${f.name} -> " + r.fold({ "OK $it (fonts: ${app.belle.assistant.ui.NameArt.importedFonts(ctx).map { it.first }})" }, { "FAILED ${it.message}" }))
                app.belle.assistant.ui.AssistantWidget.updateAll(ctx) }
            "STYLE" -> { val p = Prefs(ctx); intent.getStringExtra("style")?.let { p.nameStyle = it }; intent.getStringExtra("font")?.let { p.nameFont = it }
                AssistantRuntime.event("style: ${p.assistantName} style=${p.nameStyle} font=${p.nameFont}"); app.belle.assistant.ui.AssistantWidget.updateAll(ctx) }
            "FRAMEDUMP" -> app.belle.assistant.ui.AssistantWidget.Companion.Animator.frameDumpDir = if (intent.getStringExtra("on") == "1") java.io.File(ctx.getExternalFilesDir(null), "frames") else null
            "AUDIODUMP" -> app.belle.assistant.speech.AudioOut.dumpDir = if (intent.getStringExtra("on") == "1") java.io.File(ctx.getExternalFilesDir(null), "audio") else null
            // IMPORT3D --es path <file in the app's external files dir> [--es rig rigid]
            "IMPORT3D" -> Thread {
                val f = java.io.File(intent.getStringExtra("path").orEmpty())
                val id = ProfileStore.activeId(ctx)
                val r = runCatching { app.belle.assistant.avatar.Avatar3DFiles.import(ctx, id, mapOf(f.name.lowercase() to f.readBytes()), intent.getStringExtra("rig") ?: Prefs(ctx, id).rigStyle3d) }
                AssistantRuntime.event("import3d: " + r.fold({ it }, { "FAILED " + it }))
                app.belle.assistant.avatar.Avatar3DFiles.info(ctx, id)?.let { AssistantRuntime.event("import3d-info: " + it.toString().replace("\n", " ")) }
                app.belle.assistant.ui.AssistantWidget.updateAll(ctx)
            }.start()
            // SET3D --es shader retro64 --es intensity 0.8 --es detail 0.3 --es show3d 1 --es widget 3d --es nodepth 0 --es fog 1 --es home 0.9
            "SET3D" -> {
                val p = Prefs(ctx)
                intent.getStringExtra("shader")?.let { p.shader3d = app.belle.assistant.core.Shader3D.of(it) }
                intent.getStringExtra("intensity")?.let { p.shader3dIntensity = it.toFloat() }
                intent.getStringExtra("detail")?.let { p.detail3d = it.toFloat() }
                for (sf in listOf("home", "bubble", "widget")) intent.getStringExtra(sf)?.let { p.setDetailOverride(sf, it.toFloat()) }
                intent.getStringExtra("show3d")?.let { p.show3d = it == "1" }
                intent.getStringExtra("nodepth")?.let { p.psxNoDepth = it == "1" }
                intent.getStringExtra("fog")?.let { p.n64Fog = it == "1" }
                intent.getStringExtra("widget")?.let { m -> val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
                    val e = ctx.getSharedPreferences("belle_widgets", Context.MODE_PRIVATE).edit()
                    mgr.getAppWidgetIds(android.content.ComponentName(ctx, app.belle.assistant.ui.AssistantWidget::class.java)).forEach { e.putString("mode_$it", m) }; e.apply() }
                app.belle.assistant.ui.FramingRev.rev++
                app.belle.assistant.ui.AssistantWidget.updateAll(ctx)
                AssistantRuntime.event("set3d: shader=${p.shader3d.id} intensity=${p.shader3dIntensity} detail=${p.detail3d} show3d=${p.show3d}")
            }
            // RENDER3D --es levels 1,0.5,0.2,0 --es shaders none,retrostation,retro64 --ei size 512 --ei frames 30 : PNGs + timing JSON
            "RENDER3D" -> Thread {
                val p = Prefs(ctx); val out = java.io.File(ctx.getExternalFilesDir(null), "render3d").apply { mkdirs() }
                app.belle.assistant.avatar.Avatar3DRenderer.debugNoSkin = intent.getStringExtra("noskin") == "1"
                app.belle.assistant.avatar.Avatar3DRenderer.debugLod = intent.getStringExtra("lod")?.toFloat() ?: -1f
                AssistantRuntime.event("render3d: maxBones=${app.belle.assistant.avatar.Avatar3DRenderer.maxBones} noskin=${app.belle.assistant.avatar.Avatar3DRenderer.debugNoSkin} mesh=${app.belle.assistant.avatar.Avatar3DFiles.mesh(ctx, p.id)}")
                val size = intent.getIntExtra("size", 512); val frames = intent.getIntExtra("frames", 30)
                val levels = (intent.getStringExtra("levels") ?: "1,0.6,0.3,0.1,0").split(",").map { it.toFloat() }
                val shaders = (intent.getStringExtra("shaders") ?: "none").split(",").map { app.belle.assistant.core.Shader3D.of(it) }
                val results = org.json.JSONArray()
                for (sh in shaders) for (lv in levels) {
                    val req = { t: Float, st: Int, lvl: Float -> app.belle.assistant.avatar.Avatar3DRenderer.Request(app.belle.assistant.avatar.Avatar3DFiles.mesh(ctx, p.id), "home", size, size, p.framing("home"), sh, p.shader3dIntensity, p.psxNoDepth, p.n64Fog, lv, t, st, lvl) }
                    val first = app.belle.assistant.avatar.Avatar3DRenderer.renderBlocking(req(0f, 0, 0f), 120_000) ?: continue
                    first.bitmap.let { b -> var opaque = 0; for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) if ((b.getPixel(x, y) ushr 24) > 0) opaque++
                        AssistantRuntime.event("render3d: ${sh.id} ${lv} sampled-opaque=$opaque/${(b.width / 4 + 1) * (b.height / 4 + 1)}"); java.io.File(out, "${sh.id}_${(lv * 100).toInt()}.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    val pid = android.os.Process.myPid(); fun cpu() = java.io.File("/proc/$pid/stat").readText().substringAfterLast(") ").split(" ").let { it[11].toLong() + it[12].toLong() }
                    val c0 = cpu(); val t0 = android.os.SystemClock.elapsedRealtime(); var sumF = 0f; var sumC = 0f; var sumG = 0f; var last = first.stats
                    for (i in 0 until frames) { val f = app.belle.assistant.avatar.Avatar3DRenderer.renderBlocking(req(i / 15f, 3, (i % 6) / 6f), 60_000) ?: break; sumF += f.stats.frameMs; sumC += f.stats.cpuMs; sumG += f.stats.gpuMs; last = f.stats }
                    val wall = (android.os.SystemClock.elapsedRealtime() - t0).coerceAtLeast(1); val ticks = cpu() - c0
                    val o = org.json.JSONObject().put("shader", sh.id).put("detail", lv).put("triangles", last.triangles).put("vertices", last.vertices)
                        .put("renderW", last.renderW).put("renderH", last.renderH).put("mip", last.mip).put("frameMs", sumF / frames).put("renderThreadCpuMs", sumC / frames)
                        .put("drawToReadbackMs", sumG / frames).put("processCpuPercent", ticks * 10.0 * 100 / wall).put("fps", app.belle.assistant.core.Detail3D(lv, "home").fps(true))
                    results.put(o); AssistantRuntime.event("render3d: $o")
                }
                java.io.File(out, "bench.json").writeText(results.toString(2)); AssistantRuntime.event("render3d: done")
            }.start()
            // PORT3D --es stage birefnet|dinov3 --es model <onnx> --es image <png> [--ei size 512]: runs one on-phone
            // 3D stage, writes port3d/<stage>.json (time + peak memory) and, for birefnet, the mask PNG.
            // P3DGEN --es image <png> [--es profile <id>]: the whole on-phone photo -> 3D avatar run (Pixal3DJob)
            "P3DGEN" -> Thread {
                val img = android.graphics.BitmapFactory.decodeFile(intent.getStringExtra("image"))
                val r = runCatching { app.belle.assistant.threed.Pixal3DJob.run(ctx, intent.getStringExtra("profile") ?: ProfileStore.activeId(ctx), img,
                    steps = intent.getIntExtra("steps", 0), import = intent.getStringExtra("import") != "0") }
                AssistantRuntime.event("p3dgen: " + r.fold({ it }, { "FAILED " + it.stackTraceToString().take(600) }))
            }.start()
            "PORT3D" -> Thread {
                val out = java.io.File(ctx.getExternalFilesDir(null), "port3d").apply { mkdirs() }
                val stage = intent.getStringExtra("stage") ?: "birefnet"; val size = intent.getIntExtra("size", 512)
                val res = org.json.JSONObject().put("stage", stage).put("size", size).put("rss_before_mb", app.belle.assistant.threed.Port3D.rssMb())
                try {
                    val img = intent.getStringExtra("image")?.let { android.graphics.BitmapFactory.decodeFile(it) }
                    val model = java.io.File(intent.getStringExtra("model")!!)
                    val stats = when (stage) {
                        "birefnet" -> app.belle.assistant.threed.Port3D.birefnet(model, img!!, size).let { (m, st) ->
                            java.io.File(out, "birefnet_mask.png").outputStream().use { app.belle.assistant.threed.Port3D.maskBitmap(m, size).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; st }
                        "dinov3" -> app.belle.assistant.threed.Port3D.dinov3(model, img!!, size).let { (t, st) -> res.put("shape", t.shape.joinToString("x")).put("mean", t.data.average()); st }
                        // cond: --es crop <png already cropped> | --es image <png> (matted with the app's cutout model)
                        //   [--ei cs 1024] [--es dino 512,1024] [--es fov 46.8] [--es coords <f32 xyz file> --ei res 32]
                        //   writes port3d/cond_*.f32 for comparison with the box run of the same ONNX files
                        "cond" -> {
                            val p3 = app.belle.assistant.threed.Pixal3D(model.parentFile!!)
                            fun dump(n: String, a: FloatArray) = java.io.DataOutputStream(java.io.File(out, "cond_$n.f32").outputStream().buffered()).use { o ->
                                val bb = java.nio.ByteBuffer.allocate(a.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN); bb.asFloatBuffer().put(a); o.write(bb.array()) }
                            val sizes = (intent.getStringExtra("dino") ?: "512,1024").split(",").map { it.trim().toInt() }.toIntArray()
                            val crop = intent.getStringExtra("crop")?.let { p3.imgOf(android.graphics.BitmapFactory.decodeFile(it)) } ?: run {
                                app.belle.assistant.avatar.Cutout.init(ctx)
                                val m = app.belle.assistant.avatar.Cutout.matte(img!!) ?: error("no matting model")
                                p3.crop(img!!, m, intent.getIntExtra("cs", 1024)) }
                            val c = p3.condition(crop, (intent.getStringExtra("fov") ?: "46.82").toDouble(), sizes = sizes)
                            dump("g512", c.d512.global); dump("p512", c.d512.patches); c.d1024?.let { dump("g1024", it.global); dump("p1024", it.patches) }
                            dump("ss", p3.ssProj(c))
                            intent.getStringExtra("coords")?.let { f ->
                                val bb = java.nio.ByteBuffer.wrap(java.io.File(f).readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                val co = IntArray(bb.remaining()) { bb.get().toInt() }; val r = intent.getIntExtra("res", 32)
                                dump("shape_512", p3.sparseProj(c, "shape_512", co, r)); if (c.d1024 != null) dump("shape_1024", p3.sparseProj(c, "shape_1024", co, r)) }
                            p3.stats
                        }
                        else -> error("unknown stage $stage")
                    }
                    res.put("stats", org.json.JSONArray(stats.map { it.json() })).put("ok", true)
                } catch (e: Throwable) { res.put("ok", false).put("error", e.toString()) }
                java.io.File(out, "$stage.json").writeText(res.toString(1))
                AssistantRuntime.event("port3d: $res")
            }.start()
            // PINWIDGET : ask the launcher to add the widget (a system "Add" dialog appears)
            "PINWIDGET" -> android.appwidget.AppWidgetManager.getInstance(ctx).requestPinAppWidget(android.content.ComponentName(ctx, app.belle.assistant.ui.AssistantWidget::class.java), null, null)
        }
    }
}
