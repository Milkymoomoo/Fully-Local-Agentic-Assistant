// Minimal generic ONNX Runtime runner for the on-phone 3D stages (BiRefNet, DINOv3, MoGe, ...).
// Uses the libonnxruntime.so that sherpa-onnx already ships (ORT 1.28.2), looked up with dlopen/dlsym,
// so the app carries one runtime only (a second, newer libonnxruntime.so can't coexist: same soname,
// different symbol versions).
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <malloc.h>
#include <string.h>
#include <android/log.h>
#include "onnxruntime_c_api.h"

#define TAG "BelleOrt"
static const OrtApi *api = NULL;
static OrtEnv *env = NULL;
static char last_err[512];

typedef struct { OrtSession *s; OrtSessionOptions *o; } Sess;

static int ok(OrtStatus *st) {
    if (!st) return 1;
    snprintf(last_err, sizeof last_err, "%s", api->GetErrorMessage(st));
    __android_log_print(ANDROID_LOG_WARN, TAG, "%s", last_err);
    api->ReleaseStatus(st); return 0;
}

static int init(void) {
    if (api) return 1;
    void *h = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) { snprintf(last_err, sizeof last_err, "dlopen: %s", dlerror()); return 0; }
    const OrtApiBase *(*get)(void) = (const OrtApiBase *(*)(void)) dlsym(h, "OrtGetApiBase");
    if (!get) { snprintf(last_err, sizeof last_err, "no OrtGetApiBase"); return 0; }
    api = get()->GetApi(ORT_API_VERSION);
    if (!api) { snprintf(last_err, sizeof last_err, "ORT API %d not supported by %s", ORT_API_VERSION, get()->GetVersionString()); return 0; }
    if (!ok(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "belle3d", &env))) { api = NULL; return 0; }
    __android_log_print(ANDROID_LOG_INFO, TAG, "onnxruntime %s", get()->GetVersionString());
    return 1;
}

JNIEXPORT jstring JNICALL Java_app_belle_assistant_threed_Ort_lastError(JNIEnv *e, jclass c) { return (*e)->NewStringUTF(e, last_err); }

JNIEXPORT jlong JNICALL Java_app_belle_assistant_threed_Ort_open(JNIEnv *e, jclass c, jstring path, jint threads, jboolean arena) {
    if (!init()) return 0;
    Sess *s = calloc(1, sizeof(Sess));
    const char *p = (*e)->GetStringUTFChars(e, path, NULL);
    int good = ok(api->CreateSessionOptions(&s->o))
        && ok(api->SetIntraOpNumThreads(s->o, threads))
        && ok(api->SetSessionGraphOptimizationLevel(s->o, ORT_ENABLE_ALL))
        // no busy-waiting between ops: saves battery on phones and stops idle pool threads starving the busy ones
        && ok(api->AddSessionConfigEntry(s->o, "session.intra_op.allow_spinning", "0"))
        && ok(api->AddSessionConfigEntry(s->o, "session.inter_op.allow_spinning", "0"))
        && (arena || ok(api->DisableCpuMemArena(s->o)))
        && ok(api->CreateSession(env, p, s->o, &s->s));
    (*e)->ReleaseStringUTFChars(e, path, p);
    if (!good) { if (s->o) api->ReleaseSessionOptions(s->o); free(s); return 0; }
    return (jlong) (intptr_t) s;
}

/** Hands freed heap memory back to the system (the allocator otherwise keeps a closed model's ~1-2 GB mapped). */
static void trim_heap(void) {
#if defined(__ANDROID__) && defined(M_PURGE_ALL)
    mallopt(M_PURGE_ALL, 0);
#elif defined(__ANDROID__) && defined(M_PURGE)
    mallopt(M_PURGE, 0);
#elif !defined(__ANDROID__)
    malloc_trim(0);
#endif
}

JNIEXPORT void JNICALL Java_app_belle_assistant_threed_Ort_close(JNIEnv *e, jclass c, jlong h) {
    Sess *s = (Sess *) (intptr_t) h; if (!s) return;
    api->ReleaseSession(s->s); api->ReleaseSessionOptions(s->o); free(s);
    trim_heap();
}

JNIEXPORT void JNICALL Java_app_belle_assistant_threed_Ort_trim(JNIEnv *e, jclass c) { trim_heap(); }

