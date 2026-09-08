#!/usr/bin/env bash
# harness_run.sh — Installs harness APK, pushes corpus, drives one full run, pulls telemetry.
# SPEC §13 / BUILD_ORDER Phase 4

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.voxwolf.dictation.harness"
DEVICE_DIR="/sdcard/voxwolf_test"
CORPUS_DIR="${1:-$REPO_ROOT/corpus}"
CORPUS_FILE="${2:-corpus_01.wav}"

echo "=== VoxWolf Harness Run ==="
echo "Corpus: $CORPUS_DIR/$CORPUS_FILE"

# 1. Install harness APK
APK=$(find "$REPO_ROOT/app/build" -name "*harness*.apk" -path "*/debug/*" | head -1)
if [ -z "$APK" ]; then
    echo "ERROR: No harness APK found. Run ./gradlew assembleHarnessDebug first."
    exit 1
fi
echo "Installing: $APK"
adb install -r "$APK"

# 2. Create test directory and push corpus
adb shell mkdir -p "$DEVICE_DIR"
if [ -f "$CORPUS_DIR/$CORPUS_FILE" ]; then
    echo "Pushing corpus: $CORPUS_FILE"
    adb push "$CORPUS_DIR/$CORPUS_FILE" "$DEVICE_DIR/"
else
    echo "WARNING: Corpus file not found at $CORPUS_DIR/$CORPUS_FILE"
    echo "Run will proceed without file audio source."
fi

# 3. Clear old telemetry
adb shell "rm -f $DEVICE_DIR/telemetry.jsonl" 2>/dev/null || true

# 4. Start the app (onboarding must be completed manually first)
echo "Starting VoxWolf..."
adb shell am start -n "$PKG/.onboarding.OnboardingActivity"
sleep 3

# 5. Set file audio source
echo "Setting file source: $CORPUS_FILE"
adb shell am broadcast -a com.voxwolf.HARNESS_SOURCE --es file "$DEVICE_DIR/$CORPUS_FILE"
sleep 1

# 6. Run session
echo "Bubble tap..."
adb shell am broadcast -a com.voxwolf.HARNESS_BUBBLE_TAP
sleep 1

echo "Mic ON..."
adb shell am broadcast -a com.voxwolf.HARNESS_MIC_ON
echo "Recording... waiting for corpus to play through."

# Wait for corpus duration + buffer (160s default, adjust for longer)
WAIT_SEC="${3:-170}"
echo "Waiting ${WAIT_SEC}s for corpus playback..."
sleep "$WAIT_SEC"

echo "Mic OFF..."
adb shell am broadcast -a com.voxwolf.HARNESS_MIC_OFF

# 7. Wait for drain
echo "Waiting for Stage 2 drain..."
sleep 15

# 8. Dump telemetry
echo "Flushing telemetry..."
adb shell am broadcast -a com.voxwolf.HARNESS_DUMP
sleep 2

# 9. Pull telemetry
echo "Pulling telemetry..."
mkdir -p "$REPO_ROOT/reports"
adb pull "$DEVICE_DIR/telemetry.jsonl" "$REPO_ROOT/reports/telemetry.jsonl"

echo ""
echo "=== Run complete ==="
echo "Telemetry: $REPO_ROOT/reports/telemetry.jsonl"
echo ""
echo "Run report:"
python3 "$REPO_ROOT/scripts/telemetry_report.py" "$REPO_ROOT/reports/telemetry.jsonl"
