package app.belle.assistant.core

/**
 * One sampling stage of a ComfyUI TRELLIS 2 / Pixal3D workflow: the KSampler's widgets plus the model patches wired
 * in front of it (ModelSamplingSD3 shift, RescaleCFG multiplier, CFGOverride start), found by walking its "model" input.
 */
data class Stage3D(
    val name: String, val steps: Int, val cfg: Double, val sampler: String, val scheduler: String, val seed: Long, val randomSeed: Boolean,
    /** ModelSamplingSD3 shift; null = the model's own (3). */
    val shift: Double? = null,
    /** RescaleCFG multiplier; null = none in front of this sampler. */
    val rescale: Double? = null,
    /** CFGOverride start_percent (cfg 1 from there on); null = none. */
    val overrideStart: Double? = null,
    val denoise: Double = 1.0,
)

/** Everything a ComfyUI TRELLIS 2 / Pixal3D workflow sets that a photo → 3D run can use. */
data class Workflow3DSettings(
    val engine: String,                 // what the workflow's switch selects; the phone always runs "pixal3d"
    val removeBackground: Boolean,      // false = use the picture's own alpha (switch node)
    val cropSize: Int, val cropPadding: Double,
    val stages: List<Stage3D>,
    val structureRes: Int, val upsampleRes: Int,
    val remeshRes: Int, val decimateTarget: Int, val unwrapRes: Int, val textureSize: Int,
    val normalMapSize: Int, val aoSize: Int, val pixal3dFov: Double,
    val notes: List<String>,
) {
    fun stage(name: String) = stages.firstOrNull { it.name == name }

    /**
     * Your per-value edits on top of the workflow, as a small JSON object, e.g.
     * {"stages":{"Shape":{"steps":8,"cfg":5.0,"seed":42,"random":false,"scheduler":"simple"}},"cropSize":768,
     *  "decimateTarget":100000,"removeBackground":true}. Out-of-range values are ignored.
     */
    fun withOverrides(json: String?): Workflow3DSettings {
        if (json.isNullOrBlank()) return this
        @Suppress("UNCHECKED_CAST") val o = (runCatching { MiniJson.parse(json) }.getOrNull() as? Map<String, Any?>) ?: return this
        @Suppress("UNCHECKED_CAST") val st = (o["stages"] as? Map<String, Map<String, Any?>>).orEmpty()
        fun d(m: Map<String, Any?>?, k: String) = (m?.get(k) as? Double)
        return copy(
            stages = stages.map { s0 -> val m = st[s0.name] ?: return@map s0
                s0.copy(steps = d(m, "steps")?.toInt()?.takeIf { it in 1..100 } ?: s0.steps,
                    cfg = d(m, "cfg")?.takeIf { it in 0.0..30.0 } ?: s0.cfg,
                    seed = d(m, "seed")?.toLong()?.takeIf { it >= 0 } ?: s0.seed, randomSeed = (m["random"] as? Boolean) ?: s0.randomSeed,
                    scheduler = (m["scheduler"] as? String)?.takeIf { it.isNotBlank() } ?: s0.scheduler) },
            cropSize = d(o, "cropSize")?.toInt()?.takeIf { it in 256..4096 } ?: cropSize,
            textureSize = d(o, "textureSize")?.toInt()?.takeIf { it in listOf(512, 1024, 2048, 4096) } ?: textureSize,
            decimateTarget = d(o, "decimateTarget")?.toInt()?.takeIf { it in 5_000..2_000_000 } ?: decimateTarget,
            removeBackground = (o["removeBackground"] as? Boolean) ?: removeBackground,
        )
    }

    companion object {
        /** The official Pixal3D workflow's values (art/trellis2_workflow.json, the developer's saved copy). */
        val OFFICIAL = Workflow3DSettings("pixal3d", false, 1024, 1.1,
            listOf(Stage3D("Structure", 12, 7.5, "euler", "normal", 56, false, 5.0, 0.7, 0.667),
                Stage3D("Shape", 20, 7.5, "euler", "normal", 42, false, null, 0.5, 0.769),
                Stage3D("Shape detail", 12, 7.5, "euler", "simple", 0, true, null, 0.5, null), Stage3D("Texture", 12, 1.0, "euler", "normal", 43, false)),
            32, 1536, 768, 700_000, 4096, 4096, 2048, 1024, 49.13, emptyList())
        /** What the phone runs when no workflow is imported: the official values with 8 + 12 steps (quality-checked
         *  against the 12 + 20 reference: voxel IoU 0.890, mesh chamfer 0.36 % of the bbox) and BiRefNet cut-out. */
        val PHONE_DEFAULT = OFFICIAL.copy(removeBackground = true, decimateTarget = 150_000,
            stages = OFFICIAL.stages.map { when (it.name) { "Structure" -> it.copy(steps = 8); "Shape" -> it.copy(steps = 12); else -> it } })

        @Suppress("UNCHECKED_CAST")
        fun parse(json: String): Workflow3DSettings {
            val root = MiniJson.parse(json) as? Map<String, Any?> ?: error("Not a ComfyUI workflow (not a JSON object)")
            val nodes = (root["nodes"] as? List<Map<String, Any?>>) ?: error("Not a ComfyUI workflow (no nodes; export it with Save, not Save (API))")
            val byId = nodes.associateBy { (it["id"] as Double).toInt() }
            // link id → from node
            val links = (root["links"] as? List<List<Any?>>).orEmpty().associate { l -> (l[0] as Double).toInt() to (l[1] as Double).toInt() }
            fun type(n: Map<String, Any?>) = n["type"] as String
            fun active(n: Map<String, Any?>) = (n["mode"] as? Double ?: 0.0) == 0.0
            fun w(n: Map<String, Any?>) = (n["widgets_values"] as? List<Any?>).orEmpty()
            fun num(n: Map<String, Any?>, i: Int, d: Double) = (w(n).getOrNull(i) as? Double) ?: (w(n).getOrNull(i) as? String)?.toDoubleOrNull() ?: d
            fun inputFrom(n: Map<String, Any?>, name: String): Map<String, Any?>? {
                val inp = (n["inputs"] as? List<Map<String, Any?>>).orEmpty().firstOrNull { it["name"] == name } ?: return null
                val l = (inp["link"] as? Double)?.toInt() ?: return null
                return byId[links[l]]
            }
            fun first(t: String) = nodes.firstOrNull { type(it) == t && active(it) }
            fun all(t: String) = nodes.filter { type(it) == t && active(it) }
            /** The model patches in front of a sampler: follow "model" inputs (through switches' chosen side) up to 12 hops. */
            fun patches(k: Map<String, Any?>): Map<String, Map<String, Any?>> {
                val out = HashMap<String, Map<String, Any?>>(); var n = inputFrom(k, "model"); var hops = 0
                while (n != null && hops++ < 12) {
                    val t = type(n); if (active(n) && t !in out) out[t] = n
                    n = inputFrom(n, "model") ?: if (t == "ComfySwitchNode") inputFrom(n, if (w(n).firstOrNull() == true) "on_true" else "on_false") else null
                }
                return out
            }
            val notes = mutableListOf<String>()

            // Engine switch: PrimitiveBoolean → ComfySwitchNode(on_true = Trellis2Conditioning, on_false = Pixal3DConditioning)
            val condSwitch = nodes.firstOrNull { type(it) == "ComfySwitchNode" && inputFrom(it, "on_true")?.let(::type) == "Trellis2Conditioning" }
            val sw = condSwitch?.let { inputFrom(it, "switch") }?.let { w(it).firstOrNull() as? Boolean } ?: (condSwitch?.let { w(it).firstOrNull() as? Boolean })
            val engine = if (sw == true) "trellis2" else "pixal3d"
            if (sw == true) notes += "Your workflow's switch picks TRELLIS 2; the phone always runs Pixal3D, with this workflow's steps, samplers and seeds."
            val bgSwitch = nodes.firstOrNull { type(it) == "ComfySwitchNode" && inputFrom(it, "on_true")?.let(::type) == "RemoveBackground" }
            val removeBg = bgSwitch?.let { w(it).firstOrNull() as? Boolean } ?: true

            val stages = mutableListOf<Stage3D>()
            for (k in all("KSampler")) {
                val name = when (inputFrom(k, "latent_image")?.let(::type)) {
                    "EmptyTrellis2LatentStructure" -> "Structure"; "Trellis2ShapeStage" -> "Shape"
                    "Trellis2UpsampleStage" -> "Shape detail"; "Trellis2TextureStage" -> "Texture"; else -> continue
                }
                val v = w(k); val p = patches(k)
                stages += Stage3D(name, num(k, 2, 12.0).toInt(), num(k, 3, 7.5), v.getOrNull(4) as? String ?: "euler", v.getOrNull(5) as? String ?: "normal",
                    num(k, 0, 0.0).toLong(), (v.getOrNull(1) as? String) == "randomize",
                    shift = p["ModelSamplingSD3"]?.let { num(it, 0, 3.0) }, rescale = p["RescaleCFG"]?.let { num(it, 0, 0.7) },
                    overrideStart = p["CFGOverride"]?.let { num(it, 1, 1.0) }, denoise = num(k, 6, 1.0))
            }
            if (stages.none { it.name == "Structure" } || stages.none { it.name == "Shape" })
                error("No TRELLIS 2 / Pixal3D structure and shape samplers found (is this the Pixal3D workflow?)")
            val order = listOf("Structure", "Shape", "Shape detail", "Texture")
            stages.sortBy { order.indexOf(it.name) }
            // A PrimitiveInt wired into a widget overrides the widget's own value (e.g. texture 2048 → 4096).
            fun linkedInt(n: Map<String, Any?>?, input: String, d: Int): Int = n?.let { inputFrom(it, input) }?.takeIf { type(it) == "PrimitiveInt" }?.let { num(it, 0, d.toDouble()).toInt() } ?: d
            val unwrap = first("UnwrapMesh"); val bake = first("BakeTextureFromVoxel")
            val unwrapRes = linkedInt(unwrap, "resolution", unwrap?.let { num(it, 1, 2048.0).toInt() } ?: 2048)
            val texSize = linkedInt(bake, "texture_size", bake?.let { num(it, 0, 2048.0).toInt() } ?: 2048)
            val crop = first("ImageCropToMask")
            return Workflow3DSettings(
                engine, removeBg,
                crop?.let { num(it, 0, 1024.0).toInt() } ?: 1024, crop?.let { num(it, 2, 1.1) } ?: 1.1,
                stages,
                first("VaeDecodeStructureTrellis2")?.let { num(it, 0, 32.0).toInt() } ?: 32,
                first("Trellis2UpsampleStage")?.let { num(it, 0, 1536.0).toInt() } ?: 1536,
                first("RemeshMesh")?.let { num(it, 0, 768.0).toInt() } ?: 768,
                first("DecimateMesh")?.let { num(it, 0, 700000.0).toInt() } ?: 700_000,
                unwrapRes, texSize,
                first("BakeNormalMapFromMesh")?.let { num(it, 0, 2048.0).toInt() } ?: 2048,
                first("BakeAmbientOcclusion")?.let { num(it, 0, 1024.0).toInt() } ?: 1024,
                first("Pixal3DConditioning")?.let { num(it, 0, 49.13) } ?: 49.13,
                notes,
            )
        }
    }
}