/** Names of inputs (io=0) or outputs (io=1). */
JNIEXPORT jobjectArray JNICALL Java_app_belle_assistant_threed_Ort_names(JNIEnv *e, jclass c, jlong h, jint io) {
    Sess *s = (Sess *) (intptr_t) h; size_t n = 0; OrtAllocator *al; api->GetAllocatorWithDefaultOptions(&al);
    if (io == 0) api->SessionGetInputCount(s->s, &n); else api->SessionGetOutputCount(s->s, &n);
    jobjectArray arr = (*e)->NewObjectArray(e, (jsize) n, (*e)->FindClass(e, "java/lang/String"), NULL);
    for (size_t i = 0; i < n; i++) {
        char *nm = NULL;
        if (io == 0) api->SessionGetInputName(s->s, i, al, &nm); else api->SessionGetOutputName(s->s, i, al, &nm);
        (*e)->SetObjectArrayElement(e, arr, (jsize) i, (*e)->NewStringUTF(e, nm)); api->AllocatorFree(al, nm);
    }
    return arr;
}

/**
 * Runs with inputs that are float[] / long[] (Java heap, pinned) or direct ByteBuffers (native memory; inTypes[i]:
 * 0 = float32, 1 = int64). Writes each output's shape into outShapes[i] (a long[8], unused dims = -1) and returns,
 * per output (float32 only), a float[] (outNative false) or a direct ByteBuffer over malloc'd memory (outNative true;
 * the caller frees it with NativeMem.free): big outputs must not land on the Java heap, which Android caps at
 * 256-512 MB per app however much RAM the phone has.
 * On any failure (ORT error, or no memory for a result) everything is released and NULL is returned; a Java
 * OutOfMemoryError may then be pending, which the caller sees as a normal exception (no JNI call runs after it).
 */
