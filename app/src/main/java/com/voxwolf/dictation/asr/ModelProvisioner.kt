package com.voxwolf.dictation.asr

import android.content.Context
import android.util.Log
import com.voxwolf.dictation.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
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
        private const val TAG = "VoxWolf.Model"
        private const val MODEL_ASSET_NAME = "models/ggml-tiny.en-q5_1.bin"
        private const val MODEL_FILE_NAME = "ggml-tiny.en-q5_1.bin"
        // Real ggml-tiny.en-q5_1.bin is ~32 MB. A Git LFS pointer is ~130 bytes.
        private const val MIN_MODEL_BYTES = 1_000_000L
    }

    private val modelFile: File
        get() = File(context.filesDir, MODEL_FILE_NAME)

    /** True when a verified model file is already on internal storage. */
    fun isReady(): Boolean {
        val file = modelFile
        return file.exists() && file.length() >= MIN_MODEL_BYTES && verifySha256(file)
    }

    /**
     * Ensures the model is available and verified on internal storage.
     */
    fun provision(): ProvisionResult {
        val file = modelFile

        if (isReady()) {
            return ProvisionResult.Success(file.absolutePath)
        }
        if (file.exists()) {
            file.delete()
        }

        val firstCopy = copyFromAssets(file)
        if (firstCopy is ProvisionResult.Error) return firstCopy

        if (verifySha256(file)) {
            return ProvisionResult.Success(file.absolutePath)
        }

        file.delete()
        val secondCopy = copyFromAssets(file)
        if (secondCopy is ProvisionResult.Error) return secondCopy
        if (!verifySha256(file)) {
            file.delete()
            return ProvisionResult.Error("MODEL_CORRUPT")
        }
        return ProvisionResult.Success(file.absolutePath)
    }

    fun verifyOnStart(): ProvisionResult {
        return if (isReady()) {
            ProvisionResult.Success(modelFile.absolutePath)
        } else {
            provision()
        }
    }

    val modelPath: String?
        get() {
            val file = modelFile
            return if (file.exists() && file.length() >= MIN_MODEL_BYTES) {
                file.absolutePath
            } else {
                null
            }
        }

    private fun copyFromAssets(dest: File): ProvisionResult {
        val tmp = File(dest.absolutePath + ".tmp")
        return try {
            tmp.delete()
            context.assets.open(MODEL_ASSET_NAME).use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            val size = tmp.length()
            if (size < MIN_MODEL_BYTES) {
                Log.e(TAG, "Copied model is only $size bytes — likely an LFS pointer")
                tmp.delete()
                return ProvisionResult.Error("MODEL_TOO_SMALL")
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            ProvisionResult.Success(dest.absolutePath)
        } catch (e: FileNotFoundException) {
            tmp.delete()
            Log.e(TAG, "Model asset missing: $MODEL_ASSET_NAME", e)
            ProvisionResult.Error("ASSET_MISSING")
        } catch (e: Exception) {
            tmp.delete()
            Log.e(TAG, "Model copy failed", e)
            val msg = e.message.orEmpty()
            if (msg.contains("ENOSPC", ignoreCase = true) ||
                msg.contains("No space", ignoreCase = true)
            ) {
                ProvisionResult.Error("DISK_FULL")
            } else {
                ProvisionResult.Error("COPY_FAIL")
            }
        }
    }

    private fun verifySha256(file: File): Boolean {
        val expectedDigest = BuildConfig.MODEL_SHA256
        if (expectedDigest.startsWith("PLACEHOLDER")) {
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
            val ok = computed.equals(expectedDigest, ignoreCase = true)
            if (!ok) {
                Log.e(TAG, "SHA-256 mismatch computed=$computed expected=$expectedDigest")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "SHA-256 verify failed", e)
            false
        }
    }

    sealed class ProvisionResult {
        data class Success(val path: String) : ProvisionResult()
        data class Error(val code: String) : ProvisionResult()
    }
}