/** Reads and writes the overrides JSON (see [Workflow3DSettings.withOverrides]). */
object Workflow3DOverrides {
    @Suppress("UNCHECKED_CAST")
    fun read(json: String?): MutableMap<String, Any?> = runCatching { (json?.takeIf { it.isNotBlank() }?.let { MiniJson.parse(it) } as? Map<String, Any?>)?.toMutableMap() }.getOrNull() ?: mutableMapOf()
    @Suppress("UNCHECKED_CAST")
    fun setStage(o: MutableMap<String, Any?>, stage: String, key: String, v: Any?) {
        val st = ((o["stages"] as? Map<String, Any?>)?.toMutableMap()) ?: mutableMapOf()
        val m = ((st[stage] as? Map<String, Any?>)?.toMutableMap()) ?: mutableMapOf()
        if (v == null) m.remove(key) else m[key] = v
        if (m.isEmpty()) st.remove(stage) else st[stage] = m
        if (st.isEmpty()) o.remove("stages") else o["stages"] = st
    }
    @Suppress("UNCHECKED_CAST")
    fun has(o: Map<String, Any?>, stage: String?, key: String): Boolean =
        if (stage == null) o.containsKey(key) else ((o["stages"] as? Map<String, Any?>)?.get(stage) as? Map<String, Any?>)?.containsKey(key) == true
    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean -> v.toString()
        is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && kotlin.math.abs(v.toDouble()) < 1e15) v.toLong().toString() else v.toDouble().toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { write(it.key.toString()) + ":" + write(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> write(v.toString())
    }
}
