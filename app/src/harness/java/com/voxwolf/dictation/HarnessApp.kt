package com.voxwolf.dictation

import com.voxwolf.dictation.harness.HarnessTelemetry
import com.voxwolf.dictation.telemetry.Telemetry

/**
 * Harness variant Application — injects HarnessTelemetry (file-based JSONL)
 * instead of the release logcat-only emitter.
 *
 * android:name in harness manifest merges to point here.
 */
class HarnessApp : VoxWolfApp() {

    override fun createTelemetry(): Telemetry = HarnessTelemetry()
}
