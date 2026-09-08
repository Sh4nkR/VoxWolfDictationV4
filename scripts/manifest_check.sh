#!/usr/bin/env bash
# manifest_check.sh — Validates merged manifests for both flavours.
# 1. No INTERNET permission in any variant.
# 2. Harness receivers absent from release manifest.
# 3. Exactly 4 permissions in release: RECORD_AUDIO, SYSTEM_ALERT_WINDOW,
#    FOREGROUND_SERVICE, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$REPO_ROOT/app/build"

FOUND=0

echo "=== Manifest Check ==="

# Find merged manifests
RELEASE_MANIFESTS=$(find "$BUILD_DIR" -name "AndroidManifest.xml" \
    -path "*release*" -path "*/merged_manifests/*" 2>/dev/null || true)
HARNESS_MANIFESTS=$(find "$BUILD_DIR" -name "AndroidManifest.xml" \
    -path "*harness*" -path "*/merged_manifests/*" 2>/dev/null || true)

# If no built manifests, check source manifests
if [ -z "$RELEASE_MANIFESTS" ] && [ -z "$HARNESS_MANIFESTS" ]; then
    echo "No merged manifests found — checking source manifests."
    RELEASE_MANIFESTS="$REPO_ROOT/app/src/main/AndroidManifest.xml"
    HARNESS_MANIFESTS="$REPO_ROOT/app/src/main/AndroidManifest.xml $REPO_ROOT/app/src/harness/AndroidManifest.xml"
fi

# Check 1: No INTERNET permission in any manifest
echo ""
echo "--- Check 1: No INTERNET permission ---"
ALL_MANIFESTS="$RELEASE_MANIFESTS $HARNESS_MANIFESTS"
for manifest in $ALL_MANIFESTS; do
    if [ -f "$manifest" ]; then
        # Look for INTERNET permission that is NOT a tools:node="remove"
        if grep -n "android.permission.INTERNET" "$manifest" | grep -v 'tools:node="remove"' 2>/dev/null; then
            echo "FAIL: INTERNET permission found in $manifest"
            FOUND=1
        fi
    fi
done
if [ "$FOUND" -eq 0 ]; then
    echo "PASS: No INTERNET permission found."
fi

# Check 2: Harness receivers absent from release manifest
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

# Check 3: Exactly 4 permissions in release
echo ""
echo "--- Check 3: Permission count in release ---"
EXPECTED_PERMS=(
    "android.permission.RECORD_AUDIO"
    "android.permission.SYSTEM_ALERT_WINDOW"
    "android.permission.FOREGROUND_SERVICE"
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
)

for manifest in $RELEASE_MANIFESTS; do
    if [ -f "$manifest" ]; then
        # Count uses-permission lines (excluding tools:node="remove")
        PERM_COUNT=$(grep "<uses-permission" "$manifest" | grep -v 'tools:node="remove"' | wc -l)
        echo "Permissions found: $PERM_COUNT (expected: 4)"

        for perm in "${EXPECTED_PERMS[@]}"; do
            if ! grep -q "$perm" "$manifest" 2>/dev/null; then
                echo "FAIL: Missing expected permission: $perm"
                FOUND=1
            fi
        done

        if [ "$PERM_COUNT" -ne 4 ]; then
            echo "FAIL: Expected exactly 4 permissions, found $PERM_COUNT"
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
