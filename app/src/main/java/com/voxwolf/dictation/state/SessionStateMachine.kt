package com.voxwolf.dictation.state

import android.os.Handler
import android.os.Looper
import com.voxwolf.dictation.telemetry.Telemetry

/**
 * SPEC §10 — Session state machine. Single source of truth.
 *
 * All transitions go through on(Event) on the main thread.
 * Illegal transitions throw in debug, logged as ILLEGAL_TRANSITION in release.
 * No component sets state directly.
 */
class SessionStateMachine(
    private val telemetry: Telemetry,
    private val onTransition: (SessionState, SessionState, SessionEvent) -> Unit
) {
    @Volatile
    var state: SessionState = SessionState.IDLE
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Process an event. Must be called on the main thread.
     * All side effects are dispatched via onTransition callback.
     */
    fun on(event: SessionEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Post to main thread — the state machine is single-writer
            mainHandler.post { on(event) }
            return
        }

        val from = state
        val to = transition(from, event)

        if (to == null) {
            // Illegal transition
            telemetry.emit("ILLEGAL_TRANSITION", mapOf(
                "from" to from.name,
                "event" to event.name
            ))
            if (BuildConfig.DEBUG_MODE) {
                throw IllegalStateException("Illegal transition: $from + $event")
            }
            return
        }

        state = to
        onTransition(from, to, event)
    }

    /**
     * Full transition table per SPEC §10.1.
     * Returns null for illegal transitions.
     */
    private fun transition(from: SessionState, event: SessionEvent): SessionState? {
        return when (from) {
            SessionState.IDLE -> when (event) {
                SessionEvent.BUBBLE_TAP -> SessionState.EXPANDED
                else -> null
            }

            SessionState.EXPANDED -> when (event) {
                SessionEvent.BUBBLE_TAP -> SessionState.IDLE
                SessionEvent.MIC_BUTTON -> SessionState.RECORDING
                SessionEvent.RED_CROSS -> SessionState.IDLE
                else -> null
            }

            SessionState.RECORDING -> when (event) {
                SessionEvent.MIC_BUTTON -> SessionState.DRAINING
                SessionEvent.RED_CROSS -> SessionState.CANCELLED
                SessionEvent.CAPTURE_LOSS -> SessionState.ERROR
                SessionEvent.BUFFER_CEILING -> SessionState.ERROR
                else -> null
            }

            SessionState.DRAINING -> when (event) {
                SessionEvent.QUEUE_EMPTY -> SessionState.INSERTING
                SessionEvent.RED_CROSS -> SessionState.CANCELLED
                else -> null
            }

            SessionState.INSERTING -> when (event) {
                SessionEvent.INSERT_VERIFIED -> SessionState.DELIVERED
                SessionEvent.INSERT_FAILED -> SessionState.ERROR
                SessionEvent.TARGET_LOST -> SessionState.ERROR
                else -> null
            }

            SessionState.DELIVERED -> when (event) {
                SessionEvent.TIMEOUT -> SessionState.IDLE
                else -> null
            }

            SessionState.CANCELLED -> when (event) {
                SessionEvent.IMMEDIATE -> SessionState.IDLE
                else -> null
            }

            SessionState.ERROR -> when (event) {
                SessionEvent.BUBBLE_TAP -> SessionState.IDLE
                SessionEvent.BUBBLE_LONG_PRESS -> SessionState.IDLE
                else -> null
            }
        }
    }

    fun reset() {
        state = SessionState.IDLE
    }

    /**
     * Helper for BuildConfig.DEBUG detection without direct import.
     */
    private object BuildConfig {
        val DEBUG_MODE: Boolean = try {
            val clazz = Class.forName("com.voxwolf.dictation.BuildConfig")
            val field = clazz.getField("DEBUG")
            field.getBoolean(null)
        } catch (_: Exception) {
            false
        }
    }
}

enum class SessionState {
    IDLE,
    EXPANDED,
    RECORDING,
    DRAINING,
    INSERTING,
    DELIVERED,
    CANCELLED,
    ERROR
}

enum class SessionEvent {
    BUBBLE_TAP,
    BUBBLE_LONG_PRESS,
    MIC_BUTTON,
    RED_CROSS,
    CAPTURE_LOSS,
    BUFFER_CEILING,
    QUEUE_EMPTY,
    INSERT_VERIFIED,
    INSERT_FAILED,
    TARGET_LOST,
    TIMEOUT,
    IMMEDIATE
}
