package app.belle.assistant.mesh

import app.belle.assistant.core.MiniJson
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One triangle mesh as the avatar renderer uses it. Optional skinning (4 joints + 4 weights per vertex)
 * is carried untouched through the progressive-mesh reordering, so rig weights survive any detail level.
 * [pmParent]/[pmTriDeath] are the progressive-mesh tables (see [ProgressiveMesh]); vertices and triangles are
 * already stored in collapse order, so any detail level is just a prefix plus an index remap.
 */
class Mesh3D(
    val pos: FloatArray, val nrm: FloatArray?, val uv: FloatArray?, val col: FloatArray?,
    val joints: IntArray?, val weights: FloatArray?, val idx: IntArray,
    val texture: ByteArray?, val textureMime: String?,
    val pmParent: IntArray? = null, val pmTriDeath: IntArray? = null,
) {
    var skeleton: Skeleton? = null
    /** Lowest triangle count of the progressive mesh that still looks like the model (see ProgressiveMesh.CLEAN_ERROR); 0 = unknown. */
    var pmCleanTris = 0
    var animations: List<Anim> = emptyList()
    /** Rig v6: the no-stretch weighting (glTF JOINTS_1 / WEIGHTS_1); the renderer mixes it with joints/weights by the
     *  avatar's animation strength. Null = only one weighting. */
    var joints2: IntArray? = null; var weights2: FloatArray? = null
    val vertexCount get() = pos.size / 3
    val triCount get() = idx.size / 3
    val hasPm get() = pmParent != null && pmTriDeath != null

    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) for (k in 0..2) { val v = pos[i * 3 + k]; if (v < b[k]) b[k] = v; if (v > b[k + 3]) b[k + 3] = v }
        return b
    }

    fun withNormals(): Mesh3D {
        if (nrm != null) return this
        val n = FloatArray(pos.size)
        for (t in 0 until triCount) {
            val a = idx[t * 3] * 3; val b = idx[t * 3 + 1] * 3; val c = idx[t * 3 + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (o in intArrayOf(a, b, c)) { n[o] += nx; n[o + 1] += ny; n[o + 2] += nz }
        }
        for (i in 0 until vertexCount) { val l = kotlin.math.sqrt(n[i * 3] * n[i * 3] + n[i * 3 + 1] * n[i * 3 + 1] + n[i * 3 + 2] * n[i * 3 + 2]).coerceAtLeast(1e-12f); n[i * 3] /= l; n[i * 3 + 1] /= l; n[i * 3 + 2] /= l }
        return Mesh3D(pos, n, uv, col, joints, weights, idx, texture, textureMime, pmParent, pmTriDeath).also { it.skeleton = skeleton; it.animations = animations; it.pmCleanTris = pmCleanTris; it.joints2 = joints2; it.weights2 = weights2 }
    }
}

/** Reads any ordinary .glb (all triangle primitives merged, node transforms applied) and writes the app's own
 *  single-mesh .glb (standard glTF 2.0 — opens in any viewer — with the progressive-mesh tables in `extras`). */
object Glb {
    private const val MAGIC = 0x46546C67; private const val JSON = 0x4E4F534A; private const val BIN = 0x004E4942

