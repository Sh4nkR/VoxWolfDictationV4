#!/usr/bin/env bash
# forbidden_api_scan.sh — CLAUDE.md §2 enforcement
# Greps the source tree and merged manifests for every forbidden identifier.
# Exit 1 on any hit.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$REPO_ROOT/app/src"

# Forbidden identifiers per CLAUDE.md §2
FORBIDDEN=(
    # §2.1 — No SpeechRecognizer
    "SpeechRecognizer"

    # §2.2 — No audio output
    "ToneGenerator"
    "SoundPool"
    "MediaPlayer"
    "AudioTrack"
    "Ringtone"
    "RingtoneManager"
    "Notification\.setSound"
    "NotificationCompat\.setSound"
    "setDefaults.*DEFAULT_SOUND"
    "AudioAttributes\.USAGE_NOTIFICATION"

    # §2.4 — No network calls
    "OkHttp"
    "Retrofit"
    "HttpURLConnection"
    "URL\.openConnection"
    "java\.net\.Socket"
    "Firebase"
    "Crashlytics"
)

FOUND=0

echo "=== Forbidden API Scan ==="
echo "Scanning: $SRC_DIR"
echo ""

for pattern in "${FORBIDDEN[@]}"; do
    # Search Kotlin/Java sources, XML, and gradle files
    if grep -rn --include="*.kt" --include="*.java" --include="*.xml" --include="*.kts" \
        -E "$pattern" "$SRC_DIR" 2>/dev/null; then
        echo "FAIL: Found forbidden identifier matching: $pattern"
        FOUND=1
    fi
done

# Also scan build files at root
for pattern in "${FORBIDDEN[@]}"; do
    if grep -rn --include="*.kts" --include="*.gradle" -E "$pattern" \
        "$REPO_ROOT/build.gradle.kts" "$REPO_ROOT/app/build.gradle.kts" \
        "$REPO_ROOT/settings.gradle.kts" 2>/dev/null; then
        echo "FAIL: Found forbidden identifier in build files matching: $pattern"
        FOUND=1
    fi
done

# Check merged manifests if they exist (built artifacts)
MERGED_MANIFESTS=$(find "$REPO_ROOT/app/build" -name "AndroidManifest.xml" -path "*/merged_manifests/*" 2>/dev/null || true)
for manifest in $MERGED_MANIFESTS; do
    for pattern in "${FORBIDDEN[@]}"; do
        if grep -n -E "$pattern" "$manifest" 2>/dev/null; then
            echo "FAIL: Found forbidden identifier in merged manifest: $manifest"
            FOUND=1
        fi
    done
done

if [ "$FOUND" -eq 1 ]; then
    echo ""
    echo "RESULT: FAIL — Forbidden API references found."
    exit 1
else
    echo "RESULT: PASS — No forbidden API references found."
    exit 0
fi
