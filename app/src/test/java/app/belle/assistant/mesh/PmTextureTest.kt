package app.belle.assistant.mesh

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Low detail levels must keep the texture in place: a software front view of each level is compared with the full
 * mesh (mean colour error over pixels both cover). Sheets are written to build/pm_tex_<name>.png.
 */
class PmTextureTest {
    private var lastIdx = IntArray(0)
    private var debugBack = false; private var twoSided = true
    private fun asset(n: String) = listOf(File("src/main/assets/default_avatars/$n"), File("app/src/main/assets/default_avatars/$n")).first { it.exists() }

    private fun render(m: Mesh3D, tex: BufferedImage, n: Int, S: Int, lit: Boolean = false, cull: Int = 0, triOut: IntArray? = null): IntArray {
        val idx = IntArray(m.idx.size); val tris = ProgressiveMesh.trim(m, n, idx); lastIdx = idx
        val b = m.bounds(); val h = maxOf(b[4] - b[1], b[3] - b[0]); val img = IntArray(S * S); val zb = FloatArray(S * S) { -1e9f }
        fun sx(i: Int) = (m.pos[i * 3] - (b[0] + b[3]) / 2) / h * S * 0.95f + S / 2f
        fun sy(i: Int) = S - ((m.pos[i * 3 + 1] - b[1]) / h * S * 0.95f + S * 0.025f)
        val uv = m.uv!!; val tw = tex.width; val th = tex.height
        for (t in 0 until tris) { val a = idx[t * 3]; val c1 = idx[t * 3 + 1]; val c2 = idx[t * 3 + 2]
            val x0 = sx(a); val y0 = sy(a); val x1 = sx(c1); val y1 = sy(c1); val x2 = sx(c2); val y2 = sy(c2)
            val den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2); if (kotlin.math.abs(den) < 1e-9f) continue
            if (cull != 0) { val p = m.pos; val fz = (p[c1*3]-p[a*3])*(p[c2*3+1]-p[a*3+1]) - (p[c1*3+1]-p[a*3+1])*(p[c2*3]-p[a*3]); if (fz * cull < 0) continue }
            for (py in maxOf(0, minOf(y0, y1, y2).toInt())..minOf(S - 1, maxOf(y0, y1, y2).toInt()))
                for (px in maxOf(0, minOf(x0, x1, x2).toInt())..minOf(S - 1, maxOf(x0, x1, x2).toInt())) {
                    val fx = px + 0.5f; val fy = py + 0.5f
                    val w0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / den; val w1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / den; val w2 = 1 - w0 - w1
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue
                    val z = w0 * m.pos[a * 3 + 2] + w1 * m.pos[c1 * 3 + 2] + w2 * m.pos[c2 * 3 + 2]; val o = py * S + px; if (z <= zb[o]) continue
                    zb[o] = z; triOut?.set(o, t)
                    val u = w0 * uv[a * 2] + w1 * uv[c1 * 2] + w2 * uv[c2 * 2]; val v = w0 * uv[a * 2 + 1] + w1 * uv[c1 * 2 + 1] + w2 * uv[c2 * 2 + 1]
                    var col = tex.getRGB(((u - kotlin.math.floor(u)) * tw).toInt().coerceIn(0, tw - 1), ((v - kotlin.math.floor(v)) * th).toInt().coerceIn(0, th - 1))
                    if (lit) { val nr = m.nrm!!; var nx = w0*nr[a*3]+w1*nr[c1*3]+w2*nr[c2*3]; var ny = w0*nr[a*3+1]+w1*nr[c1*3+1]+w2*nr[c2*3+1]; var nz = w0*nr[a*3+2]+w1*nr[c1*3+2]+w2*nr[c2*3+2]
                        if (twoSided && nz < 0) { nx = -nx; ny = -ny; nz = -nz }
                        val ln = kotlin.math.sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(1e-6f); val L = floatArrayOf(-0.4f, 0.7f, 0.9f); val ll = kotlin.math.sqrt(L[0]*L[0]+L[1]*L[1]+L[2]*L[2])
                        val k = 0.4f + 0.7f * maxOf(0f, (nx*L[0]+ny*L[1]+nz*L[2]) / ln / ll)
                        val r = (((col shr 16) and 255) * k).toInt().coerceAtMost(255); val g = (((col shr 8) and 255) * k).toInt().coerceAtMost(255); val bl = ((col and 255) * k).toInt().coerceAtMost(255); col = (r shl 16) or (g shl 8) or bl }
                    if (lit && debugBack) { val p = m.pos; val fz = (p[c1*3]-p[a*3])*(p[c2*3+1]-p[a*3+1]) - (p[c1*3+1]-p[a*3+1])*(p[c2*3]-p[a*3]); if (fz < 0) col = 0xFF0000 }
                    img[o] = col or (0xFF shl 24)
                } }
        return img
    }

    private fun texOf(m: Mesh3D): BufferedImage {
        var t = ImageIO.read(m.texture!!.inputStream())
        if (maxOf(t.width, t.height) > 2048) { val s = BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB); val g = s.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(t, 0, 0, 2048, 2048, null); g.dispose(); t = s }
        return t
    }
    private fun img(l: TextureMips.Level) = BufferedImage(l.w, l.h, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, l.w, l.h, l.px, 0, l.w) }

    /** Mip 2/3 sampling, plain box chain vs chart-aware chain, against full-resolution texels. */
    private fun mips(name: String, file: String): Pair<Double, Double> {
        val f = listOf(File("../$file"), File(file), File("app/src/main/assets/default_avatars/$file"), File("src/main/assets/default_avatars/$file")).first { it.exists() }
        val m = ProgressiveMesh.build(Glb.read(f.readBytes())); val t = texOf(m); val w = t.width; val h = t.height
        val px = t.getRGB(0, 0, w, h, null, 0, w)
        val used = TextureMips.coverage(w, h, m.uv!!, m.idx)
        val box = TextureMips.box(w, h, px); val chart = TextureMips.build(w, h, px, used)
        val S = 400; val full = render(m, t, m.vertexCount, S); val sheet = BufferedImage(S * 5, S, BufferedImage.TYPE_INT_RGB); sheet.setRGB(0, 0, S, S, full, 0, S)
        fun err(a: IntArray): Double { var e = 0.0; var c = 0; for (o in a.indices) { if (a[o] == 0 || full[o] == 0) continue; c++
            for (sh in intArrayOf(0, 8, 16)) e += kotlin.math.abs(((a[o] shr sh) and 255) - ((full[o] shr sh) and 255)) / 3.0 }; return e / maxOf(1, c) }
        var eb = 0.0; var ec = 0.0
        for ((i, lv) in listOf(2, 3).withIndex()) {
            val rb = render(m, img(box[lv]), m.vertexCount, S); val rc = render(m, img(chart[lv]), m.vertexCount, S)
            sheet.setRGB(S * (1 + i * 2), 0, S, S, rb, 0, S); sheet.setRGB(S * (2 + i * 2), 0, S, S, rc, 0, S)
            println("$name mip $lv: box error=${"%.1f".format(err(rb))} chart-aware error=${"%.1f".format(err(rc))} used=${used.count { it } * 100 / used.size}%")
            if (lv == 2) { eb = err(rb); ec = err(rc) }
        }
        ImageIO.write(sheet, "png", File("build/mips_$name.png")); return eb to ec
    }

    @Test fun chartAwareMipsBeatBoxFilter() {
        for ((n, f) in listOf("bundled_bob" to "bob.glb", "imported_bob_pixal3d" to "art/bob_pixal3d.glb")) {
            val (b, c) = mips(n, f); assertTrue("$n chart-aware $c vs box $b", c < b)
        }
    }

    private fun check(name: String, file: String): List<Double> {
        // exactly the app's import path: Import3D.read → withNormals → progressive mesh → auto-rig → save .glb → load
        var m0 = Import3D.read(mapOf(file to asset(file).readBytes())).withNormals()
        m0 = ProgressiveMesh.build(m0); m0 = AutoRig3D.rig(m0).mesh
        val m = Glb.read(Glb.write(m0)).withNormals()
        val tex = ImageIO.read(m.texture!!.inputStream()) ?: return emptyList()
        val S = 400; val levels = listOf(1f, 0.3f, 0.1f, 0.03f)
        val full = render(m, tex, m.vertexCount, S); val sheet = BufferedImage(S * levels.size, S, BufferedImage.TYPE_INT_RGB); val errs = ArrayList<Double>()
        for ((i, lv) in levels.withIndex()) {
            val n = ProgressiveMesh.verticesForTriangles(m, (m.triCount * lv).toInt())
            val img = render(m, tex, n, S); var e = 0.0; var c = 0
            for (o in img.indices) { if (img[o] == 0 || full[o] == 0) continue; c++
                for (sh in intArrayOf(0, 8, 16)) e += kotlin.math.abs(((img[o] shr sh) and 255) - ((full[o] shr sh) and 255)) / 3.0 }
            errs += e / maxOf(1, c); sheet.setRGB(i * S, 0, S, S, img, 0, S)
            println("$name level ${lv} verts=$n meanColourError=${"%.1f".format(e / maxOf(1, c))}")
        }
        File("build").mkdirs(); ImageIO.write(sheet, "png", File("build/pm_tex_$name.png")); return errs
    }

    @Test fun textureSurvivesDetailLevels() {
        for ((n, f) in listOf("bob" to "bob.glb", "boob_dragon" to "boob_dragon.glb")) { val e = check(n, f); if (e.size > 2) assertTrue("$n 30% error ${e[1]}", e[1] < 25.0) }
    }

    /** Lighting at low detail: inner shells that poke through must not go grey (two-sided lighting, as in the shader). */
    @Test fun lowDetailLightingMatchesFull() {
        val white = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB).also { for (y in 0..3) for (x in 0..3) it.setRGB(x, y, 0xFFFFFF) }
        for (f in listOf("bob.glb", "boob_dragon.glb")) {
            val m = Glb.read(Glb.write(AutoRig3D.rig(ProgressiveMesh.build(Import3D.read(mapOf(f to asset(f).readBytes())).withNormals())).mesh)).withNormals()
            val S = 400; val sheet = BufferedImage(S * 3, S, BufferedImage.TYPE_INT_RGB)
            fun mean(a: IntArray) = a.filter { it != 0 }.map { (it shr 8) and 255 }.average()
            val full = render(m, white, m.vertexCount, S, true); val n = ProgressiveMesh.verticesForTriangles(m, 4751)
            twoSided = false; val oneS = render(m, white, n, S, true); twoSided = true; val twoS = render(m, white, n, S, true)
            listOf(full, oneS, twoS).forEachIndexed { i, a -> sheet.setRGB(i * S, 0, S, S, a, 0, S) }
            ImageIO.write(sheet, "png", File("build/pm_light_$f.png"))
            println("$f brightness full=${"%.1f".format(mean(full))} low one-sided=${"%.1f".format(mean(oneS))} low two-sided=${"%.1f".format(mean(twoS))}")
            assertTrue("$f two-sided low detail too dark", mean(twoS) > mean(full) - 12)
        }
    }

    /** Back-face culling on closed meshes vs the full-detail reference, textured and lit, at every level. */
    @Test fun cullingRemovesInnerShellSmears() {
        for ((name, f) in listOf("bob" to "bob.glb", "boob_dragon" to "boob_dragon.glb", "imported_bob_pixal3d" to "art/bob_pixal3d.glb")) {
            val file = listOf(File("../$f"), File(f), File("app/src/main/assets/default_avatars/$f"), File("src/main/assets/default_avatars/$f")).first { it.exists() }
            val m = Glb.read(Glb.write(AutoRig3D.rig(ProgressiveMesh.build(Import3D.read(mapOf(file.name to file.readBytes())).withNormals())).mesh)).withNormals()
            println("$name min triangles reachable: ${ProgressiveMesh.trim(m, ProgressiveMesh.verticesForTriangles(m, 1), IntArray(m.idx.size))} of ${m.triCount}")
            val o = Orientation.analyse(m); println("$name orientation mode=${o.mode} consistent=${"%.3f".format(o.manifoldConsistent)} volume=${"%.4g".format(o.volume)}")
            val t = texOf(m); val S = 400; val ref = render(m, t, m.vertexCount, S, true, 0)
            val levels = listOf(0.3f, 0.07f, 1500f / m.triCount)
            val sheet = BufferedImage(S * (1 + levels.size * 2), S, BufferedImage.TYPE_INT_RGB); sheet.setRGB(0, 0, S, S, ref, 0, S)
            fun err(a: IntArray): Double { var e = 0.0; var c = 0; for (q in a.indices) { if (ref[q] == 0) continue; c++
                if (a[q] == 0) { e += 128; continue }; for (sh in intArrayOf(0, 8, 16)) e += kotlin.math.abs(((a[q] shr sh) and 255) - ((ref[q] shr sh) and 255)) / 3.0 }; return e / maxOf(1, c) }
            for ((i, lv) in levels.withIndex()) {
                val n = ProgressiveMesh.verticesForTriangles(m, (m.triCount * lv).toInt().coerceAtLeast(1500))
                val two = render(m, t, n, S, true, 0); val cul = render(m, t, n, S, true, o.mode)
                sheet.setRGB(S * (1 + i * 2), 0, S, S, two, 0, S); sheet.setRGB(S * (2 + i * 2), 0, S, S, cul, 0, S)
                println("$name level ${"%.3f".format(lv)}: two-sided error=${"%.1f".format(err(two))} culled error=${"%.1f".format(err(cul))}")
            }
            ImageIO.write(sheet, "png", File("build/cull_$name.png"))
        }
    }

    @Test fun liveTrianglesStayInOneChart() {
        val m = ProgressiveMesh.build(Import3D.read(mapOf("bob.glb" to asset("bob.glb").readBytes())).withNormals())
        val V = m.vertexCount; val par = IntArray(V) { it }
        fun find(x: Int): Int { var r = x; while (par[r] != r) r = par[r]; var y = x; while (par[y] != r) { val n = par[y]; par[y] = r; y = n }; return r }
        for (t in 0 until m.triCount) { val a = find(m.idx[t * 3]); val b = find(m.idx[t * 3 + 1]); val c = find(m.idx[t * 3 + 2]); par[a] = b; par[find(c)] = find(b) }
        val n = ProgressiveMesh.verticesForTriangles(m, 1); val out = IntArray(m.idx.size); val k = ProgressiveMesh.trim(m, n, out)
        var cross = 0; var big = 0; val uv = m.uv!!
        for (t in 0 until k) { val a = out[t * 3]; val b = out[t * 3 + 1]; val c = out[t * 3 + 2]
            if (find(a) != find(b) || find(b) != find(c)) cross++
            val ua = kotlin.math.abs((uv[b*2]-uv[a*2])*(uv[c*2+1]-uv[a*2+1])-(uv[c*2]-uv[a*2])*(uv[b*2+1]-uv[a*2+1])); if (ua > 0.002f) big++ }
        println("floor: $k triangles, cross-chart=$cross, uvArea>0.002: $big")
        var bigFull = 0; for (t in 0 until m.triCount) { val a = m.idx[t*3]; val b = m.idx[t*3+1]; val c = m.idx[t*3+2]
            val ua = kotlin.math.abs((uv[b*2]-uv[a*2])*(uv[c*2+1]-uv[a*2+1])-(uv[c*2]-uv[a*2])*(uv[b*2+1]-uv[a*2+1])); if (ua > 0.002f) bigFull++ }
        println("full: uvArea>0.002: $bigFull")
        // write a UV-space picture of the floor level's big triangles over the texture
        val tex = ImageIO.read(m.texture!!.inputStream()); val g = tex.createGraphics(); g.color = java.awt.Color.GREEN
        for (t in 0 until k) { val a = out[t*3]; val b = out[t*3+1]; val c = out[t*3+2]
            val ua = kotlin.math.abs((uv[b*2]-uv[a*2])*(uv[c*2+1]-uv[a*2+1])-(uv[c*2]-uv[a*2])*(uv[b*2+1]-uv[a*2+1])); if (ua <= 0.002f) continue
            val xs = intArrayOf((uv[a*2]*tex.width).toInt(), (uv[b*2]*tex.width).toInt(), (uv[c*2]*tex.width).toInt()); val ys = intArrayOf((uv[a*2+1]*tex.height).toInt(), (uv[b*2+1]*tex.height).toInt(), (uv[c*2+1]*tex.height).toInt())
            g.drawPolygon(xs, ys, 3) }
        ImageIO.write(tex, "png", File("build/floor_uv.png"))
        assertTrue("triangles spanning charts: $cross", cross == 0)
    }

    /** The renderer never samples below the atlas-safe mip, so islands of fragmented atlases never smear together. */
    @Test fun safeMipKeepsIslandsApart() {
        for ((n, file) in listOf("bob" to "bob.glb", "boob_dragon" to "boob_dragon.glb", "imported_bob_pixal3d" to "art/bob_pixal3d.glb")) {
            val f = listOf(File("../$file"), File(file), File("app/src/main/assets/default_avatars/$file"), File("src/main/assets/default_avatars/$file")).first { it.exists() }
            val m = ProgressiveMesh.build(Glb.read(f.readBytes())); val t = texOf(m); val w = t.width; val h = t.height
            val px = t.getRGB(0, 0, w, h, null, 0, w); val used = TextureMips.coverage(w, h, m.uv!!, m.idx)
            val chain = TextureMips.build(w, h, px, used); val mix = TextureMips.levelErrors(chain, used); val safe = TextureMips.safeLevel(mix)
            val S = 400; val full = render(m, t, m.vertexCount, S)
            fun err(a: IntArray): Double { var e = 0.0; var c = 0; for (o in a.indices) { if (a[o] == 0 || full[o] == 0) continue; c++
                for (sh in intArrayOf(0, 8, 16)) e += kotlin.math.abs(((a[o] shr sh) and 255) - ((full[o] shr sh) and 255)) / 3.0 }; return e / maxOf(1, c) }
            val sheet = BufferedImage(S * 6, S, BufferedImage.TYPE_INT_RGB); val errs = ArrayList<Double>()
            for (lv in 0..5) { val r = render(m, img(chain[lv]), m.vertexCount, S); sheet.setRGB(S * lv, 0, S, S, r, 0, S); errs += err(r) }
            ImageIO.write(sheet, "png", File("build/safe_mip_$n.png"))
            println("$n ${w}x$h texelErrors=${mix.joinToString { "%.3f".format(it) }} safeLevel=$safe errors=${errs.joinToString { "%.1f".format(it) }}")
            assertTrue("$n error at safe level ${errs[safe]}", errs[safe] < 12.0)
        }
    }

    /** Specks: pixels that stand out from their neighbours at low detail where the full-detail render is smooth. */
    private fun speckCount(m: Mesh3D, t: BufferedImage, full: IntArray, n: Int, S: Int): Int {
        val low = render(m, t, n, S); var specks = 0
        fun lum(c: Int) = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
        val nb = IntArray(8)
        fun dev(img: IntArray, o: Int): Int { var k = 0
            for (q in intArrayOf(o - 1, o + 1, o - S, o + S, o - S - 1, o - S + 1, o + S - 1, o + S + 1)) { if (img[q] == 0) return 0; nb[k++] = lum(img[q]) }
            nb.sort(); return kotlin.math.abs(lum(img[o]) - (nb[3] + nb[4]) / 2) }
        for (y in 1 until S - 1) for (x in 1 until S - 1) { val o = y * S + x; if (low[o] == 0 || full[o] == 0) continue
            if (dev(low, o) > 45 && dev(full, o) < 20) specks++ }
        return specks
    }

    @Test fun specksPerLevel() {
        val names = (System.getProperty("specks.models") ?: "bob,boob_dragon,imported_bob_pixal3d").split(",")
        for ((name, f) in listOf("bob" to "bob.glb", "boob_dragon" to "boob_dragon.glb", "imported_bob_pixal3d" to "art/bob_pixal3d.glb").filter { it.first in names }) {
            val file = listOf(File("../$f"), File(f), File("app/src/main/assets/default_avatars/$f"), File("src/main/assets/default_avatars/$f")).first { it.exists() }
            for (cone in (System.getProperty("specks.cones") ?: "${ProgressiveMesh.NORMAL_CONE}").split(",").map { it.toDouble() }) {
            ProgressiveMesh.NORMAL_CONE = cone
            val m = ProgressiveMesh.build(Import3D.read(mapOf(file.name to file.readBytes())).withNormals())
            val t = texOf(m); val S = 512; val full = render(m, t, m.vertexCount, S)
            val floor = ProgressiveMesh.trim(m, ProgressiveMesh.verticesForTriangles(m, 1), IntArray(m.idx.size))
            val out = StringBuilder("cone=$cone $name ${m.triCount} tris, floor $floor:")
            run { val cl = ProgressiveMesh.lastCosts; val b = m.bounds(); val d = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]).toDouble()
                val sb = StringBuilder("  cost/d^4 by live tris:"); var next = m.triCount * 0.9
                // running max of cost
                var mx = 0f; for (i in 0 until cl.size / 2) { mx = maxOf(mx, cl[i * 2 + 1]); if (cl[i * 2] <= next) { sb.append(" ${cl[i * 2].toInt()}:${"%.2e".format(mx / (d * d * d * d))}"); next = cl[i * 2] * 0.85 } }
                println(sb) }
            for (want in listOf(m.triCount, 50000, 35000, 28000, 24000, 20000, 16000, floor).filter { it in floor..m.triCount }) {
                val n = ProgressiveMesh.verticesForTriangles(m, want); out.append(" ${want}=${speckCount(m, t, full, n, S)}") }
            println(out)
            val img = BufferedImage(S, S, BufferedImage.TYPE_INT_RGB); img.setRGB(0, 0, S, S, render(m, t, ProgressiveMesh.verticesForTriangles(m, 1), S), 0, S); ImageIO.write(img, "png", File("build/floor_${name}_$cone.png"))
            }
            ProgressiveMesh.NORMAL_CONE = 0.2
        }
    }

    /** Lowest slider level = the clean level: stored in the .glb, and on screen it shows no specks the full mesh doesn't. */
    @Test fun lowestLevelIsClean() {
        val C = 300; val models = listOf("bob" to "bob.glb", "boob_dragon" to "boob_dragon.glb", "imported_bob_pixal3d" to "art/bob_pixal3d.glb")
        val sheet = BufferedImage(C * 3, C * models.size, BufferedImage.TYPE_INT_RGB)
        for ((r, nf) in models.withIndex()) { val (name, f) = nf
            val file = listOf(File("../$f"), File(f), File("app/src/main/assets/default_avatars/$f"), File("src/main/assets/default_avatars/$f")).first { it.exists() }
            val m = Glb.read(Glb.write(ProgressiveMesh.build(Import3D.read(mapOf(file.name to file.readBytes())).withNormals()))); val t = texOf(m)
            val floor = ProgressiveMesh.trim(m, ProgressiveMesh.verticesForTriangles(m, 1), IntArray(m.idx.size))
            println("$name: ${m.triCount} triangles, clean level ${m.pmCleanTris}, absolute floor $floor")
            assertTrue("$name clean level stored", m.pmCleanTris in floor..m.triCount)
            val S = 512; val full = render(m, t, m.vertexCount, S)
            val sClean = speckCount(m, t, full, ProgressiveMesh.verticesForTriangles(m, m.pmCleanTris), S); val sFloor = speckCount(m, t, full, ProgressiveMesh.verticesForTriangles(m, floor), S)
            println("$name specks: clean level $sClean, absolute floor $sFloor")
            assertTrue("$name clean level has fewer specks", sClean < sFloor)
            // chest close-ups at the lowest render size (179 px): full, clean level, old floor
            for ((c, n) in listOf(m.triCount, m.pmCleanTris, floor).withIndex()) { val R = 179
                val img = BufferedImage(R, R, BufferedImage.TYPE_INT_RGB); img.setRGB(0, 0, R, R, render(m, t, ProgressiveMesh.verticesForTriangles(m, n), R), 0, R)
                val g = sheet.createGraphics(); g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                g.drawImage(img.getSubimage(R / 5, R * 2 / 5, R * 3 / 5, R * 3 / 5), c * C, r * C, C, C, null); g.color = java.awt.Color.WHITE
                g.drawString("$name ${listOf("full", "lowest (clean)", "old floor")[c]} $n", c * C + 4, r * C + 14); g.dispose() }
        }
        ImageIO.write(sheet, "png", File("build/lowest_level_clean.png"))
    }
}