    @Suppress("UNCHECKED_CAST")
    fun read(bytes: ByteArray): Mesh3D {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.int == MAGIC) { "Not a .glb file" }; bb.int; bb.int
        var json: Map<String, Any?>? = null; var bin: ByteBuffer? = null
        while (bb.remaining() >= 8) {
            val len = bb.int; val type = bb.int; val start = bb.position()
            when (type) {
                JSON -> json = MiniJson.parse(String(bytes, start, len, Charsets.UTF_8)) as Map<String, Any?>
                BIN -> bin = ByteBuffer.wrap(bytes, start, len).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            bb.position(start + len)
        }
        val j = json ?: error("No JSON chunk")
        val ext = (j["extensionsRequired"] as? List<*>).orEmpty()
        require(ext.none { "draco" in it.toString() || "meshopt" in it.toString() }) { "Compressed .glb ($ext) isn't supported; export it uncompressed" }
        fun list(k: String) = (j[k] as? List<Map<String, Any?>>).orEmpty()
        val accessors = list("accessors"); val views = list("bufferViews"); val meshes = list("meshes"); val nodes = list("nodes")
        fun view(i: Int): Triple<Int, Int, Int> { val v = views[i]; return Triple(((v["byteOffset"] as? Double) ?: 0.0).toInt(), (v["byteLength"] as Double).toInt(), ((v["byteStride"] as? Double) ?: 0.0).toInt()) }
        fun acc(i: Int): Pair<FloatArray, Int> {  // returns floats + component count
            val a = accessors[i]; val count = (a["count"] as Double).toInt()
            val comps = when (a["type"]) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> error("type ${a["type"]}") }
            val ct = (a["componentType"] as Double).toInt(); val norm = a["normalized"] == true
            val csz = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val out = FloatArray(count * comps)
            val bvI = (a["bufferView"] as? Double)?.toInt() ?: return out to comps
            val (vo, _, st) = view(bvI); val base = vo + ((a["byteOffset"] as? Double) ?: 0.0).toInt(); val stride = if (st > 0) st else csz * comps
            val b = bin ?: error("No BIN chunk")
            for (e in 0 until count) for (c in 0 until comps) {
                val p = base + e * stride + c * csz
                out[e * comps + c] = when (ct) {
                    5126 -> b.getFloat(p)
                    5121 -> (b.get(p).toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
                    5120 -> b.get(p).toFloat().let { if (norm) maxOf(it / 127f, -1f) else it }
                    5123 -> (b.getShort(p).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
                    5122 -> b.getShort(p).toFloat().let { if (norm) maxOf(it / 32767f, -1f) else it }
                    5125 -> (b.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
                    else -> error("componentType $ct")
                }
            }
            return out to comps
        }
        fun accInt(i: Int): IntArray {
            val a = accessors[i]; val count = (a["count"] as Double).toInt(); val ct = (a["componentType"] as Double).toInt()
            val comps = when (a["type"]) { "SCALAR" -> 1; "VEC4" -> 4; else -> 1 }
            val (vo, _, st) = view((a["bufferView"] as Double).toInt()); val base = vo + ((a["byteOffset"] as? Double) ?: 0.0).toInt()
            val csz = when (ct) { 5121, 5120 -> 1; 5123, 5122 -> 2; else -> 4 }; val stride = if (st > 0) st else csz * comps
            val b = bin!!
            return IntArray(count * comps) { k -> val p = base + (k / comps) * stride + (k % comps) * csz
                when (csz) { 1 -> b.get(p).toInt() and 0xFF; 2 -> b.getShort(p).toInt() and 0xFFFF; else -> b.getInt(p) } }
        }
        // node world transforms
        val world = HashMap<Int, FloatArray>()
        fun local(n: Map<String, Any?>): FloatArray {
            (n["matrix"] as? List<Double>)?.let { m -> return FloatArray(16) { m[it].toFloat() } }
            val t = (n["translation"] as? List<Double>) ?: listOf(0.0, 0.0, 0.0); val r = (n["rotation"] as? List<Double>) ?: listOf(0.0, 0.0, 0.0, 1.0)
            val s = (n["scale"] as? List<Double>) ?: listOf(1.0, 1.0, 1.0)
            val (x, y, z, w) = r.map { it.toFloat() }
            val m = floatArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0f, 2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0f,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0f, t[0].toFloat(), t[1].toFloat(), t[2].toFloat(), 1f)
            for (c in 0..2) for (rr in 0..2) m[c * 4 + rr] *= s[c].toFloat()
            return m
        }
        fun mul(a: FloatArray, b: FloatArray) = FloatArray(16) { i -> val c = i / 4; val r = i % 4; (0..3).fold(0f) { acc, k -> acc + a[k * 4 + r] * b[c * 4 + k] } }
        fun walk(i: Int, parent: FloatArray) { val m = mul(parent, local(nodes[i])); world[i] = m; (nodes[i]["children"] as? List<Double>)?.forEach { walk(it.toInt(), m) } }
        val ident = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
        val scenes = list("scenes"); val sc = scenes.getOrNull(((j["scene"] as? Double) ?: 0.0).toInt())
        ((sc?.get("nodes") as? List<Double>)?.map { it.toInt() } ?: nodes.indices.filter { i -> nodes.none { (it["children"] as? List<Double>)?.contains(i.toDouble()) == true } }).forEach { walk(it, ident) }
        val inst = if (nodes.isEmpty()) meshes.indices.map { it to ident } else world.entries.filter { nodes[it.key]["mesh"] != null }.map { (nodes[it.key]["mesh"] as Double).toInt() to it.value }

        val P = FloatList(); val N = FloatList(); val U = FloatList(); val C = FloatList(); val J = IntList(); val W = FloatList(); val I = IntList()
        var anyN = true; var anyU = true; var anyC = false; var anySkin = true; var texIndex: Int? = null; var pm: Pair<IntArray, IntArray>? = null; var cleanTris = 0; var j2: IntArray? = null; var w2: FloatArray? = null
        val skinNode = nodes.indices.firstOrNull { nodes[it]["skin"] != null && nodes[it]["mesh"] != null }
        val skinned = skinNode != null
        for ((mi, m0) in inst) for (prim in (meshes[mi]["primitives"] as List<Map<String, Any?>>)) {
            val m = if (skinned) ident else m0
            if (((prim["mode"] as? Double) ?: 4.0).toInt() != 4) continue
            val at = prim["attributes"] as Map<String, Any?>; val base = P.size / 3
            val (p, _) = acc((at["POSITION"] as Double).toInt()); val vc = p.size / 3
            for (v in 0 until vc) { val x = p[v * 3]; val y = p[v * 3 + 1]; val z = p[v * 3 + 2]
                P.add(m[0] * x + m[4] * y + m[8] * z + m[12]); P.add(m[1] * x + m[5] * y + m[9] * z + m[13]); P.add(m[2] * x + m[6] * y + m[10] * z + m[14]) }
            (at["NORMAL"] as? Double)?.let { val (n, _) = acc(it.toInt()); for (v in 0 until vc) { val x = n[v * 3]; val y = n[v * 3 + 1]; val z = n[v * 3 + 2]
                N.add(m[0] * x + m[4] * y + m[8] * z); N.add(m[1] * x + m[5] * y + m[9] * z); N.add(m[2] * x + m[6] * y + m[10] * z) } } ?: run { anyN = false }
            (at["TEXCOORD_0"] as? Double)?.let { N.size; val (t, _) = acc(it.toInt()); t.forEach(U::add) } ?: run { anyU = false; repeat(vc * 2) { U.add(0f) } }
            (at["COLOR_0"] as? Double)?.let { val (c, k) = acc(it.toInt()); anyC = true; for (v in 0 until vc) { C.add(c[v * k]); C.add(c[v * k + 1]); C.add(c[v * k + 2]) } } ?: repeat(vc * 3) { C.add(1f) }
            if (at["JOINTS_0"] != null && at["WEIGHTS_0"] != null) { accInt((at["JOINTS_0"] as Double).toInt()).forEach(J::add); acc((at["WEIGHTS_0"] as Double).toInt()).first.forEach(W::add) } else anySkin = false
            if (at["JOINTS_1"] != null && at["WEIGHTS_1"] != null && inst.size == 1 && (meshes[mi]["primitives"] as List<*>).size == 1) { j2 = accInt((at["JOINTS_1"] as Double).toInt()); w2 = acc((at["WEIGHTS_1"] as Double).toInt()).first }
            (prim["indices"] as? Double)?.let { accInt(it.toInt()).forEach { k -> I.add(base + k) } } ?: (0 until vc).forEach { I.add(base + it) }
            if (texIndex == null) (prim["material"] as? Double)?.let { mat -> ((list("materials")[mat.toInt()]["pbrMetallicRoughness"] as? Map<String, Any?>)?.get("baseColorTexture") as? Map<String, Any?>)?.get("index")?.let { texIndex = (it as Double).toInt() } }
            ((prim["extras"] as? Map<String, Any?>)?.get("belle_pm") as? Map<String, Any?>)?.takeIf { ((it["version"] as? Double)?.toInt() ?: 1) >= ProgressiveMesh.VERSION }?.let { e ->
                if (inst.size == 1 && (meshes[mi]["primitives"] as List<*>).size == 1) cleanTris = (e["cleanTris"] as? Double)?.toInt() ?: 0
                if (inst.size == 1 && (meshes[mi]["primitives"] as List<*>).size == 1) pm = accInt((e["parent"] as Double).toInt()) to accInt((e["triDeath"] as Double).toInt()).map { if (it == -1 || it == 0xFFFFFFFF.toInt()) -1 else it }.toIntArray()
            }
        }
        require(I.size >= 3) { "The .glb has no triangles" }
        var tex: ByteArray? = null; var mime: String? = null
        texIndex?.let { ti -> val src = ((list("textures")[ti]["source"]) as? Double)?.toInt(); val img = src?.let { list("images")[it] }
            (img?.get("bufferView") as? Double)?.let { bv -> val (o, l, _) = view(bv.toInt()); tex = ByteArray(l).also { arr -> val d = bin!!.duplicate(); d.position(o); d.get(arr) }; mime = img["mimeType"] as? String } }
        val mesh = Mesh3D(P.toArray(), if (anyN) N.toArray() else null, if (anyU) U.toArray() else null, if (anyC) C.toArray() else null,
            if (anySkin && J.size > 0) J.toArray() else null, if (anySkin && W.size > 0) W.toArray() else null, I.toArray(), tex, mime, pm?.first, pm?.second)
        if (pm != null) mesh.pmCleanTris = cleanTris
        if (j2 != null && w2 != null && j2!!.size == mesh.vertexCount * 4 && w2!!.size == mesh.vertexCount * 4 && mesh.joints != null) { mesh.joints2 = j2; mesh.weights2 = w2 }
        // ---- skeleton + animations (kept and used when the file has them) ----
        if (skinNode != null && mesh.joints != null) {
            val skin = list("skins")[(nodes[skinNode]["skin"] as Double).toInt()]
            val jn = (skin["joints"] as List<Double>).map { it.toInt() }; val J = jn.size
            val parentNode = HashMap<Int, Int>(); nodes.forEachIndexed { i, n -> (n["children"] as? List<Double>)?.forEach { parentNode[it.toInt()] = i } }
            val par = IntArray(J) { k -> var p = parentNode[jn[k]]; while (p != null && p !in jn) p = parentNode[p]; p?.let { jn.indexOf(it) } ?: -1 }
            val t = FloatArray(J * 3); val r = FloatArray(J * 4); val sc = FloatArray(J * 3)
            for (k in 0 until J) { val n = nodes[jn[k]]
                ((n["translation"] as? List<Double>) ?: listOf(0.0, 0.0, 0.0)).forEachIndexed { i, v -> t[k * 3 + i] = v.toFloat() }
                ((n["rotation"] as? List<Double>) ?: listOf(0.0, 0.0, 0.0, 1.0)).forEachIndexed { i, v -> r[k * 4 + i] = v.toFloat() }
                ((n["scale"] as? List<Double>) ?: listOf(1.0, 1.0, 1.0)).forEachIndexed { i, v -> sc[k * 3 + i] = v.toFloat() }
                (n["matrix"] as? List<Double>)?.let { mm -> M4.decompose(FloatArray(16) { mm[it].toFloat() }, t, k * 3, r, k * 4, sc, k * 3) } }
            // root joints inherit any non-joint ancestors (e.g. an "Armature" node with a 0.01 scale): fold that into the root's rest
            val ibm = (skin["inverseBindMatrices"] as? Double)?.let { acc(it.toInt()).first } ?: FloatArray(J * 16) { if (it % 16 % 5 == 0) 1f else 0f }
            val names = Array(J) { (nodes[jn[it]]["name"] as? String) ?: "joint$it" }
            val sk = Skeleton(names, par, t, r, sc, ibm)
            val mot = FloatArray(J) { k -> (((nodes[jn[k]]["extras"] as? Map<String, Any?>)?.get("belle_motion")) as? Double)?.toFloat()?.coerceIn(0f, 1f) ?: 1f }
            if (mot.any { it < 1f }) sk.motion = mot
            for (k in 0 until J) if (par[k] < 0) parentNode[jn[k]]?.let { pn -> world[pn]?.let { base -> sk.rootBase[k] = base } }
            mesh.skeleton = sk
            mesh.animations = list("animations").mapNotNull { a ->
                val samplers = a["samplers"] as List<Map<String, Any?>>
                val ch = (a["channels"] as List<Map<String, Any?>>).mapNotNull { c ->
                    val tg = c["target"] as Map<String, Any?>; val node = (tg["node"] as? Double)?.toInt() ?: return@mapNotNull null
                    val k = jn.indexOf(node); if (k < 0) return@mapNotNull null
                    val path = when (tg["path"]) { "translation" -> 0; "rotation" -> 1; "scale" -> 2; else -> return@mapNotNull null }
                    val smp = samplers[(c["sampler"] as Double).toInt()]
                    var vals = acc((smp["output"] as Double).toInt()).first
                    if (smp["interpolation"] == "CUBICSPLINE") { val n = if (path == 1) 4 else 3; val keys = vals.size / (3 * n); vals = FloatArray(keys * n) { i -> vals[(i / n) * 3 * n + n + i % n] } }
                    AnimChannel(k, path, acc((smp["input"] as Double).toInt()).first, vals, smp["interpolation"] == "STEP")
                }
                if (ch.isEmpty()) null else Anim((a["name"] as? String) ?: "animation", ch)
            }
        }
        return mesh
    }

    fun write(m: Mesh3D): ByteArray {
        val bin = ByteArrayOutputStream(); val views = mutableListOf<String>(); val accs = mutableListOf<String>()
        fun pad() { while (bin.size() % 4 != 0) bin.write(0) }
        fun addView(bytes: ByteArray, target: Int?): Int { pad(); val o = bin.size(); bin.write(bytes); views += """{"buffer":0,"byteOffset":$o,"byteLength":${bytes.size}${target?.let { ",\"target\":$it" } ?: ""}}"""; return views.size - 1 }
        fun floats(a: FloatArray) = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(a) }.array()
        fun ints(a: IntArray) = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(a) }.array()
        fun shorts(a: IntArray) = ByteBuffer.allocate(a.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { a.forEach { putShort(it.toShort()) } }.array()
        fun accF(a: FloatArray, type: String, comps: Int, minmax: Boolean = false): Int {
            val v = addView(floats(a), 34962); val n = a.size / comps
            val mm = if (minmax) { val b = m.bounds(); ""","min":[${b[0]},${b[1]},${b[2]}],"max":[${b[3]},${b[4]},${b[5]}]""" } else ""
            accs += """{"bufferView":$v,"componentType":5126,"count":$n,"type":"$type"$mm}"""; return accs.size - 1 }
        fun accI(a: IntArray, type: String, comps: Int, target: Int?, ct: Int = 5125): Int {
            val v = addView(if (ct == 5123) shorts(a) else ints(a), target); accs += """{"bufferView":$v,"componentType":$ct,"count":${a.size / comps},"type":"$type"}"""; return accs.size - 1 }
        val attrs = mutableListOf("\"POSITION\":${accF(m.pos, "VEC3", 3, true)}")
        m.nrm?.let { attrs += "\"NORMAL\":${accF(it, "VEC3", 3)}" }
        m.uv?.let { attrs += "\"TEXCOORD_0\":${accF(it, "VEC2", 2)}" }
        m.col?.let { attrs += "\"COLOR_0\":${accF(it, "VEC3", 3)}" }
        if (m.joints != null && m.weights != null) { attrs += "\"JOINTS_0\":${accI(m.joints, "VEC4", 4, 34962, 5123)}"; attrs += "\"WEIGHTS_0\":${accF(m.weights, "VEC4", 4)}" }
        val j2 = m.joints2; val w2 = m.weights2
        if (m.joints != null && j2 != null && w2 != null) { attrs += "\"JOINTS_1\":${accI(j2, "VEC4", 4, 34962, 5123)}"; attrs += "\"WEIGHTS_1\":${accF(w2, "VEC4", 4)}" }
        val ind = accI(m.idx, "SCALAR", 1, 34963)
        val extras = if (m.hasPm) ""","extras":{"belle_pm":{"version":${ProgressiveMesh.VERSION},"parent":${accI(m.pmParent!!, "SCALAR", 1, null)},"triDeath":${accI(m.pmTriDeath!!, "SCALAR", 1, null)},"cleanTris":${m.pmCleanTris}}}""" else ""
        var material = ""; var tail = ""
        if (m.texture != null) {
            val iv = addView(m.texture, null)
            material = ""","material":0"""
            tail = ""","materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1}}],"textures":[{"source":0,"sampler":0}],"samplers":[{"magFilter":9729,"minFilter":9987}],"images":[{"bufferView":$iv,"mimeType":"${m.textureMime ?: "image/png"}"}]"""
        }
        var nodes = """[{"mesh":0}]"""; var sceneNodes = "[0]"; var skinJson = ""; var animJson = ""
        val sk = m.skeleton
        if (sk != null && m.joints != null) {
            val ib = accF(sk.inverseBind, "MAT4", 16)
            val roots = (0 until sk.size).filter { sk.parent[it] < 0 }
            val wrappers = roots.filter { sk.rootBase[it] != null }
            nodes = "[" + (listOf("""{"mesh":0,"skin":0}""") + (0 until sk.size).map { k ->
                val ch = (0 until sk.size).filter { sk.parent[it] == k }.map { it + 1 }
                val trs = ""","translation":[${sk.restT[k * 3]},${sk.restT[k * 3 + 1]},${sk.restT[k * 3 + 2]}],"rotation":[${(0..3).joinToString(",") { sk.restR[k * 4 + it].toString() }}],"scale":[${sk.restS[k * 3]},${sk.restS[k * 3 + 1]},${sk.restS[k * 3 + 2]}]"""
                val mo = sk.motion?.get(k)?.takeIf { it < 1f }?.let { ""","extras":{"belle_motion":$it}""" } ?: ""
                """{"name":"${sk.names[k].replace("\"", "")}"$trs${if (ch.isNotEmpty()) ",\"children\":$ch" else ""}$mo}"""
            } + wrappers.map { k -> """{"name":"Armature","matrix":[${sk.rootBase[k]!!.joinToString(",")}],"children":[${k + 1}]}""" }).joinToString(",") + "]"
            sceneNodes = (listOf(0) + roots.map { k -> if (k in wrappers) sk.size + 1 + wrappers.indexOf(k) else k + 1 }).toString()
            skinJson = ""","skins":[{"joints":${(1..sk.size).toList()},"inverseBindMatrices":$ib}]"""
            if (m.animations.isNotEmpty()) animJson = ""","animations":[""" + m.animations.joinToString(",") { a ->
                val smp = mutableListOf<String>(); val chs = mutableListOf<String>()
                a.channels.forEach { c -> val ti = accF(c.times, "SCALAR", 1); val vi = accF(c.values, if (c.path == 1) "VEC4" else "VEC3", if (c.path == 1) 4 else 3)
                    smp += """{"input":$ti,"output":$vi,"interpolation":"${if (c.step) "STEP" else "LINEAR"}"}"""
                    chs += """{"sampler":${smp.size - 1},"target":{"node":${c.joint + 1},"path":"${listOf("translation", "rotation", "scale")[c.path]}"}}""" }
                """{"name":"${a.name.replace("\"", "")}","samplers":[${smp.joinToString(",")}],"channels":[${chs.joinToString(",")}]}"""
            } + "]"
        }
        pad()
        val json = """{"asset":{"version":"2.0","generator":"Milky Moo's Assistant"},"scene":0,"scenes":[{"nodes":$sceneNodes}],"nodes":$nodes,"meshes":[{"primitives":[{"attributes":{${attrs.joinToString(",")}},"indices":$ind$material$extras}]}]$skinJson$animJson$tail,"accessors":[${accs.joinToString(",")}],"bufferViews":[${views.joinToString(",")}],"buffers":[{"byteLength":${bin.size()}}]}"""
        var jb = json.toByteArray(Charsets.UTF_8); if (jb.size % 4 != 0) jb += ByteArray(4 - jb.size % 4) { ' '.code.toByte() }
        val b = bin.toByteArray()
        val out = ByteBuffer.allocate(12 + 8 + jb.size + 8 + b.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC).putInt(2).putInt(out.capacity()).putInt(jb.size).putInt(JSON).put(jb).putInt(b.size).putInt(BIN).put(b)
        return out.array()
    }
}

