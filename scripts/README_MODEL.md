# Model Asset

VoxWolf ships `ggml-tiny.en-q5_1.bin` (whisper tiny.en, q5_1 quantisation).

## Setup

1. Download `ggml-tiny.en-q5_1.bin` from the whisper.cpp releases or convert it yourself.
2. Place it at `app/src/main/assets/models/ggml-tiny.en-q5_1.bin`.
3. Compute the SHA-256: `sha256sum app/src/main/assets/models/ggml-tiny.en-q5_1.bin`
4. Update `gradle/libs.versions.toml` → `modelSha256` with the real digest.
5. Track the file with Git LFS: `git lfs track "app/src/main/assets/models/*.bin"`
6. Commit.

The build will fail if the model file is absent. This is intentional — the model
is never downloaded at runtime, and there is no network fallback.
