import numpy as np, subprocess, json, sys, os
def shell(G, thick=0.6):
    """Surface voxels of a blobby object (sphere + torso-ish ellipsoid) on a G^3 grid, like a character's surface."""
    a = np.arange(G) + 0.5; x, y, z = np.meshgrid(a, a, a, indexing="ij"); c = G / 2
    f = np.minimum(np.sqrt(((x - c) / (0.30 * G))**2 + ((y - 0.65 * G) / (0.22 * G))**2 + ((z - c) / (0.25 * G))**2),
                   np.sqrt(((x - c) / (0.40 * G))**2 + ((y - 0.30 * G) / (0.28 * G))**2 + ((z - c) / (0.30 * G))**2))
    d = np.abs(f - 1) * G * 0.25
    return np.argwhere(d < thick).astype(np.int32)
def prep(d, co, cin, cout, seed=0):
    os.makedirs(d, exist_ok=True); r = np.random.default_rng(seed)
    co.tofile(f"{d}/coords.i32"); x = r.standard_normal((len(co), cin)).astype(np.float32); x.tofile(f"{d}/in.f32")
    w = (r.standard_normal((27, cin, cout)) / np.sqrt(27 * cin)).astype(np.float32); w.tofile(f"{d}/w.f32")
    b = r.standard_normal(cout).astype(np.float32); b.tofile(f"{d}/bias.f32"); return x, w, b
def ref(co, x, w, b):
    idx = {tuple(c): i for i, c in enumerate(co)}; out = np.tile(b, (len(co), 1))
    for k in range(27):
        dx, dy, dz = k // 9 - 1, (k // 3) % 3 - 1, k % 3 - 1
        pr = [(i, idx.get((c[0] + dx, c[1] + dy, c[2] + dz), -1)) for i, c in enumerate(co)]
        pr = np.array([p for p in pr if p[1] >= 0]);
        if len(pr): np.add.at(out, pr[:, 0], x[pr[:, 1]] @ w[k])
    return out
if __name__ != "__main__": pass
elif sys.argv[1] == "check":
    co = shell(24); x, w, b = prep("/tmp/spc_chk", co, 64, 48)
    print(subprocess.check_output(["./spconv_x86", "/tmp/spc_chk", "64", "48", "4", "1"]).decode().strip())
    y = np.fromfile("/tmp/spc_chk/out.f32", np.float32).reshape(len(co), 48); r = ref(co, x, w, b)
    print("voxels", len(co), "max abs err", float(np.abs(y - r).max()), "OK" if np.allclose(y, r, atol=1e-3) else "MISMATCH")
else:
    G, cin, cout, T = map(int, sys.argv[2:6]); co = shell(G); prep("/tmp/spc_b", co, cin, cout)
    print(subprocess.check_output(["./spconv_x86", "/tmp/spc_b", str(cin), str(cout), str(T), "2"]).decode().strip())
