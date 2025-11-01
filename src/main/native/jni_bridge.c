#include <jni.h>
#include <stdbool.h>
#include "tbprobe.h"
#include "julius_game_chessengine_syzygy_bridge_SyzygyBridge.h"

static const char *const INIT_EXCEPTION = "java/lang/RuntimeException";

static void throw_if_needed(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) {
        return;
    }
    jclass clazz = (*env)->FindClass(env, INIT_EXCEPTION);
    if (clazz != NULL) {
        (*env)->ThrowNew(env, clazz, message);
    }
}

JNIEXPORT jboolean JNICALL Java_julius_game_chessengine_syzygy_bridge_SyzygyBridge_init(JNIEnv *env, jclass clazz, jstring path) {
    (void)clazz;
    if (path == NULL) {
        throw_if_needed(env, "Syzygy path must not be null");
        return JNI_FALSE;
    }
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (c_path == NULL) {
        return JNI_FALSE;
    }
    bool ok = tb_init(c_path);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_julius_game_chessengine_syzygy_bridge_SyzygyBridge_getTBLargest(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (jint)TB_LARGEST;
}

JNIEXPORT jint JNICALL Java_julius_game_chessengine_syzygy_bridge_SyzygyBridge_probeWDL(JNIEnv *env, jclass clazz,
        jlong white, jlong black, jlong kings, jlong queens, jlong rooks, jlong bishops,
        jlong knights, jlong pawns, jint ep, jboolean turn) {
    (void)env;
    (void)clazz;
    return (jint)tb_probe_wdl((uint64_t)white, (uint64_t)black, (uint64_t)kings, (uint64_t)queens,
            (uint64_t)rooks, (uint64_t)bishops, (uint64_t)knights, (uint64_t)pawns,
            0U, 0U, (unsigned)ep, turn == JNI_TRUE);
}

JNIEXPORT jint JNICALL Java_julius_game_chessengine_syzygy_bridge_SyzygyBridge_probeDTZ(JNIEnv *env, jclass clazz,
        jlong white, jlong black, jlong kings, jlong queens, jlong rooks, jlong bishops,
        jlong knights, jlong pawns, jint rule50, jint ep, jboolean turn) {
    (void)env;
    (void)clazz;
    return (jint)tb_probe_root((uint64_t)white, (uint64_t)black, (uint64_t)kings, (uint64_t)queens,
            (uint64_t)rooks, (uint64_t)bishops, (uint64_t)knights, (uint64_t)pawns,
            (unsigned)rule50, 0U, (unsigned)ep, turn == JNI_TRUE, NULL);
}
