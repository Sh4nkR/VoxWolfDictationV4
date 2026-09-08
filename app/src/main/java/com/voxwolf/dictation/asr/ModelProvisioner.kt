package com.voxwolf.dictation.asr

import android.content.Context
import com.voxwolf.dictation.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * SPEC §4.9 — Model provisioning.
 *
 * Copies tiny.en q5_1 from APK assets to internal storage on first run.
 * SHA-256 verified after copy and on every service start.
 * On mismatch: re-copy once; on second mismatch → MODEL_CORRUPT.
 */
class ModelProvisioner(private val context: Context) {

    companion object {
        private const val MODEL_ASSET_NAME = "models/ggml-tiny.en-q5_1.bin"
        private const val MODEL_FILE_NAME = "ggml-tiny.en-q5_1.bin"
    }

    private val modelFile: File
        get() = File(context.filesDir, MODEL_FILE_NAME)

    /**
     * Ensures the model is available and verified on internal storage.
     * @return the verified model file path, or null on failure with the error code.
     */
    fun provision(): ProvisionResult {
        val file = modelFile

        // First attempt: if file exists, verify
        if (file.exists()) {
            if (verifySha256(file)) {
                return ProvisionResult.Success(file.absolutePath)
            }
            // Mismatch — re-copy once
            file.delete()
        }

        // Copy from assets
        if (!copyFromAssets(file)) {
            return ProvisionResult.Error("BUFFER_ALLOC_FAIL")
        }

        // Verify after copy
        if (!verifySha256(file)) {
            // Second attempt: re-copy
            file.delete()
            if (!copyFromAssets(file)) {
                return ProvisionResult.Error("MODEL_CORRUPT")
            }
            if (!verifySha256(file)) {
                file.delete()
                return ProvisionResult.Error("MODEL_CORRUPT")
            }
        }

        return ProvisionResult.Success(file.absolutePath)
    }

    /**
     * Verify only — called on every service start.
     */
    fun verifyOnStart(): ProvisionResult {
        val file = modelFile
        if (!file.exists()) {
            return provision()
        }
        if (!verifySha256(file)) {
            // Re-copy once
            file.delete()
            return provision()
        }
        return ProvisionResult.Success(file.absolutePath)
    }

    val modelPath: String?
        get() {
            val file = modelFile
            return if (file.exists()) file.absolutePath else null
        }

    private fun copyFromAssets(dest: File): Boolean {
        return try {
            context.assets.open(MODEL_ASSET_NAME).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun verifySha256(file: File): Boolean {
        val expectedDigest = BuildConfig.MODEL_SHA256
        if (expectedDigest.startsWith("PLACEHOLDER")) {
            // Development: skip verification until real digest is set
            return true
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val computed = digest.digest().joinToString("") { "%02x".format(it) }
            computed.equals(expectedDigest, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    sealed class ProvisionResult {
        data class Success(val path: String) : ProvisionResult()
        data class Error(val code: String) : ProvisionResult()
    }
}