/** Polygon-mesh .ply (ASCII or binary little-endian) with optional normals / vertex colours / UVs. */
object Ply {
    fun read(bytes: ByteArray): Mesh3D {
        val headerEnd = String(bytes, 0, minOf(bytes.size, 65536), Charsets.ISO_8859_1).indexOf("end_header").let { require(it >= 0) { "Not a .ply file" }; var k = it; while (k < bytes.size && bytes[k] != '\n'.code.toByte()) k++; k + 1 }
        val lines = String(bytes, 0, headerEnd, Charsets.ISO_8859_1).lines().map { it.trim() }
        require(lines.first() == "ply") { "Not a .ply file" }
        val fmt = lines.first { it.startsWith("format") }.split(" ")[1]
        require(fmt == "ascii" || fmt == "binary_little_endian") { "PLY format $fmt isn't supported" }
        data class Prop(val name: String, val type: String, val listCount: String? = null)
        val elems = mutableListOf<Pair<String, Int>>(); val props = HashMap<String, MutableList<Prop>>()
        for (l in lines) { val p = l.split(Regex("\\s+"))
            if (p[0] == "element") { elems += p[1] to p[2].toInt(); props[p[1]] = mutableListOf() }
            if (p[0] == "property") props[elems.last().first]!! += if (p[1] == "list") Prop(p[4], p[3], p[2]) else Prop(p[2], p[1]) }
        val vp = props["vertex"] ?: error("No vertices")
        require(elems.any { it.first == "face" && it.second > 0 }) { if (vp.any { it.name.startsWith("f_dc") }) "This .ply is a Gaussian splat, not a mesh. Export a mesh (.glb) instead." else "This .ply has no faces (point cloud)" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); bb.position(headerEnd)
        val tok = if (fmt == "ascii") String(bytes, headerEnd, bytes.size - headerEnd, Charsets.ISO_8859_1).split(Regex("\\s+")).filter { it.isNotEmpty() }.iterator() else null
        fun num(t: String): Double = if (tok != null) tok.next().toDouble() else when (t) {
            "char", "int8" -> bb.get().toDouble(); "uchar", "uint8" -> (bb.get().toInt() and 0xFF).toDouble()
            "short", "int16" -> bb.short.toDouble(); "ushort", "uint16" -> (bb.short.toInt() and 0xFFFF).toDouble()
            "int", "int32" -> bb.int.toDouble(); "uint", "uint32" -> (bb.int.toLong() and 0xFFFFFFFFL).toDouble()
            "float", "float32" -> bb.float.toDouble(); "double", "float64" -> bb.double; else -> error("ply type $t") }
        val P = FloatList(); val N = FloatList(); val C = FloatList(); val U = FloatList(); val I = IntList()
        val hasN = vp.any { it.name == "nx" }; val hasC = vp.any { it.name == "red" }; val hasU = vp.any { it.name == "s" || it.name == "u" || it.name == "texture_u" }
        for ((e, count) in elems) {
            val ps = props[e]!!
            repeat(count) {
                if (e == "face") for (p in ps) { if (p.listCount != null) { val n = num(p.listCount).toInt(); val f = IntArray(n) { num(p.type).toInt() }
                    if (p.name == "vertex_indices" || p.name == "vertex_index") for (k in 1 until n - 1) { I.add(f[0]); I.add(f[k]); I.add(f[k + 1]) } } else num(p.type) }
                else if (e == "vertex") { val v = HashMap<String, Double>(); for (p in ps) v[p.name] = if (p.listCount != null) { repeat(num(p.listCount).toInt()) { num(p.type) }; 0.0 } else num(p.type)
                    P.add(v["x"]!!.toFloat()); P.add(v["y"]!!.toFloat()); P.add(v["z"]!!.toFloat())
                    if (hasN) { N.add(v["nx"]!!.toFloat()); N.add(v["ny"]!!.toFloat()); N.add(v["nz"]!!.toFloat()) }
                    if (hasC) { val sc = if (ps.first { it.name == "red" }.type.startsWith("float")) 1f else 255f; C.add(v["red"]!!.toFloat() / sc); C.add(v["green"]!!.toFloat() / sc); C.add(v["blue"]!!.toFloat() / sc) }
                    if (hasU) { U.add((v["s"] ?: v["u"] ?: v["texture_u"])!!.toFloat()); U.add(1f - (v["t"] ?: v["v"] ?: v["texture_v"])!!.toFloat()) } }
                else for (p in ps) if (p.listCount != null) repeat(num(p.listCount).toInt()) { num(p.type) } else num(p.type)
            }
        }
        return Mesh3D(P.toArray(), if (hasN) N.toArray() else null, if (hasU) U.toArray() else null, if (hasC) C.toArray() else null, null, null, I.toArray(), null, null)
    }
}

