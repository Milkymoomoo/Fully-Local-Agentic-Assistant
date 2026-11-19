/* Submanifold sparse 3x3x3 convolution for TRELLIS 2's shape/texture VAE decoders, written for phones.
 * Gather-GEMM-scatter in output-stationary tiles: for a tile of TR output voxels, for each of the 27
 * offsets, gather the neighbours' input rows (skipping offsets with no neighbour in the tile) and
 * accumulate acc[TR x Cout] += G[TR x Cin] * W_k[Cin x Cout]. No atomics, no N x 27 x Cin buffer:
 * memory is just inputs + outputs + weights + a per-thread tile. Weight layout matches the checkpoint
 * ([Cout, 3,3,3, Cin]) after a one-time transpose to [27][Cin][Cout].
 * NEON (aarch64) path uses vfmaq_f32; otherwise plain C that auto-vectorises (AVX2/SSE). */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#ifdef __aarch64__
#include <arm_neon.h>
#endif
#define TR 32
typedef struct { int32_t *key; int32_t *val; uint32_t mask; } Hash;
static uint32_t hk(int32_t x, int32_t y, int32_t z) { return (uint32_t)(x * 73856093) ^ (uint32_t)(y * 19349663) ^ (uint32_t)(z * 83492791); }
static int32_t pack(int x, int y, int z) { return (x << 20) | (y << 10) | z; }
static void hput(Hash *h, int x, int y, int z, int v) { uint32_t i = hk(x, y, z) & h->mask; int32_t k = pack(x, y, z); while (h->key[i] != -1) i = (i + 1) & h->mask; h->key[i] = k; h->val[i] = v; }
static int hget(const Hash *h, int x, int y, int z) { if (x < 0 || y < 0 || z < 0 || x > 1023 || y > 1023 || z > 1023) return -1; uint32_t i = hk(x, y, z) & h->mask; int32_t k = pack(x, y, z); while (h->key[i] != -1) { if (h->key[i] == k) return h->val[i]; i = (i + 1) & h->mask; } return -1; }

typedef struct { const int32_t *coords; int n, cin, cout; const float *in, *w, *bias; float *out; int32_t *nbr; int t0, t1; } Job;

static void axpy_row(float *restrict acc, const float *restrict w, float a, int n) {
#ifdef __aarch64__
    float32x4_t va = vdupq_n_f32(a); int j = 0;
    for (; j + 16 <= n; j += 16) {
        vst1q_f32(acc + j, vfmaq_f32(vld1q_f32(acc + j), vld1q_f32(w + j), va));
        vst1q_f32(acc + j + 4, vfmaq_f32(vld1q_f32(acc + j + 4), vld1q_f32(w + j + 4), va));
        vst1q_f32(acc + j + 8, vfmaq_f32(vld1q_f32(acc + j + 8), vld1q_f32(w + j + 8), va));
        vst1q_f32(acc + j + 12, vfmaq_f32(vld1q_f32(acc + j + 12), vld1q_f32(w + j + 12), va));
    }
    for (; j < n; j++) acc[j] += a * w[j];
#else
    for (int j = 0; j < n; j++) acc[j] += a * w[j];
#endif
}
/* acc[r][0..cout) += sum_c G[r][c] * W[c][..]; 4 rows at a time so each W row is loaded once per 4 rows */
static void gemm_tile(float *acc, const float *G, const float *W, int rows, int cin, int cout, int cb) {
    for (int c0 = 0; c0 < cout; c0 += cb) {
        int cn = cout - c0 < cb ? cout - c0 : cb;
        for (int r = 0; r < rows; r++) {
            float *a = acc + (size_t)r * cout + c0; const float *g = G + (size_t)r * cin;
            for (int c = 0; c < cin; c++) { float v = g[c]; if (v != 0.f) axpy_row(a, W + (size_t)c * cout + c0, v, cn); }
        }
    }
}
static void *worker(void *p) {
    Job *j = p; float *G = malloc(sizeof(float) * TR * j->cin); float *acc = malloc(sizeof(float) * TR * j->cout);
    int rows_idx[TR];
    for (int t = j->t0; t < j->t1; t += TR) {
        int rows = j->t1 - t < TR ? j->t1 - t : TR;
        for (int r = 0; r < rows; r++) memcpy(acc + (size_t)r * j->cout, j->bias, sizeof(float) * j->cout);
        for (int k = 0; k < 27; k++) {
            int any = 0;
            for (int r = 0; r < rows; r++) { rows_idx[r] = j->nbr[(size_t)(t + r) * 27 + k]; any |= rows_idx[r] >= 0; }
            if (!any) continue;
            /* compact: only rows that have this neighbour */
            int m = 0; int which[TR];
            for (int r = 0; r < rows; r++) if (rows_idx[r] >= 0) { memcpy(G + (size_t)m * j->cin, j->in + (size_t)rows_idx[r] * j->cin, sizeof(float) * j->cin); which[m++] = r; }
            float *tmp = malloc(sizeof(float) * m * j->cout); memset(tmp, 0, sizeof(float) * m * j->cout);
            gemm_tile(tmp, G, j->w + (size_t)k * j->cin * j->cout, m, j->cin, j->cout, 256);
            for (int q = 0; q < m; q++) { float *a = acc + (size_t)which[q] * j->cout, *s = tmp + (size_t)q * j->cout; for (int c = 0; c < j->cout; c++) a[c] += s[c]; }
            free(tmp);
        }
        memcpy(j->out + (size_t)t * j->cout, acc, sizeof(float) * rows * j->cout);
    }
    free(G); free(acc); return 0;
}
static double now(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec + ts.tv_nsec * 1e-9; }
static void *rd(const char *f, size_t *n) { FILE *fp = fopen(f, "rb"); if (!fp) { perror(f); exit(1); } fseek(fp, 0, SEEK_END); *n = ftell(fp); fseek(fp, 0, SEEK_SET); void *b = malloc(*n); if (fread(b, 1, *n, fp) != *n) exit(2); fclose(fp); return b; }

