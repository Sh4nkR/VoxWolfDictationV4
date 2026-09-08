package com.voxwolf.dictation

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.voxwolf.dictation.audio.SessionBuffer
import com.voxwolf.dictation.telemetry.Telemetry

/**
 * Application class — global singletons that outlive any single service lifecycle.
 *
 * Owns:
 * - SessionBuffer (pre-allocated once, reused across sessions)
 * - Telemetry (single emitter for the process)
 * - Notification channel creation
 */
class VoxWolfApp : Application() {

    lateinit var sessionBuffer: SessionBuffer
        private set

    lateinit var telemetry: Telemetry
        private set

    override fun onCreate() {
        super.onCreate()

        // Pre-allocate the session buffer once for the process lifetime (SPEC §4.4)
        sessionBuffer = SessionBuffer()

        // Telemetry emitter — base class logs to logcat; harness variant overrides
        telemetry = createTelemetry()

        // Create the foreground service notification channel
        createNotificationChannel()
    }

    /**
     * Override point for the harness variant to inject HarnessTelemetry.
     * The base release variant uses plain Telemetry (logcat only).
     */
    protected open fun createTelemetry(): Telemetry = Telemetry()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "voxwolf_capture"
    }
}
