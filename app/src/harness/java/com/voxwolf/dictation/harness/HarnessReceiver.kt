package com.voxwolf.dictation.harness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.voxwolf.dictation.state.SessionStateMachine
import com.voxwolf.dictation.state.SessionEvent

/**
 * SPEC §13.2 — ADB control surface for harness variant.
 *
 * Every user action reachable by ADB broadcast. These drive the same
 * handlers as the real touch targets — they do NOT bypass the state machine.
 * The receiver posts to the main thread and calls SessionStateMachine.on(Event).
 *
 * Broadcasts:
 *   com.voxwolf.HARNESS_SOURCE       --es file <path>
 *   com.voxwolf.HARNESS_BUBBLE_TAP
 *   com.voxwolf.HARNESS_MIC_ON
 *   com.voxwolf.HARNESS_MIC_OFF
 *   com.voxwolf.HARNESS_CANCEL
 *   com.voxwolf.HARNESS_DUMP
 *   com.voxwolf.HARNESS_LONG_PRESS
 *   com.voxwolf.HARNESS_INJECT_ERROR --es code CAPTURE_LOST|MIC_SEIZED|BUFFER_CEILING
 */
class HarnessReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SOURCE = "com.voxwolf.HARNESS_SOURCE"
        const val ACTION_BUBBLE_TAP = "com.voxwolf.HARNESS_BUBBLE_TAP"
        const val ACTION_MIC_ON = "com.voxwolf.HARNESS_MIC_ON"
        const val ACTION_MIC_OFF = "com.voxwolf.HARNESS_MIC_OFF"
        const val ACTION_CANCEL = "com.voxwolf.HARNESS_CANCEL"
        const val ACTION_DUMP = "com.voxwolf.HARNESS_DUMP"
        const val ACTION_LONG_PRESS = "com.voxwolf.HARNESS_LONG_PRESS"
        const val ACTION_INJECT_ERROR = "com.voxwolf.HARNESS_INJECT_ERROR"

        // Set by CaptureService composition root when harness variant
        var fileAudioSource: FileAudioSource? = null
        var stateMachine: SessionStateMachine? = null
        var telemetryFlush: (() -> Unit)? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SOURCE -> {
                val filePath = intent.getStringExtra("file") ?: return
                fileAudioSource?.setFile(filePath)
            }

            ACTION_BUBBLE_TAP -> postEvent(SessionEvent.BUBBLE_TAP)
            ACTION_MIC_ON -> postEvent(SessionEvent.MIC_BUTTON)
            ACTION_MIC_OFF -> postEvent(SessionEvent.MIC_BUTTON)
            ACTION_CANCEL -> postEvent(SessionEvent.RED_CROSS)
            ACTION_LONG_PRESS -> postEvent(SessionEvent.BUBBLE_LONG_PRESS)

            ACTION_DUMP -> {
                telemetryFlush?.invoke()
            }

            ACTION_INJECT_ERROR -> {
                val code = intent.getStringExtra("code") ?: return
                when (code) {
                    "CAPTURE_LOST" -> postEvent(SessionEvent.CAPTURE_LOSS)
                    "MIC_SEIZED" -> postEvent(SessionEvent.CAPTURE_LOSS)
                    "BUFFER_CEILING" -> postEvent(SessionEvent.BUFFER_CEILING)
                }
            }
        }
    }

    private fun postEvent(event: SessionEvent) {
        mainHandler.post {
            stateMachine?.on(event)
        }
    }
}