/* usage: spconv dir cin cout threads reps   (dir has coords.i32 [n,3], in.f32 [n,cin], w.f32 [27,cin,cout], bias.f32) → writes out.f32 */
int main(int argc, char **argv) {
    char f[512]; size_t sz; const char *d = argv[1]; int cin = atoi(argv[2]), cout = atoi(argv[3]), T = atoi(argv[4]), reps = atoi(argv[5]);
    snprintf(f, sizeof f, "%s/coords.i32", d); int32_t *co = rd(f, &sz); int n = sz / 12;
    snprintf(f, sizeof f, "%s/in.f32", d); float *in = rd(f, &sz);
    snprintf(f, sizeof f, "%s/w.f32", d); float *w = rd(f, &sz);
    snprintf(f, sizeof f, "%s/bias.f32", d); float *bias = rd(f, &sz);
    double t0 = now();
    Hash h; uint32_t cap = 1; while (cap < (uint32_t)n * 2) cap <<= 1; h.mask = cap - 1; h.key = malloc(4 * cap); h.val = malloc(4 * cap); memset(h.key, 0xff, 4 * cap);
    for (int i = 0; i < n; i++) hput(&h, co[i * 3], co[i * 3 + 1], co[i * 3 + 2], i);
    int32_t *nbr = malloc(sizeof(int32_t) * 27 * (size_t)n); long pairs = 0;
    for (int i = 0; i < n; i++) for (int k = 0; k < 27; k++) { int dx = k / 9 - 1, dy = (k / 3) % 3 - 1, dz = k % 3 - 1; int v = hget(&h, co[i * 3] + dx, co[i * 3 + 1] + dy, co[i * 3 + 2] + dz); nbr[(size_t)i * 27 + k] = v; pairs += v >= 0; }
    double tmap = now() - t0;
    float *out = malloc(sizeof(float) * (size_t)n * cout);
    pthread_t th[64]; Job jb[64]; double best = 1e9;
    for (int r = 0; r < reps; r++) {
        double t1 = now(); int per = ((n + T - 1) / T + TR - 1) / TR * TR;
        for (int i = 0; i < T; i++) { jb[i] = (Job){co, n, cin, cout, in, w, bias, out, nbr, i * per < n ? i * per : n, (i + 1) * per < n ? (i + 1) * per : n}; pthread_create(&th[i], 0, worker, &jb[i]); }
        for (int i = 0; i < T; i++) pthread_join(th[i], 0);
        double dt = now() - t1; if (dt < best) best = dt;
    }
    double gflop = 2.0 * pairs * cin * cout / 1e9;
    printf("{\"n\":%d,\"cin\":%d,\"cout\":%d,\"threads\":%d,\"pairs\":%ld,\"avg_neighbours\":%.2f,\"map_s\":%.4f,\"conv_s\":%.4f,\"gflop\":%.2f,\"gflops\":%.1f}\n", n, cin, cout, T, pairs, (double)pairs / n, tmap, best, gflop, gflop / best);
    snprintf(f, sizeof f, "%s/out.f32", d); FILE *fp = fopen(f, "wb"); fwrite(out, sizeof(float), (size_t)n * cout, fp); fclose(fp);
    return 0;
}