JNIEXPORT jobjectArray JNICALL Java_app_belle_assistant_threed_Ort_run2(JNIEnv *e, jclass c, jlong h, jobjectArray inNames,
        jobjectArray inData, jintArray inTypes, jobjectArray inShapes, jobjectArray outNames, jobjectArray outShapes, jboolean outNative) {
    Sess *s = (Sess *) (intptr_t) h;
    jsize ni = (*e)->GetArrayLength(e, inNames), no = (*e)->GetArrayLength(e, outNames);
    OrtMemoryInfo *mi; api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mi);
    const char **in_n = calloc(ni, sizeof(char *)), **out_n = calloc(no, sizeof(char *));
    jstring *in_js = calloc(ni, sizeof(jstring)), *out_js = calloc(no, sizeof(jstring));
    OrtValue **in_v = calloc(ni, sizeof(OrtValue *)), **out_v = calloc(no, sizeof(OrtValue *));
    void **pinned = calloc(ni, sizeof(void *)); jarray *arrs = calloc(ni, sizeof(jarray)); int *kind = calloc(ni, sizeof(int));
    jint *types = (*e)->GetIntArrayElements(e, inTypes, NULL);
    jclass floatArr = (*e)->FindClass(e, "[F"), longArr = (*e)->FindClass(e, "[J");
    jobjectArray result = NULL; int good = 1;
    for (jsize i = 0; i < ni && good; i++) {
        in_js[i] = (*e)->GetObjectArrayElement(e, inNames, i); in_n[i] = (*e)->GetStringUTFChars(e, in_js[i], NULL);
        jlongArray shp = (*e)->GetObjectArrayElement(e, inShapes, i); jsize nd = (*e)->GetArrayLength(e, shp);
        int64_t dims[8]; jlong *sd = (*e)->GetLongArrayElements(e, shp, NULL); size_t count = 1;
        for (jsize d = 0; d < nd && d < 8; d++) { dims[d] = sd[d]; count *= (size_t) sd[d]; }
        (*e)->ReleaseLongArrayElements(e, shp, sd, JNI_ABORT); (*e)->DeleteLocalRef(e, shp);
        arrs[i] = (*e)->GetObjectArrayElement(e, inData, i);
        int isF; void *data;
        if ((*e)->IsInstanceOf(e, arrs[i], floatArr)) { kind[i] = 1; isF = 1; data = pinned[i] = (*e)->GetFloatArrayElements(e, arrs[i], NULL); }
        else if ((*e)->IsInstanceOf(e, arrs[i], longArr)) { kind[i] = 2; isF = 0; data = pinned[i] = (*e)->GetLongArrayElements(e, arrs[i], NULL); }
        else { kind[i] = 3; isF = types[i] == 0; data = (*e)->GetDirectBufferAddress(e, arrs[i]);
            jlong cap = (*e)->GetDirectBufferCapacity(e, arrs[i]);
            if (data && cap >= 0 && (size_t) cap < count * (isF ? 4 : 8)) { snprintf(last_err, sizeof last_err, "input %s: buffer too small", in_n[i]); good = 0; break; } }
        if (!data) { snprintf(last_err, sizeof last_err, "input %s: no memory", in_n[i]); good = 0; break; }
        good = ok(api->CreateTensorWithDataAsOrtValue(mi, data, count * (isF ? 4 : 8), dims, nd,
            isF ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT : ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &in_v[i]));
    }
    for (jsize i = 0; i < no && good; i++) { out_js[i] = (*e)->GetObjectArrayElement(e, outNames, i); out_n[i] = (*e)->GetStringUTFChars(e, out_js[i], NULL); }
    if (good) good = ok(api->Run(s->s, NULL, in_n, (const OrtValue *const *) in_v, ni, out_n, no, out_v));
    // the inputs are no longer needed: release them before the results are copied out (less at the peak)
    for (jsize i = 0; i < ni; i++) { if (in_v[i]) { api->ReleaseValue(in_v[i]); in_v[i] = NULL; } }
    if (good) result = (*e)->NewObjectArray(e, no, outNative ? (*e)->FindClass(e, "java/nio/ByteBuffer") : floatArr, NULL);
    if (good && !result) good = 0;
    for (jsize i = 0; i < no && good; i++) {
        OrtTensorTypeAndShapeInfo *ti; api->GetTensorTypeAndShape(out_v[i], &ti);
        size_t nd = 0, count = 0; api->GetDimensionsCount(ti, &nd); api->GetTensorShapeElementCount(ti, &count);
        int64_t dims[8] = {0}; api->GetDimensions(ti, dims, nd > 8 ? 8 : nd); api->ReleaseTensorTypeAndShapeInfo(ti);
        jlong js[8]; for (int d = 0; d < 8; d++) js[d] = d < (int) nd ? dims[d] : -1;
        jobject shp = (*e)->GetObjectArrayElement(e, outShapes, i);
        (*e)->SetLongArrayRegion(e, shp, 0, 8, js); (*e)->DeleteLocalRef(e, shp);
        float *data; api->GetTensorMutableData(out_v[i], (void **) &data);
        jobject o = NULL;
        if (outNative) {
            void *m = count ? malloc(count * 4) : malloc(4);
            if (m) { if (count) memcpy(m, data, count * 4); o = (*e)->NewDirectByteBuffer(e, m, (jlong) (count * 4)); if (!o) free(m); }
            else snprintf(last_err, sizeof last_err, "no native memory for output %s (%zu floats)", out_n[i], count);
        } else {
            o = (*e)->NewFloatArray(e, (jsize) count);
            if (o) (*e)->SetFloatArrayRegion(e, o, 0, (jsize) count, data);
            else snprintf(last_err, sizeof last_err, "no Java heap for output %s (%zu floats)", out_n[i], count);
        }
        api->ReleaseValue(out_v[i]); out_v[i] = NULL;
        if (!o) { good = 0; break; }   // an OutOfMemoryError may be pending: only release calls from here on
        (*e)->SetObjectArrayElement(e, result, i, o); (*e)->DeleteLocalRef(e, o);
    }
    if (!good && result && outNative) {   // free the native results already made
        if (!(*e)->ExceptionCheck(e)) for (jsize i = 0; i < no; i++) { jobject o = (*e)->GetObjectArrayElement(e, result, i);
            if (o) { free((*e)->GetDirectBufferAddress(e, o)); (*e)->DeleteLocalRef(e, o); } }
    }
    for (jsize i = 0; i < no; i++) if (out_v[i]) api->ReleaseValue(out_v[i]);
    for (jsize i = 0; i < ni; i++) {
        if (pinned[i]) {
            if (kind[i] == 1) (*e)->ReleaseFloatArrayElements(e, arrs[i], pinned[i], JNI_ABORT);
            else if (kind[i] == 2) (*e)->ReleaseLongArrayElements(e, arrs[i], pinned[i], JNI_ABORT);
        }
        if (in_n[i]) (*e)->ReleaseStringUTFChars(e, in_js[i], in_n[i]);
    }
    for (jsize i = 0; i < no; i++) if (out_n[i]) (*e)->ReleaseStringUTFChars(e, out_js[i], out_n[i]);
    (*e)->ReleaseIntArrayElements(e, inTypes, types, JNI_ABORT);
    api->ReleaseMemoryInfo(mi); free(in_n); free(out_n); free(in_js); free(out_js); free(in_v); free(out_v); free(pinned); free(arrs); free(kind);
    return good ? result : NULL;
}

/** Native memory for big arrays (NativeMem.kt): zeroed, outside the Java heap, freed explicitly. */
JNIEXPORT jobject JNICALL Java_app_belle_assistant_threed_NativeMem_alloc(JNIEnv *e, jclass c, jlong bytes) {
    void *m = calloc(1, bytes > 0 ? (size_t) bytes : 1);
    if (!m) return NULL;
    jobject b = (*e)->NewDirectByteBuffer(e, m, bytes);
    if (!b) free(m);
    return b;
}
JNIEXPORT void JNICALL Java_app_belle_assistant_threed_NativeMem_free(JNIEnv *e, jclass c, jobject buf) {
    if (buf) free((*e)->GetDirectBufferAddress(e, buf));
}
