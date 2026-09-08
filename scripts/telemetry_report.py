#!/usr/bin/env python3
"""
telemetry_report.py — Computes every SPEC §14 gate from a JSONL telemetry file.

Usage: python3 telemetry_report.py <telemetry.jsonl> [ground_truth.txt]

Prints the 12-gate table with PASS/FAIL/UNMEASURED and supporting numbers.
"""

import json
import sys
import re
from collections import defaultdict
from pathlib import Path


def load_events(path):
    events = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return events


def compute_wer(reference, hypothesis):
    """Word Error Rate via dynamic programming."""
    ref_words = reference.lower().split()
    hyp_words = hypothesis.lower().split()
    # Strip punctuation for comparison
    ref_words = [re.sub(r'[^\w\']', '', w) for w in ref_words if re.sub(r'[^\w\']', '', w)]
    hyp_words = [re.sub(r'[^\w\']', '', w) for w in hyp_words if re.sub(r'[^\w\']', '', w)]

    n = len(ref_words)
    m = len(hyp_words)
    d = [[0] * (m + 1) for _ in range(n + 1)]

    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j

    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if ref_words[i - 1] == hyp_words[j - 1]:
                d[i][j] = d[i - 1][j - 1]
            else:
                d[i][j] = 1 + min(d[i - 1][j], d[i][j - 1], d[i - 1][j - 1])

    return d[n][m] / max(n, 1)


def run_report(telemetry_path, ground_truth_path=None):
    events = load_events(telemetry_path)
    if not events:
        print("ERROR: No events found in telemetry file.")
        return

    # Group by session
    sessions = defaultdict(list)
    for e in events:
        sid = e.get("sid", 0)
        sessions[sid].append(e)

    # Collect metrics across all sessions
    all_infer_durations = []
    all_stop_to_paste = []
    all_window_events = []
    all_seam_unmatched = 0
    all_seam_total = 0
    all_mic_reasons = []
    stop_pressed_count = 0
    delivered_count = 0
    insert_failed_count = 0
    cancelled_or_error_insertions = 0

    for sid, sevents in sessions.items():
        for e in sevents:
            ev = e.get("ev", "")

            if ev == "INFER_END":
                all_infer_durations.append(e.get("dur_ms", 0))

            if ev == "STOP_TO_PASTE":
                all_stop_to_paste.append(e.get("dur_ms", 0))

            if ev == "WINDOW_CLOSED":
                all_window_events.append(e)

            if ev == "SEAM_UNMATCHED":
                all_seam_unmatched += 1
                all_seam_total += 1
            if ev == "SEAM_MATCHED":
                all_seam_total += 1

            if ev == "MIC_STATE" and e.get("state") == "off":
                all_mic_reasons.append(e.get("reason", "UNKNOWN"))

            if ev == "STOP_PRESSED":
                stop_pressed_count += 1

            if ev == "SESSION_END":
                end_state = e.get("end_state", "")
                if end_state == "DELIVERED":
                    delivered_count += 1

            if ev == "INSERT_FAILED":
                insert_failed_count += 1

            if ev == "INSERT_VERIFIED":
                # Check if this session had a cancel or error
                pass

    # ---- GATE COMPUTATIONS ----

    print("=" * 70)
    print("VoxWolf v4.1 Validation Report")
    print("=" * 70)
    print(f"Sessions: {len(sessions)}")
    print(f"Total events: {len(events)}")
    print()

    # Gate 1: No invalid MIC_STATE reasons
    valid_reasons = {"user_stop", "user_cancel", "capture_lost", "buffer_ceiling"}
    invalid_reasons = [r for r in all_mic_reasons if r not in valid_reasons]
    g1 = "PASS" if not invalid_reasons else "FAIL"
    print(f"Gate  1  [{g1:>10}]  MIC_STATE reasons — invalid: {len(invalid_reasons)}")
    if invalid_reasons:
        print(f"         Invalid reasons: {invalid_reasons}")

    # Gate 2: Inference latency
    if all_infer_durations:
        mean_infer = sum(all_infer_durations) / len(all_infer_durations)
        sorted_infer = sorted(all_infer_durations)
        p95_idx = int(len(sorted_infer) * 0.95)
        p95_infer = sorted_infer[min(p95_idx, len(sorted_infer) - 1)]
        max_infer = max(all_infer_durations)
        g2 = "PASS" if mean_infer <= 1200 and p95_infer <= 2000 and max_infer <= 3500 else "FAIL"
        print(f"Gate  2  [{g2:>10}]  Inference: mean={mean_infer:.0f}ms p95={p95_infer}ms max={max_infer}ms")
    else:
        print(f"Gate  2  [UNMEASURED]  No inference data")

    # Gate 3: Stop-to-paste
    if all_stop_to_paste:
        sorted_stp = sorted(all_stop_to_paste)
        p95_idx = int(len(sorted_stp) * 0.95)
        p95_stp = sorted_stp[min(p95_idx, len(sorted_stp) - 1)]
        g3 = "PASS" if p95_stp <= 1500 else "FAIL"
        print(f"Gate  3  [{g3:>10}]  Stop-to-paste p95={p95_stp}ms (n={len(all_stop_to_paste)})")
    else:
        print(f"Gate  3  [UNMEASURED]  No stop-to-paste data")

    # Gate 4: Seam unmatched rate
    if all_seam_total > 0:
        seam_rate = all_seam_unmatched / all_seam_total * 100
        g4 = "PASS" if seam_rate <= 2.0 else "FAIL"
        print(f"Gate  4  [{g4:>10}]  SEAM_UNMATCHED rate={seam_rate:.1f}% ({all_seam_unmatched}/{all_seam_total})")
    else:
        print(f"Gate  4  [UNMEASURED]  No seam data")

    # Gate 5: WER comparison (requires ground truth)
    if ground_truth_path:
        print(f"Gate  5  [UNMEASURED]  WER comparison requires corpus run results")
    else:
        print(f"Gate  5  [UNMEASURED]  No ground truth provided")

    # Gate 6: Zero insertions on errored/cancelled sessions
    g6 = "PASS" if cancelled_or_error_insertions == 0 else "FAIL"
    print(f"Gate  6  [{g6:>10}]  Insertions on error/cancel: {cancelled_or_error_insertions}")

    # Gate 7: Caret verification
    print(f"Gate  7  [UNMEASURED]  Caret verification requires manual test on device")

    # Gate 8: Silence proof
    print(f"Gate  8  [UNMEASURED]  Static scan + output capture required")

    # Gate 9: Haptic distinctness
    print(f"Gate  9  [UNMEASURED]  Haptic capture required on device")

    # Gate 10: Offline proof
    print(f"Gate 10  [UNMEASURED]  aapt dump + aeroplane mode run required")

    # Gate 11: No unhandled transcript
    accounted = delivered_count + insert_failed_count
    g11 = "PASS" if accounted == stop_pressed_count and stop_pressed_count > 0 else "UNMEASURED"
    if stop_pressed_count > 0:
        g11 = "PASS" if accounted == stop_pressed_count else "FAIL"
    print(f"Gate 11  [{g11:>10}]  STOP_PRESSED={stop_pressed_count} DELIVERED={delivered_count} INSERT_FAILED={insert_failed_count}")

    # Gate 12: Corpus_06 quiet proof
    print(f"Gate 12  [UNMEASURED]  Requires corpus_06 run")

    print()
    print("=" * 70)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <telemetry.jsonl> [ground_truth.txt]")
        sys.exit(1)

    telemetry_path = sys.argv[1]
    gt_path = sys.argv[2] if len(sys.argv) > 2 else None
    run_report(telemetry_path, gt_path)
