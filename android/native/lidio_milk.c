// LiDio Milkdrop (card ef3a3dfb): a thin JNI layer over libprojectM 4 (LGPL-2.1). Everything runs on the GL thread of the
// view that draws: create, size, presets, PCM in, one frame out.
#include <jni.h>
#include <stdlib.h>
#include <projectM-4/projectM.h>

#define F(name) Java_io_github_veritasx1_lidio_Milk_##name

JNIEXPORT jlong JNICALL F(nCreate)(JNIEnv *env, jclass c) {
    projectm_handle pm = projectm_create();
    if (!pm) return 0;
    projectm_set_mesh_size(pm, 48, 32);
    projectm_set_fps(pm, 60);
    projectm_set_aspect_correction(pm, true);
    projectm_set_preset_duration(pm, 1e9);     // LiDio changes presets itself
    projectm_set_soft_cut_duration(pm, 3.0);
    projectm_set_beat_sensitivity(pm, 1.0f);
    return (jlong)(intptr_t)pm;
}

JNIEXPORT void JNICALL F(nSize)(JNIEnv *env, jclass c, jlong h, jint w, jint hgt) {
    if (h) projectm_set_window_size((projectm_handle)(intptr_t)h, (size_t)w, (size_t)hgt);
}

JNIEXPORT void JNICALL F(nPreset)(JNIEnv *env, jclass c, jlong h, jstring path, jboolean smooth) {
    if (!h) return;
    const char *p = (*env)->GetStringUTFChars(env, path, 0);
    projectm_load_preset_file((projectm_handle)(intptr_t)h, p, smooth);
    (*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT void JNICALL F(nPcm)(JNIEnv *env, jclass c, jlong h, jfloatArray samples, jint count) {
    if (!h || count <= 0) return;
    jfloat *s = (*env)->GetFloatArrayElements(env, samples, 0);
    projectm_pcm_add_float((projectm_handle)(intptr_t)h, s, (unsigned int)count, PROJECTM_MONO);
    (*env)->ReleaseFloatArrayElements(env, samples, s, JNI_ABORT);
}

JNIEXPORT void JNICALL F(nFrame)(JNIEnv *env, jclass c, jlong h) {
    if (h) projectm_opengl_render_frame((projectm_handle)(intptr_t)h);
}

JNIEXPORT void JNICALL F(nDestroy)(JNIEnv *env, jclass c, jlong h) {
    if (h) projectm_destroy((projectm_handle)(intptr_t)h);
}
