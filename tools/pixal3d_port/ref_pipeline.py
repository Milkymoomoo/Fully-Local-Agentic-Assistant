"""Reference Pixal3D pipeline on the box CPU through ComfyUI's own node code (official implementation), stage by
stage, saving every intermediate tensor so the phone port can be checked against it, plus time/peak memory."""
from common import *
import time, json, numpy as np, pickle
from PIL import Image
import comfy.model_management as mm
torch.set_grad_enabled(False)
STAGES = os.environ.get("STAGES", "pre,ss").split(",")
OUT = "/workspace/port3d/ref"; os.makedirs(OUT, exist_ok=True)
log = {}
def mark(name, t0):
    log[name] = {"s": round(time.time() - t0, 2), "peak_rss_gb": round(rss_gb(), 2)}; print(name, log[name], flush=True)
    json.dump(log, open(f"{OUT}/timings_{'_'.join(STAGES)}.json", "w"), indent=1)
def out(node_cls, **kw):
    r = node_cls.execute(**kw); return r.result if hasattr(r, "result") else r
if "pre" in STAGES:
    from comfy_extras.nodes_bg_removal import LoadBackgroundRemovalModel, RemoveBackground
    from comfy_extras.nodes_images import ImageCropToMask
    from comfy_extras.nodes_moge import LoadMoGeModel, MoGeInference, MoGeGeometryToFOV
    img = torch.from_numpy(np.asarray(Image.open(os.environ.get("IMG", "/workspace/android_assistant/art/dragoness.png")).convert("RGB")).astype(np.float32) / 255)[None]
    # mask: the same U2-Net-P matting the app bundles (ComfyUI's BiRefNet path needs >9.5 GB here, see README)
    import onnxruntime as ort
    t = time.time(); sess = ort.InferenceSession("/workspace/matting/u2netp.onnx")
    pil = Image.fromarray((img[0].numpy() * 255).astype(np.uint8))
    x = (np.asarray(pil.resize((320, 320), Image.BILINEAR), np.float32) / 255 - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    r = sess.run(None, {sess.get_inputs()[0].name: x.transpose(2, 0, 1)[None].astype(np.float32)})[0][0, 0]
    r = (r - r.min()) / max(r.max() - r.min(), 1e-6)
    r = np.asarray(Image.fromarray((r * 255).astype(np.uint8)).resize(pil.size, Image.BILINEAR), np.float32) / 255
    r = np.clip((r - 0.25) / 0.5, 0, 1); r = r * r * (3 - 2 * r)   # the app's smoothstep
    mask = torch.from_numpy(r)[None]; mark("matting_u2netp", t)
    crop = out(ImageCropToMask, images=img, masks=mask, width=1024, height=1024, pad_factor=1.1, grow_mask=0, background="#000000")[0]
    Image.fromarray((crop[0].numpy() * 255).astype(np.uint8)).save(f"{OUT}/crop.png"); np.save(f"{OUT}/mask.npy", mask.numpy())
    fov = float(os.environ.get("FOV", "0"))
    if fov > 0: torch.save({"crop": crop, "fov": fov}, f"{OUT}/pre.pt"); print("fixed fov", fov)
    if fov <= 0:
     t = time.time(); mg = out(LoadMoGeModel, model_name="moge_2_vitl_normal_fp16.safetensors")[0]
     geo = out(MoGeInference, moge_model=mg, image=crop, resolution_level=9, fov_x_degrees=0.0, batch_size=4, force_projection=True, apply_mask=True, refine_steps=3)[0]
     fov = out(MoGeGeometryToFOV, moge_geometry=geo, axis="horizontal", unit="degrees")[0]; mark("moge", t); print("fov", fov)
     del mg, geo; mm.soft_empty_cache()
     torch.save({"crop": crop, "fov": float(fov)}, f"{OUT}/pre.pt")
if "cond" in STAGES:
    import comfy.clip_vision
    from comfy_extras.nodes_trellis2 import Pixal3DConditioning
    pre = torch.load(f"{OUT}/pre.pt")
    t = time.time(); cv = comfy.clip_vision.load("/workspace/models3d/dino_v3_L_naf_fp32.safetensors")
    pos, neg = out(Pixal3DConditioning, clip_vision_model=cv, image=pre["crop"], camera_angle_x=pre["fov"]); mark("dinov3+naf", t)
    pk = pos[0][1]["proj_feat_pack"]; st = pk["stages"]
    # the 1024x1024x1024 texture map is 4 GB; keep a strided sample of it for checking the on-demand NAF, drop the rest
    torch.save({"hr_tex_1024_s37": st["tex_1024"]["feature_map_hr"][:, :, ::37, ::37].clone(),
                "hr_shape_512_s7": st["shape_512"]["feature_map_hr"][:, :, ::7, ::7].clone(),
                "hr_shape_1024_s7": st["shape_1024"]["feature_map_hr"][:, :, ::7, ::7].clone(),
                "T": pk["transform_matrix"], "cam": pk["camera_angle_x"], "fov": pre["fov"]}, f"{OUT}/naf_check.pt")
    st["tex_1024"]["feature_map_hr"] = None
    torch.save({"pos": pos, "neg": neg}, f"{OUT}/cond.pt")

# ---- sampling stages (Pixal3D branch of the official ComfyUI workflow; mobile path stops at the 512 shape) ----
import nodes as N
from comfy_extras import nodes_model_advanced as MA, nodes_custom_sampler as CS, nodes_trellis2 as T2
def cap(module, name, n=1):
    """Saves the first n forward calls of a submodule (inputs + output) for checking the port one call at a time."""
    calls = []
    def hook(m, args, kwargs, out):
        if len(calls) < n:
            calls.append({"args": [a.detach().clone() if torch.is_tensor(a) else a for a in args],
                          "kwargs": {k: (v.detach().clone() if torch.is_tensor(v) else v) for k, v in kwargs.items() if k != "transformer_options"},
                          "out": out.feats.detach().clone() if hasattr(out, "feats") else out.detach().clone(),
                          "coords": args[0].coords.detach().clone() if hasattr(args[0], "coords") else None,
                          "x_feats": args[0].feats.detach().clone() if hasattr(args[0], "feats") else None})
            torch.save(calls, f"{OUT}/calls_{name}.pt")
    return module.register_forward_hook(hook, with_kwargs=True)
def unet():
    return N.UNETLoader().load_unet("pixal3d_int8_convrot.safetensors", "default")[0]
def shape_vae(): return N.VAELoader().load_vae("trellis_2_shape_vae_bf16.safetensors")[0]
if "ss" in STAGES:
    c = torch.load(f"{OUT}/cond.pt"); pos, neg = c["pos"], c["neg"]
    m0 = unet()
    m = out(CS.CFGOverride, model=m0, cfg=1.0, start_percent=0.667, end_percent=1.0)[0]
    m = MA.RescaleCFG().patch(m, 0.7)[0]; m = MA.ModelSamplingSD3().patch(m, 5.0)[0]
    h = cap(m0.model.diffusion_model.structure_model, "ss", 2)
    lat = out(T2.EmptyTrellis2LatentStructure, batch_size=1)[0]
    t = time.time(); s = N.KSampler().sample(m, 56, int(os.environ.get("SS_STEPS", 12)), 7.5, "euler", "normal", pos, neg, lat)[0]; mark("ss_flow", t)
    torch.save(s, f"{OUT}/ss_latent.pt")
if "struct" in STAGES:
    s = torch.load(f"{OUT}/ss_latent.pt"); v = shape_vae()
    t = time.time(); vox = out(T2.VaeDecodeStructureTrellis2, samples=s, vae=v, resolution="32")[0]; mark("struct_dec", t)
    torch.save(vox.data, f"{OUT}/voxel32.pt"); print("occupied", int(vox.data.sum()))
    np.save(f"{OUT}/ss_latent8.npy", s["samples"][:, :8].float().numpy())
if "shape" in STAGES:
    # CPU fp16 back-projection of the high-res NAF maps overflows to inf/NaN (whole shape512 latent was NaN);
    # do the projection in fp32 like the phone port does.
    import comfy.ldm.trellis2.model as _TM
    _sel = _TM._select_stage_entry
    def _sel32(*a, **k):
        lr, hr, res = _sel(*a, **k)
        f = lambda t: t.float() if torch.is_tensor(t) else t
        return f(lr), f(hr), res
    _TM._select_stage_entry = _sel32
    c = torch.load(f"{OUT}/cond.pt"); pos, neg = c["pos"], c["neg"]
    from comfy_api.latest import Types
    vox = Types.VOXEL(torch.load(f"{OUT}/voxel32.pt"))
    p2, n2, lat = out(T2.Trellis2ShapeStage, positive=pos, negative=neg, voxel=vox)
    m0 = unet()
    m = out(CS.CFGOverride, model=m0, cfg=1.0, start_percent=0.769, end_percent=1.0)[0]
    m = MA.RescaleCFG().patch(m, 0.5)[0]
    h = cap(m0.model.diffusion_model.img2shape_512, "shape512", 2)
    t = time.time(); s = N.KSampler().sample(m, 42, int(os.environ.get("SHAPE_STEPS", 20)), 7.5, "euler", "normal", p2, n2, lat)[0]; mark("shape512_flow", t)
    torch.save(s, f"{OUT}/shape512_latent.pt")
    _l = s["samples"] if isinstance(s, dict) else s
    print("shape512 latent nan", bool(torch.isnan(_l.float()).any()), "absmax", float(_l.float().nan_to_num().abs().max()), flush=True)
if "shapedec" in STAGES:
    s = torch.load(f"{OUT}/shape512_latent.pt"); v = shape_vae()
    t = time.time(); mesh, subs = out(T2.VaeDecodeShapeTrellis, samples=s, vae=v); mark("shape_dec512", t)
    torch.save({"v": mesh.vertices, "f": mesh.faces}, f"{OUT}/mesh512.pt")
    with open(f"{OUT}/mesh512.obj", "w") as fo:
        for p in mesh.vertices[0].tolist(): fo.write("v %.5f %.5f %.5f\n" % tuple(p))
        for f in (mesh.faces[0] + 1).tolist(): fo.write("f %d %d %d\n" % tuple(f))
    print("verts", mesh.vertices.shape, "faces", mesh.faces.shape)
