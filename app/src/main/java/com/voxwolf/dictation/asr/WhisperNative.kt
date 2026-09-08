package com.voxwolf.dictation.asr

/**
 * JNI declarations for the whisper.cpp bridge.
 * SPEC §4.8 — Five functions, no others.
 */
object WhisperNative {

    init {
        System.loadLibrary("voxwolf_jni")
    }

    /**
     * Initialize a whisper context from a model file path.
     * @return context pointer as Long, or 0 on failure.
     */
    @JvmStatic
    external fun initContext(modelPath: String): Long

    /**
     * Free a whisper context.
     */
    @JvmStatic
    external fun freeContext(ctx: Long)

    /**
     * Transcribe PCM float32 audio.
     * @param ctx context pointer from initContext
     * @param pcm float32 audio samples (pre-converted from PCM-16)
     * @param nThreads number of inference threads (spec: 4)
     * @return transcribed text, or empty string on failure
     */
    @JvmStatic
    external fun transcribe(ctx: Long, pcm: FloatArray, nThreads: Int): String

    /**
     * Returns whisper system info string for diagnostics.
     */
    @JvmStatic
    external fun systemInfo(): String

    /**
     * Check if the loaded model is multilingual.
     */
    @JvmStatic
    external fun isMultilingual(ctx: Long): Boolean
}
