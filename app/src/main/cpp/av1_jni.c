// Tiny JNI bridge to dav1d (8-bit, bundled as a static lib) for AV1 pictures on phones without an AV1 decoder
// (many Android 10/11 devices): AVIF stills / animations and AV1 video backgrounds.
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <dav1d/dav1d.h>

typedef struct { Dav1dContext *c; } Ctx;

JNIEXPORT jlong JNICALL Java_app_belle_assistant_avatar_Av1_nOpen(JNIEnv *e, jclass k) {
    Dav1dSettings s; dav1d_default_settings(&s);
    s.n_threads = 2; s.max_frame_delay = 1; s.logger.callback = NULL;
    Ctx *x = calloc(1, sizeof(Ctx));
    if (dav1d_open(&x->c, &s) < 0) { free(x); return 0; }
    return (jlong)(intptr_t)x;
}

JNIEXPORT void JNICALL Java_app_belle_assistant_avatar_Av1_nClose(JNIEnv *e, jclass k, jlong h) {
    Ctx *x = (Ctx *)(intptr_t)h; if (!x) return; dav1d_close(&x->c); free(x);
}

static inline int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

// picture -> int[] { w, h, argb... }, downscaled by an integer step so the long side is <= maxPx
static jintArray toArgb(JNIEnv *e, Dav1dPicture *p, int maxPx) {
    int w = p->p.w, h = p->p.h, step = 1;
    while ((w > h ? w : h) / step > maxPx) step++;
    int ow = w / step, oh = h / step; if (ow < 1) ow = 1; if (oh < 1) oh = 1;
    jintArray out = (*e)->NewIntArray(e, 2 + ow * oh); if (!out) return NULL;
    jint *px = malloc(sizeof(jint) * (2 + ow * oh)); px[0] = ow; px[1] = oh;
    int full = p->seq_hdr ? p->seq_hdr->color_range : 0;
    int bt709 = p->seq_hdr && p->seq_hdr->mtrx == DAV1D_MC_BT709;
    // fixed point (x1024) YCbCr -> RGB
    int ky = full ? 1024 : 1192, off = full ? 0 : 16;
    int rv = bt709 ? (full ? 1613 : 1836) : (full ? 1436 : 1634);
    int gu = bt709 ? (full ? 192 : 218) : (full ? 352 : 401);
    int gv = bt709 ? (full ? 479 : 546) : (full ? 731 : 833);
    int bu = bt709 ? (full ? 1900 : 2163) : (full ? 1815 : 2066);
    const uint8_t *Y = p->data[0], *U = p->data[1], *V = p->data[2];
    ptrdiff_t ys = p->stride[0], cs = p->stride[1];
    int sx = p->p.layout == DAV1D_PIXEL_LAYOUT_I444 ? 0 : 1, sy = p->p.layout == DAV1D_PIXEL_LAYOUT_I420 ? 1 : 0;
    int mono = p->p.layout == DAV1D_PIXEL_LAYOUT_I400;
    for (int y = 0; y < oh; y++) {
        int yy = y * step;
        for (int x = 0; x < ow; x++) {
            int xx = x * step;
            int Yv = (Y[yy * ys + xx] - off) * ky;
            int u = 0, v = 0;
            if (!mono) { u = U[(yy >> sy) * cs + (xx >> sx)] - 128; v = V[(yy >> sy) * cs + (xx >> sx)] - 128; }
            int r = (Yv + rv * v) >> 10, g = (Yv - gu * u - gv * v) >> 10, b = (Yv + bu * u) >> 10;
            px[2 + y * ow + x] = (jint)(0xFF000000u | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
        }
    }
    (*e)->SetIntArrayRegion(e, out, 0, 2 + ow * oh, px); free(px);
    return out;
}

// Feed one temporal unit (NULL = end of stream: drain). Returns every picture that came out (int[] each, maybe none).
JNIEXPORT jobjectArray JNICALL Java_app_belle_assistant_avatar_Av1_nDecode(JNIEnv *e, jclass k, jlong h, jbyteArray data, jint maxPx) {
    Ctx *x = (Ctx *)(intptr_t)h; if (!x) return NULL;
    jintArray got[64]; int n = 0;
    Dav1dPicture p;
    if (data) {
        jsize len = (*e)->GetArrayLength(e, data);
        Dav1dData d; uint8_t *buf = dav1d_data_create(&d, len); if (!buf) return NULL;
        (*e)->GetByteArrayRegion(e, data, 0, len, (jbyte *)buf);
        while (d.sz > 0) {
            int r = dav1d_send_data(x->c, &d);
            if (r == DAV1D_ERR(EAGAIN)) {
                memset(&p, 0, sizeof p);
                int g = dav1d_get_picture(x->c, &p);
                if (g == 0) { if (n < 64) got[n++] = toArgb(e, &p, maxPx); dav1d_picture_unref(&p); }
                else if (g != DAV1D_ERR(EAGAIN)) break;
            } else if (r < 0) break;
        }
        if (d.sz > 0) dav1d_data_unref(&d);
    }
    for (;;) {                                           // whatever is ready now (all of it when draining)
        memset(&p, 0, sizeof p);
        if (dav1d_get_picture(x->c, &p) != 0) break;
        if (n < 64) got[n++] = toArgb(e, &p, maxPx); dav1d_picture_unref(&p);
    }
    jclass ia = (*e)->FindClass(e, "[I");
    jobjectArray out = (*e)->NewObjectArray(e, n, ia, NULL);
    for (int i = 0; i < n; i++) { (*e)->SetObjectArrayElement(e, out, i, got[i]); (*e)->DeleteLocalRef(e, got[i]); }
    return out;
}

JNIEXPORT jstring JNICALL Java_app_belle_assistant_avatar_Av1_nVersion(JNIEnv *e, jclass k) { return (*e)->NewStringUTF(e, dav1d_version()); }
