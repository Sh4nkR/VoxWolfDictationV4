# VoxWolf Dictation — Decision Log

## Architecture

### D-001: Single-process, single-service composition root
**Decision**: All pipeline components are owned by CaptureService (foreground service).
**Rationale**: SPEC §2 mandates a single composition root. No bound services, no ContentProviders, no cross-process IPC. The foreground service outlives any Activity and holds the mic.

### D-002: VoxWolfApp owns SessionBuffer and Telemetry
**Decision**: SessionBuffer (17.44 MB) and Telemetry are allocated once in Application.onCreate().
**Rationale**: SPEC §4.4 says "allocated once, reused across sessions, never reallocated." Application outlives service restarts.

### D-003: Harness variant overrides Application class
**Decision**: HarnessApp extends VoxWolfApp, overrides createTelemetry() to inject HarnessTelemetry.
**Rationale**: Single override point keeps pipeline code identical between release and harness. Gradle flavour manifest merge replaces android:name.

## Audio Pipeline

### D-004: Capture thread at MAX_PRIORITY
**Decision**: The AudioRecord read loop runs on a dedicated Thread at MAX_PRIORITY.
**Rationale**: AudioRecord.read() blocks. Dropping samples means data loss. Priority above inference thread prevents starvation.

### D-005: 100ms capture chunks (1600 samples)
**Decision**: Each read() call requests 1600 samples.
**Rationale**: Balances latency (100ms per wakeup) against overhead. VAD frames are 480 samples (30ms), so 3 full frames + remainder per chunk.

### D-006: MicAudioSource capture-loss detection matrix
**Decision**: Six capture-loss conditions checked per SPEC §3.2: recordingState, AudioRecord.state, negative read(), AudioRecordingCallback (mic seizure), 3 consecutive zero chunks.
**Rationale**: Android audio stack can fail silently in multiple ways. Each row catches a different failure mode.

## Inference

### D-007: Reused FloatArray(160_000) for PCM conversion
**Decision**: A single FloatArray is allocated for the worst-case 10s window (160,000 samples at 16kHz).
**Rationale**: SPEC §4.8. Avoids GC churn on every inference. copyOf() creates a view only when the window is shorter.

### D-008: Single inference thread via ExecutorService
**Decision**: WhisperEngine uses a SingleThreadExecutor. whisper.cpp internally uses 4 threads (n_threads=4).
**Rationale**: Serialised inference prevents contention on the NEON units. 4 internal threads match the big cores on Snapdragon 855.

## Text Processing

### D-009: Stitcher N=10, minimum match ≥ 2 words
**Decision**: LCS operates on trailing/leading 10 words. Matches shorter than 2 words are treated as unmatched.
**Rationale**: SPEC §4.5. N=10 covers the 1s overlap window comfortably. 2-word minimum prevents false seams.

### D-010: Normaliser applies Rule 7 (annotation stripping) last
**Decision**: Despite Rule 7 being numbered last, it runs after spacing rules so annotation removal doesn't leave orphaned spaces.
**Rationale**: A trailing whitespace cleanup pass after Rule 7 handles residual gaps.

## Insertion

### D-011: Node resolution at insert time, not cached
**Decision**: Inserter resolves the target AccessibilityNodeInfo at the moment of insertion, never from a cached reference.
**Rationale**: SPEC §5.1. Between recording and insertion, the user may have changed apps or dismissed a field.

### D-012: Polled read-back 5 × 40ms
**Decision**: After ACTION_SET_TEXT, verify by polling node.text 5 times at 40ms intervals.
**Rationale**: SPEC §5.2. Some apps (Samsung Notes, Chrome) update the accessibility node asynchronously.

## UI

### D-013: Bubble size 49dp (≈ 1.3 cm physical)
**Decision**: Wolf face bubble is 49dp diameter.
**Rationale**: User specification for 1.3cm physical size. At standard 160dpi baseline, 49dp ≈ 1.3cm.

### D-014: Panel opens toward screen centre
**Decision**: When bubble is on left edge, panel opens right; on right edge, panel opens left.
**Rationale**: Maximises visible panel area regardless of bubble position.

### D-015: WaveformView at 30fps via Choreographer
**Decision**: Waveform animation driven by Choreographer.FrameCallback at ~33ms interval.
**Rationale**: SPEC §8.3. Choreographer synchronises with VSYNC. 30fps is sufficient for audio visualisation and avoids GPU overhead.

## State Machine

### D-016: Main-thread enforcement with auto-post
**Decision**: SessionStateMachine.on() checks Looper and auto-posts to main thread if called from a worker.
**Rationale**: Single-writer guarantee. All UI updates happen on the main thread.

### D-017: Illegal transitions throw in debug, log in release
**Decision**: BuildConfig.DEBUG checked via reflection to avoid direct import.
**Rationale**: Debug builds crash on illegal transitions for early detection. Release builds log and absorb.

## Build

### D-018: No INTERNET permission — explicit removal + static scan
**Decision**: tools:node="remove" on INTERNET in manifest, plus forbidden_api_scan.sh in CI.
**Rationale**: CLAUDE.md §3 hard invariant. Double enforcement: manifest merge block + source scan.

### D-019: arm64-v8a only
**Decision**: NDK abiFilters limited to arm64-v8a.
**Rationale**: Target device (Galaxy Note 10+) is arm64. whisper.cpp NEON optimisations require aarch64. No x86 emulator support needed.

### D-020: Git LFS for model binary
**Decision**: ggml-tiny.en-q5_1.bin tracked via Git LFS (.gitattributes).
**Rationale**: ~31 MB binary doesn't belong in git history. LFS stores it efficiently.