class FloatList { var a = FloatArray(1024); var size = 0
    fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun toArray() = a.copyOf(size) }
class IntList(cap: Int = 1024) { var a = IntArray(cap); var size = 0
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }
    fun toArray() = a.copyOf(size) }

/** A glTF skin: joints with parents, rest pose (T/R/S) and inverse bind matrices (column-major 4x4). */
class Skeleton(val names: Array<String>, val parent: IntArray, val restT: FloatArray, val restR: FloatArray, val restS: FloatArray, val inverseBind: FloatArray) {
    val size get() = parent.size
    /** Transform of non-joint ancestors above a root joint (folded in when evaluating the pose). */
    val rootBase = HashMap<Int, FloatArray>()
    /** Per joint: how much of the procedural motion (idle sway, gestures, wing / tail / ear motion) it gets, 0..1
     *  (null = all). The auto-rig lowers it for a part fused to another part it would otherwise tear away from. */
    var motion: FloatArray? = null
    fun find(vararg keys: String): Int = names.indexOfFirst { n -> keys.any { n.contains(it, ignoreCase = true) } }
}
/** One animation channel on a joint: path 0 = translation, 1 = rotation (quaternion xyzw), 2 = scale. */
class AnimChannel(val joint: Int, val path: Int, val times: FloatArray, val values: FloatArray, val step: Boolean)
class Anim(val name: String, val channels: List<AnimChannel>) { val duration = channels.maxOfOrNull { it.times.lastOrNull() ?: 0f } ?: 0f }

