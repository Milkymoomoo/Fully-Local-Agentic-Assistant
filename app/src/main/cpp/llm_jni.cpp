#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else   // desktop build (desktop/native): same messages on stderr
#include <cstdio>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) (std::fprintf(stderr, "%s/%s: ", (prio) == ANDROID_LOG_ERROR ? "E" : "I", tag), std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif
#include <mutex>
#include "llm_core.h"

#define TAG "BelleLlm"

static std::mutex g_mu;

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_belle_assistant_llm_LlamaEngine_nativeLoad(JNIEnv *env, jobject, jstring path, jint threads, jint ctx) {
    std::lock_guard<std::mutex> lk(g_mu);
    auto *core = new LlmCore();
    if (!core->load(jstr(env, path), threads, ctx)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", core->last_error().c_str());
        delete core;
        return 0;
    }
    return reinterpret_cast<jlong>(core);
}

static std::string jbytes(JNIEnv *env, jbyteArray a) {
    if (!a) return "";
    jsize n = env->GetArrayLength(a);
    std::string r((size_t)n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&r[0]));
    return r;
}

// Strings cross as real UTF-8 bytes both ways: NewStringUTF/GetStringUTFChars use *modified* UTF-8, which breaks on
// emoji (4-byte) and on a multi-byte character cut by max_tokens — a reply must never be lost to that.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_belle_assistant_llm_LlamaEngine_nativeComplete(JNIEnv *env, jobject, jlong h, jbyteArray sys, jbyteArray user,
                                                       jbyteArray grammar, jint maxTokens) {
    std::lock_guard<std::mutex> lk(g_mu);
    auto *core = reinterpret_cast<LlmCore *>(h);
    if (!core) return env->NewByteArray(0);
    std::string out = core->complete(jbytes(env, sys), jbytes(env, user), jbytes(env, grammar), maxTokens);
    __android_log_print(ANDROID_LOG_INFO, TAG, "prompt %d tok (%d reused) %.0f ms, gen %d tok %.0f ms %s",
                        core->last_prompt_tokens, core->last_reused_tokens, core->last_prompt_ms,
                        core->last_gen_tokens, core->last_gen_ms, core->last_error().c_str());
    jbyteArray r = env->NewByteArray((jsize)out.size());
    if (!out.empty()) env->SetByteArrayRegion(r, 0, (jsize)out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return r;
}

// No lock on purpose: called from another thread while nativeComplete holds the mutex.
extern "C" JNIEXPORT void JNICALL
Java_app_belle_assistant_llm_LlamaEngine_nativeCancel(JNIEnv *, jobject, jlong h) {
    auto *core = reinterpret_cast<LlmCore *>(h);
    if (core) core->cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_app_belle_assistant_llm_LlamaEngine_nativeReset(JNIEnv *, jobject, jlong h) {
    auto *core = reinterpret_cast<LlmCore *>(h);
    if (core) core->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_app_belle_assistant_llm_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong h) {
    std::lock_guard<std::mutex> lk(g_mu);
    delete reinterpret_cast<LlmCore *>(h);
}

// Streams free text to sink.onText(byte[]) -> boolean (false stops); returns the whole text.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_belle_assistant_llm_LlamaEngine_nativeStream(JNIEnv *env, jobject, jlong h, jbyteArray sys, jbyteArray user,
                                                     jint maxTokens, jfloat temp, jdouble genCapMs, jobject sink) {
    std::lock_guard<std::mutex> lk(g_mu);
    auto *core = reinterpret_cast<LlmCore *>(h);
    if (!core) return env->NewByteArray(0);
    jclass cls = env->GetObjectClass(sink);
    jmethodID mid = env->GetMethodID(cls, "onText", "([B)Z");
    std::string out = core->generate(jbytes(env, sys), jbytes(env, user), "", maxTokens, temp, genCapMs,
        [&](const std::string &piece) -> bool {
            jbyteArray a = env->NewByteArray((jsize)piece.size());
            env->SetByteArrayRegion(a, 0, (jsize)piece.size(), reinterpret_cast<const jbyte *>(piece.data()));
            jboolean go = env->CallBooleanMethod(sink, mid, a);
            env->DeleteLocalRef(a);
            if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
            return go == JNI_TRUE;
        });
    __android_log_print(ANDROID_LOG_INFO, TAG, "stream: prompt %d tok (%d reused) %.0f ms, gen %d tok %.0f ms%s %s",
                        core->last_prompt_tokens, core->last_reused_tokens, core->last_prompt_ms,
                        core->last_gen_tokens, core->last_gen_ms, core->last_hit_limit ? " (hit limit)" : "", core->last_error().c_str());
    jbyteArray r = env->NewByteArray((jsize)out.size());
    if (!out.empty()) env->SetByteArrayRegion(r, 0, (jsize)out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return r;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_belle_assistant_llm_LlamaEngine_nativeHitLimit(JNIEnv *, jobject, jlong h) {
    auto *core = reinterpret_cast<LlmCore *>(h);
    return core && core->last_hit_limit ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_belle_assistant_llm_LlamaEngine_nativeGpuLayers(JNIEnv *, jobject, jlong h) {
    auto *core = reinterpret_cast<LlmCore *>(h);
    return core ? core->gpu_layers() : 0;
}
