/**
 * whisper_jni.cpp — Thin JNI bridge per SPEC §4.8
 *
 * Five functions, no others:
 *   initContext, freeContext, transcribe, systemInfo, isMultilingual
 *
 * PCM-16 → float32 conversion happens on the Kotlin side.
 * Context is created once in CaptureService.onCreate, freed in onDestroy.
 */

#include <jni.h>
#include <string>
#include "whisper.h"

#ifdef __cplusplus
extern "C" {
#endif

static const char *TAG = "VoxWolfJNI";

/**
 * Initialize a whisper context from a model file path.
 * Returns the context pointer as a jlong, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_voxwolf_dictation_asr_WhisperNative_initContext(
        JNIEnv *env, jclass /* clazz */, jstring modelPath) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        return 0;
    }

    struct whisper_context_params params = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);

    return reinterpret_cast<jlong>(ctx);
}

/**
 * Free a whisper context.
 */
JNIEXPORT void JNICALL
Java_com_voxwolf_dictation_asr_WhisperNative_freeContext(
        JNIEnv * /* env */, jclass /* clazz */, jlong ctx) {

    if (ctx != 0) {
        whisper_free(reinterpret_cast<struct whisper_context *>(ctx));
    }
}

/**
 * Transcribe PCM float32 audio.
 * Returns the transcribed text, or empty string on failure.
 *
 * Whisper parameters per SPEC §4.7:
 *   WHISPER_SAMPLING_GREEDY, n_threads=nThreads, translate=false,
 *   language="en", no_context=true, single_segment=false,
 *   suppress_blank=true, suppress_nst=true,
 *   temperature=0.0, temperature_inc=0.0, max_tokens=0
 */
JNIEXPORT jstring JNICALL
Java_com_voxwolf_dictation_asr_WhisperNative_transcribe(
        JNIEnv *env, jclass /* clazz */, jlong ctx, jfloatArray pcm, jint nThreads) {

    if (ctx == 0) {
        return env->NewStringUTF("");
    }

    struct whisper_context *context = reinterpret_cast<struct whisper_context *>(ctx);

    jint pcmLen = env->GetArrayLength(pcm);
    jfloat *pcmData = env->GetFloatArrayElements(pcm, nullptr);
    if (pcmData == nullptr) {
        return env->NewStringUTF("");
    }

    // Configure per SPEC §4.7
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads       = static_cast<int>(nThreads);
    params.translate       = false;
    params.language        = "en";
    params.no_context      = true;   // Context comes from our overlap, not whisper's
    params.single_segment  = false;
    params.print_realtime  = false;
    params.print_progress  = false;
    params.print_timestamps = false;
    params.print_special   = false;
    params.suppress_blank  = true;
    params.suppress_nst    = true;   // Non-speech tokens
    params.temperature     = 0.0f;
    params.temperature_inc = 0.0f;   // No fallback re-decode — blows the latency budget
    params.max_tokens      = 0;

    int result = whisper_full(context, params, pcmData, pcmLen);
    env->ReleaseFloatArrayElements(pcm, pcmData, JNI_ABORT);

    if (result != 0) {
        return env->NewStringUTF("");
    }

    // Collect all segments into one string
    std::string text;
    int nSegments = whisper_full_n_segments(context);
    for (int i = 0; i < nSegments; i++) {
        const char *segText = whisper_full_get_segment_text(context, i);
        if (segText != nullptr) {
            text += segText;
        }
    }

    return env->NewStringUTF(text.c_str());
}

/**
 * Returns whisper system info string for diagnostics.
 */
JNIEXPORT jstring JNICALL
Java_com_voxwolf_dictation_asr_WhisperNative_systemInfo(
        JNIEnv *env, jclass /* clazz */) {

    const char *info = whisper_print_system_info();
    return env->NewStringUTF(info);
}

/**
 * Check if the loaded model is multilingual.
 */
JNIEXPORT jboolean JNICALL
Java_com_voxwolf_dictation_asr_WhisperNative_isMultilingual(
        JNIEnv * /* env */, jclass /* clazz */, jlong ctx) {

    if (ctx == 0) return JNI_FALSE;
    return whisper_is_multilingual(reinterpret_cast<struct whisper_context *>(ctx))
           ? JNI_TRUE : JNI_FALSE;
}

#ifdef __cplusplus
}
#endif
