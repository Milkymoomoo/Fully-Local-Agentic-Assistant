package app.belle.assistant.mesh

import app.belle.assistant.core.MiniJson
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a PC-made avatar: .glb, .gltf (embedded or with its .bin/.png picked alongside), .obj (+ .mtl + texture) or .ply. */
object Import3D {
    val EXTENSIONS = listOf("glb", "gltf", "obj", "ply")

    /** [files] = every picked file by lower-case name; the first model file found is used. */
    fun read(files: Map<String, ByteArray>): Mesh3D {
        val main = files.keys.firstOrNull { it.endsWith(".glb") } ?: files.keys.firstOrNull { it.endsWith(".gltf") }
            ?: files.keys.firstOrNull { it.endsWith(".obj") } ?: files.keys.firstOrNull { it.endsWith(".ply") }
            ?: error("Pick a .glb, .gltf, .obj or .ply file")
        val b = files[main]!!
        return when (main.substringAfterLast('.')) {
            "glb" -> Glb.read(b)
            "gltf" -> Glb.read(gltfToGlb(String(b, Charsets.UTF_8), files))
            "obj" -> obj(String(b, Charsets.UTF_8), files)
            else -> Ply.read(b)
        }
    }

    private fun lookup(uri: String, files: Map<String, ByteArray>): ByteArray {
        if (uri.startsWith("data:")) return java.util.Base64.getDecoder().decode(uri.substringAfter("base64,"))
        val name = java.net.URLDecoder.decode(uri, "UTF-8").substringAfterLast('/').lowercase()
        return files[name] ?: error("The .gltf needs \"$name\" too. Pick it together with the .gltf (or export a single .glb).")
    }

    @Suppress("UNCHECKED_CAST")
    fun gltfToGlb(text: String, files: Map<String, ByteArray>): ByteArray {
        val j = (MiniJson.parse(text) as Map<String, Any?>).toMutableMap()
        val bin = ByteArrayOutputStream(); val bufOffsets = mutableListOf<Int>()
        for (buf in (j["buffers"] as? List<Map<String, Any?>>).orEmpty()) {
            while (bin.size() % 4 != 0) bin.write(0); bufOffsets += bin.size(); bin.write(lookup(buf["uri"] as String, files))
        }
        val views = (j["bufferViews"] as? List<Map<String, Any?>>).orEmpty().map { v ->
            v.toMutableMap().apply { this["byteOffset"] = ((v["byteOffset"] as? Double) ?: 0.0) + bufOffsets[((v["buffer"] as? Double) ?: 0.0).toInt()]; this["buffer"] = 0.0 }
        }.toMutableList<Map<String, Any?>>()
        val images = (j["images"] as? List<Map<String, Any?>>).orEmpty().map { im ->
            val uri = im["uri"] as? String ?: return@map im
            val data = lookup(uri, files); while (bin.size() % 4 != 0) bin.write(0)
            views += mapOf("buffer" to 0.0, "byteOffset" to bin.size().toDouble(), "byteLength" to data.size.toDouble()); bin.write(data)
            val mime = if (uri.startsWith("data:")) uri.substringAfter("data:").substringBefore(';') else if (uri.lowercase().endsWith(".png")) "image/png" else "image/jpeg"
            mapOf("bufferView" to (views.size - 1).toDouble(), "mimeType" to mime)
        }
        j["bufferViews"] = views; j["images"] = images; j["buffers"] = listOf(mapOf("byteLength" to bin.size().toDouble()))
        while (bin.size() % 4 != 0) bin.write(0)
        var jb = toJson(j).toByteArray(Charsets.UTF_8); if (jb.size % 4 != 0) jb += ByteArray(4 - jb.size % 4) { 32 }
        val b = bin.toByteArray()
        return ByteBuffer.allocate(28 + jb.size + b.size).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546C67).putInt(2).putInt(28 + jb.size + b.size)
            .putInt(jb.size).putInt(0x4E4F534A).put(jb).putInt(b.size).putInt(0x004E4942).put(b).array()
    }

    fun toJson(v: Any?): String = when (v) {
        null -> "null"; is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { toJson(it.key.toString()) + ":" + toJson(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { toJson(it) }
        else -> toJson(v.toString())
    }

    fun obj(text: String, files: Map<String, ByteArray>): Mesh3D {
        val vp = FloatList(); val vc = FloatList(); val vt = FloatList(); val vn = FloatList()
        val P = FloatList(); val U = FloatList(); val N = FloatList(); val C = FloatList(); val I = IntList()
        val seen = HashMap<String, Int>(); var hasColor = false; var mtllib: String? = null; var firstMtl: String? = null
        for (raw in text.lineSequence()) {
            val l = raw.trim(); if (l.isEmpty() || l[0] == '#') continue
            val p = l.split(Regex("\\s+"))
            when (p[0]) {
                "v" -> { for (k in 1..3) vp.add(p[k].toFloat()); if (p.size >= 7) { hasColor = true; for (k in 4..6) vc.add(p[k].toFloat()) } else repeat(3) { vc.add(1f) } }
                "vt" -> { vt.add(p[1].toFloat()); vt.add(1f - p.getOrElse(2) { "0" }.toFloat()) }
                "vn" -> for (k in 1..3) vn.add(p[k].toFloat())
                "mtllib" -> mtllib = l.substringAfter(' ').trim()
                "usemtl" -> if (firstMtl == null) firstMtl = p[1]
                "f" -> {
                    val ids = p.drop(1).map { c ->
                        seen.getOrPut(c) {
                            val q = c.split('/'); fun ix(s: String?, n: Int) = s?.takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 0) n + it else it - 1 }
                            val vi = ix(q[0], vp.size / 3)!!; val ti = ix(q.getOrNull(1), vt.size / 2); val ni = ix(q.getOrNull(2), vn.size / 3)
                            for (k in 0..2) { P.add(vp[vi * 3 + k]); C.add(vc[vi * 3 + k]) }
                            if (ti != null) { U.add(vt[ti * 2]); U.add(vt[ti * 2 + 1]) } else { U.add(0f); U.add(0f) }
                            if (ni != null) for (k in 0..2) N.add(vn[ni * 3 + k]) else repeat(3) { N.add(0f) }
                            P.size / 3 - 1
                        }
                    }
                    for (k in 1 until ids.size - 1) { I.add(ids[0]); I.add(ids[k]); I.add(ids[k + 1]) }
                }
            }
        }
        require(I.size > 0) { "The .obj has no faces" }
        // texture from the .mtl (map_Kd), if the user picked it alongside
        var tex: ByteArray? = null; var mime: String? = null
        val mtl = mtllib?.let { files[it.substringAfterLast('/').lowercase()] } ?: files.entries.firstOrNull { it.key.endsWith(".mtl") }?.value
        val texName = mtl?.let { String(it).lineSequence().map { it.trim() }.firstOrNull { it.startsWith("map_Kd") }?.split(Regex("\\s+"))?.last()?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase() }
        val texFile = texName?.let { files[it] } ?: files.entries.firstOrNull { it.key.endsWith(".png") || it.key.endsWith(".jpg") || it.key.endsWith(".jpeg") }?.value
        if (texFile != null) { tex = texFile; mime = if (texFile.size > 3 && texFile[1] == 'P'.code.toByte()) "image/png" else "image/jpeg" }
        val noNormals = vn.size == 0
        return Mesh3D(P.toArray(), if (noNormals) null else N.toArray(), if (vt.size > 0) U.toArray() else null, if (hasColor) C.toArray() else null, null, null, I.toArray(), tex, mime).withNormals()
    }
}

private operator fun FloatList.get(i: Int) = a[i]
