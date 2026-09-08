#!/usr/bin/env bash
# Validates manifests: no granted INTERNET, no harness in release, expected permissions.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$REPO_ROOT/app/build"
FOUND=0

echo "=== Manifest Check ==="

RELEASE_MANIFESTS=$(find "$BUILD_DIR" -name "AndroidManifest.xml" \
    -path "*release*" -path "*/merged_manifests/*" 2>/dev/null || true)
HARNESS_MANIFESTS=$(find "$BUILD_DIR" -name "AndroidManifest.xml" \
    -path "*harness*" -path "*/merged_manifests/*" 2>/dev/null || true)

if [ -z "$RELEASE_MANIFESTS" ] && [ -z "$HARNESS_MANIFESTS" ]; then
    echo "No merged manifests found — checking source manifests."
    RELEASE_MANIFESTS="$REPO_ROOT/app/src/main/AndroidManifest.xml"
    HARNESS_MANIFESTS="$REPO_ROOT/app/src/main/AndroidManifest.xml $REPO_ROOT/app/src/harness/AndroidManifest.xml"
fi

flatten() {
    tr '\n' ' ' < "$1" | tr -s ' '
}

echo ""
echo "--- Check 1: No INTERNET permission ---"
ALL_MANIFESTS="$RELEASE_MANIFESTS $HARNESS_MANIFESTS"
for manifest in $ALL_MANIFESTS; do
    if [ -f "$manifest" ]; then
        FLAT=$(flatten "$manifest")
        if echo "$FLAT" | grep -oE '<uses-permission[^>]*android.permission.INTERNET[^>]*>' | grep -v 'tools:node="remove"' >/tmp/internet_hits 2>/dev/null; then
            if [ -s /tmp/internet_hits ]; then
                echo "FAIL: INTERNET permission found in $manifest"
                cat /tmp/internet_hits
                FOUND=1
            fi
        fi
    fi
done
if [ "$FOUND" -eq 0 ]; then
    echo "PASS: No INTERNET permission found."
fi

echo ""
echo "--- Check 2: No harness receivers in release ---"
for manifest in $RELEASE_MANIFESTS; do
    if [ -f "$manifest" ]; then
        if grep -n "HarnessReceiver\|HARNESS_" "$manifest" 2>/dev/null; then
            echo "FAIL: Harness receiver found in release manifest: $manifest"
            FOUND=1
        else
            echo "PASS: No harness receivers in $manifest"
        fi
    fi
done

echo ""
echo "--- Check 3: Permission count in release ---"
EXPECTED_PERMS=(
    "android.permission.RECORD_AUDIO"
    "android.permission.SYSTEM_ALERT_WINDOW"
    "android.permission.FOREGROUND_SERVICE"
    "android.permission.FOREGROUND_SERVICE_MICROPHONE"
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
)

for manifest in $RELEASE_MANIFESTS; do
    if [ -f "$manifest" ]; then
        FLAT=$(flatten "$manifest")
        PERM_COUNT=$(echo "$FLAT" | grep -oE '<uses-permission[^>]*>' | grep -v 'tools:node="remove"' | wc -l | tr -d ' ')
        echo "Permissions found: $PERM_COUNT (expected: ${#EXPECTED_PERMS[@]})"

        for perm in "${EXPECTED_PERMS[@]}"; do
            if ! grep -q "$perm" "$manifest" 2>/dev/null; then
                echo "FAIL: Missing expected permission: $perm"
                FOUND=1
            fi
        done

        if [ "$PERM_COUNT" -ne "${#EXPECTED_PERMS[@]}" ]; then
            echo "FAIL: Expected exactly ${#EXPECTED_PERMS[@]} permissions, found $PERM_COUNT"
            FOUND=1
        else
            echo "PASS: Correct permission count."
        fi
    fi
done

echo ""
if [ "$FOUND" -eq 1 ]; then
    echo "RESULT: FAIL"
    exit 1
else
    echo "RESULT: PASS"
    exit 0
fi