/** Column-major 4x4 helpers. */
object M4 {
    fun ident() = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
    fun mul(a: FloatArray, b: FloatArray, o: FloatArray = FloatArray(16)): FloatArray {
        for (c in 0..3) for (r in 0..3) { var s = 0f; for (k in 0..3) s += a[k * 4 + r] * b[c * 4 + k]; o[c * 4 + r] = s }; return o }
    fun trs(t: FloatArray, to: Int, q: FloatArray, qo: Int, s: FloatArray, so: Int, o: FloatArray = FloatArray(16)): FloatArray {
        val x = q[qo]; val y = q[qo + 1]; val z = q[qo + 2]; val w = q[qo + 3]
        o[0] = (1 - 2 * (y * y + z * z)) * s[so]; o[1] = 2 * (x * y + z * w) * s[so]; o[2] = 2 * (x * z - y * w) * s[so]; o[3] = 0f
        o[4] = 2 * (x * y - z * w) * s[so + 1]; o[5] = (1 - 2 * (x * x + z * z)) * s[so + 1]; o[6] = 2 * (y * z + x * w) * s[so + 1]; o[7] = 0f
        o[8] = 2 * (x * z + y * w) * s[so + 2]; o[9] = 2 * (y * z - x * w) * s[so + 2]; o[10] = (1 - 2 * (x * x + y * y)) * s[so + 2]; o[11] = 0f
        o[12] = t[to]; o[13] = t[to + 1]; o[14] = t[to + 2]; o[15] = 1f; return o }
    /** Rotation quaternion (xyzw) about a unit axis. */
    fun quat(ax: Float, ay: Float, az: Float, rad: Float): FloatArray { val s = kotlin.math.sin(rad / 2); return floatArrayOf(ax * s, ay * s, az * s, kotlin.math.cos(rad / 2)) }
    fun qmul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1], a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3], a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2])
    fun decompose(m: FloatArray, t: FloatArray, to: Int, q: FloatArray, qo: Int, s: FloatArray, so: Int) {
        t[to] = m[12]; t[to + 1] = m[13]; t[to + 2] = m[14]
        val sx = kotlin.math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]); val sy = kotlin.math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]); val sz = kotlin.math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
        s[so] = sx; s[so + 1] = sy; s[so + 2] = sz
        val r00 = m[0] / sx; val r10 = m[1] / sx; val r20 = m[2] / sx; val r01 = m[4] / sy; val r11 = m[5] / sy; val r21 = m[6] / sy; val r02 = m[8] / sz; val r12 = m[9] / sz; val r22 = m[10] / sz
        val tr = r00 + r11 + r22
        if (tr > 0) { val w = kotlin.math.sqrt(1 + tr) * 2; q[qo + 3] = 0.25f * w; q[qo] = (r21 - r12) / w; q[qo + 1] = (r02 - r20) / w; q[qo + 2] = (r10 - r01) / w }
        else if (r00 > r11 && r00 > r22) { val w = kotlin.math.sqrt(1 + r00 - r11 - r22) * 2; q[qo + 3] = (r21 - r12) / w; q[qo] = 0.25f * w; q[qo + 1] = (r01 + r10) / w; q[qo + 2] = (r02 + r20) / w }
        else if (r11 > r22) { val w = kotlin.math.sqrt(1 + r11 - r00 - r22) * 2; q[qo + 3] = (r02 - r20) / w; q[qo] = (r01 + r10) / w; q[qo + 1] = 0.25f * w; q[qo + 2] = (r12 + r21) / w }
        else { val w = kotlin.math.sqrt(1 + r22 - r00 - r11) * 2; q[qo + 3] = (r10 - r01) / w; q[qo] = (r02 + r20) / w; q[qo + 1] = (r12 + r21) / w; q[qo + 2] = 0.25f * w }
    }
    fun invertAffine(m: FloatArray): FloatArray {  // general 4x4 inverse (bind matrices may contain scale)
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        return FloatArray(16) { inv[it] / det }
    }
}
