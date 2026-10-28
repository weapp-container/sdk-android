# Weapp Container Android SDK

Version **0.1.0-preview.3** · [weapp-container organization](https://github.com/weapp-container) · [SDK contract](https://github.com/weapp-container/sdk-contract)

This independent repository contains the **Apache-2.0 host wrapper and consumer source**. The runtime implementation remains closed source. Its separately licensed binaries are obtained only from the private [`weapp-container/runtime-artifacts`](https://github.com/weapp-container/runtime-artifacts) repository. SDK source licensing does not grant a runtime binary license. Distributions retain `RUNTIME-LICENSE` and all third-party notices alongside the wrapper's `LICENSE`.

A fresh clone needs Node.js 24+, Python 3 and the platform toolchain below. It does **not** need a parent monorepo, Rust, Cargo, a git submodule or private implementation source.

This checkout targets **0.1.0-preview.3** for Dev Player. The checked-in
preview.2 runtime lock and its historical reports remain unchanged; they do not
supply preview.3 binaries or evidence. Default preparation rejects that version
mismatch. Use an explicitly reviewed preview.3 lock with `--manifest` once its
real archive hashes exist, or build the wrapper with an explicit local preview.3
`--native-root` and `--licenses-root`. No preview.2 fallback is performed.

## Obtain the native runtime

Use the reviewed `runtime-artifacts.lock.json` shipped with the matching SDK release. It locks the exact runtime version, platform, architecture, ABI **1**, artifact size and SHA-256. An absent pin, missing architecture, hash/version mismatch or unavailable engine is an error; there is no download of `latest`, software substitution or silent fallback.

Set `WEAPP_CONTAINER_RUNTIME_TOKEN` in your credential environment with read access to the private artifact repository. Do not place credentials in source files or CLI arguments. Then run:

Network artifact transfers have a single 15-minute deadline covering headers,
redirects and the complete body; release metadata has a separate 30-second
deadline. For slower connections, set `WEAPP_CONTAINER_RUNTIME_TIMEOUT_MS` to a
positive base-10 integer in milliseconds, up to `3600000` (one hour). Invalid
values return `ERR_RUNTIME_OPTIONS`; transfer failures and timeouts return
sanitized `ERR_RUNTIME_NETWORK`. Explicit API/CLI overrides are documented in the
[shared SDK contract](https://github.com/weapp-container/sdk-contract).

```sh
node tools/prepare-runtime.mjs
```

For an authorized offline copy use `--artifact-dir /absolute/runtime-archives`, or `WEAPP_CONTAINER_RUNTIME_ARTIFACT_DIR`. Files still undergo the same committed pin, hash, version and ABI checks. `--manifest /absolute/reviewed-lock.json` explicitly selects another reviewed lock; `--version` overrides are intentionally unavailable in this wrapper.

Preparation atomically installs verified inputs under `.runtime/install/native` and `.runtime/install/licenses`. It preserves the prior installation on failure. Generated archives, binaries, caches and credentials are excluded from Git. `--native-root` below also accepts an explicitly supplied local distribution with matching per-target `build.json`, SHA-256, the reviewed C header and runtime license; local input does not imply private-release authentication.

Exact runtime distributions retain the producer's original `build.json` and bind its bytes with `runtime-distribution.json`. The selection accounts for every producer artifact and can omit only the unused iOS dynamic library or Android static archive. Required libraries, the reviewed header and platform notices remain mandatory. Inputs without this selection receipt continue to verify the full producer inventory.

## Build and embed

Requirements: JDK 17+ (Java bytecode target 17), Android SDK platform/build-tools 36, NDK 27.1.12297006, CMake 3.22.1 and Gradle 8.9. Set `ANDROID_HOME`, optionally `ANDROID_NDK_HOME` and `GRADLE`. The Maven coordinate and Kotlin/JNI namespace remain stable.

```sh
node tools/build.mjs --native-root .runtime/install/native --licenses-root .runtime/install/licenses --pack /absolute/verified-portable-pack
```

This builds the wrapper's release AAR, local Maven repository and an independent R8-enabled consumer under `artifacts/android`. The consumer depends only on `com.sonofmagic.weapp:weapp-container-android:0.1.0-preview.3`. Applications need Android API 26+ and `arm64-v8a` or `x86_64`. The builder verifies both ELF architectures and 16 KB ELF/APK alignment; this does not establish a 16 KB device boot.

The library and sample consumer preserve the pinned core and `libc++_shared.so` bytes with explicit `jniLibs.keepDebugSymbols` rules. The builder checks that both ABI copies in the AAR and consumer APK exactly match the verified runtime distribution. The host JNI adapter has its own recorded hash.

`distribution.json` records the portable source identity, verified native inputs and Maven file hashes. Local commands and paths remain in the separate `validation.json` build evidence. Neither receipt claims a device test when `deviceExecution` is `not-run`.

Add `artifacts/android/maven` as a Maven repository and depend on that coordinate. `WeappContainerSession` owns state; `WeappContainerView` embeds its GLES TextureView as a normal subrectangle. [Embedding and lifecycle details](docs/EMBEDDING.md).

```sh
node tools/device-smoke.mjs --serial YOUR_ALREADY_RUNNING_TARGET
node tools/gles-recovery.mjs --serial YOUR_ALREADY_RUNNING_TARGET --native-root .runtime/install/native --licenses-root .runtime/install/licenses --pack /absolute/verified-portable-pack
```

Device tools require an explicitly selected existing target, refuse to overwrite their app packages, and remove only their own installs. They do not create/stop a device. Smoke commands are separate from building the SDK.

## Validation and provenance

```sh
npm test
```

These are wrapper input-contract tests, not runtime, GPU, device, performance or WeChat parity results. This split starts with a new version: historical preview.1 results are not attributed to preview.2. Platform build/UI reports must be regenerated against the exact pinned runtime before claiming that scope passed. Native IME, complete accessibility and advanced gestures remain separate limitations.

The 2026-10-27 preview.2 report for wrapper source `a448373` records 19 native checks on an arm64 API 30 emulator with host GLES, including actual EGL swap acknowledgement, MotionEvent input/scrolling, navigation, async completion, timeout/cancellation, reload rollback, storage reopening and concurrent session retirement. Both ABI binaries passed ELF and 16 KB alignment checks, and the release consumer passed R8 and exact runtime-byte validation. Physical-device, x86_64 execution, 16 KB device boot, performance and WeChat parity remain unmeasured. Device evidence is separate from the build receipt.

`ORIGIN.json` lists the allowlisted source paths, original source SHA-256, transformed destination SHA-256 and reviewed C-header contract hash. This repository includes no core implementation, Git history from the original repository, binary runtime, cache or credentials.
