# Weapp Container Android SDK

[简体中文](README.md) · **English**

<a id="公开发行候选preview7"></a>
## Public distribution candidate: preview.7

The current core builder and these source examples use preview.7. Core runtime, iOS, Desktop and public CocoaPods Specs downloads are available; npm, Maven Central and pub.dev publication is incomplete. Check the [release status](https://github.com/weapp-container/weapp-container/blob/main/docs/public-sdk-release.en.md) before using installation commands. Match wrapper and runtime versions. Source availability, package publication, compilation and device acceptance are recorded separately.

First public publication is being prepared. Use these commands once this version is visible in its registry. Historical private archives and locks retain their original identities.

Version `0.1.0-preview.7.0` requires Android API 26+, arm64-v8a / x86_64 and Java 17. Resolve the public coordinate from Maven Central:

```kotlin
repositories { google(); mavenCentral() }
dependencies { implementation("io.github.weapp-container:weapp-container-android:0.1.0-preview.7.0") }
```

The AAR includes JNI bindings, both runtime ABIs and dependency licenses. Application installation requires no Node.js, Rust or private repository access. Java/Kotlin namespaces remain `com.sonofmagic.weapp.container.sdk`.

<a id="已复核的源码构建版本"></a>
### Reviewed source-build checkout

```sh
git clone https://github.com/weapp-container/sdk-android.git sdk-android-source
cd sdk-android-source
git checkout ca8c21a4d4a075b7180761875287bf9c7abaee53
```



[Documentation](https://container.weapp.dev/en/docs/) · [Build from source](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-source-build.en.md) · [APIs and contracts](https://github.com/weapp-container/sdk-contract/blob/main/README.en.md)

Embed mini-program pages within Android applications. The SDK provides Kotlin session management, native `TextureView`, host message channel, and local persistence, using QuickJS to execute JavaScript and Skia GLES to render pages.

Reviewed source example version: **0.1.0-preview.7.0**. The SDK wrapper source is licensed under [Apache-2.0](LICENSE); runtime source is provided in the main repository under Apache-2.0. This repository contains the SDK wrapper source code, while the runtime library is built or installed separately.

<a id="从当前源码开始"></a>
## Start from Source Code

For initial integration, prefer using the public core repository and its fixed SDK commit—no download token is required for historical archives:

```sh
git clone --recurse-submodules https://github.com/weapp-container/weapp-container.git
cd weapp-container
corepack enable
pnpm install --frozen-lockfile
node sdk/contract/fixtures/make-acceptance-pack.mjs --out artifacts/hello-pack
```

Then prepare the platform-specific runtime library and notices according to the [source build instructions](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-source-build.en.md), and use the build tool from this repository to generate the integration package. The platform toolchain remains a required dependency; public source availability does not imply that public artifact repositories contain downloadable binaries. Existing native mini-program or framework build artifacts can be packaged via an independent portable pack tool; the weapp-vite is not required.

The following retains the installation, view, and lifecycle instructions for the standalone SDK. Commands involving `prepare-runtime` / `prepare-native` belong to explicit archival paths and must use a verified companion manifest. Historical default locks may not be downloadable; see end-of-documentation notes for details.

<a id="准备环境"></a>
## Prepare Environment

| Project | Requirement |
|--------|-------------|
| Application System | Android API 26+ |
| Native Architecture | `arm64-v8a`, `x86_64` |
| Build Tools | Node.js 24+, Python 3, JDK 17+, Gradle 8.9 |
| Android Toolchain | SDK / Build Tools 36, NDK `27.1.12297006`, CMake `3.22.1` |

Configure `ANDROID_HOME`; use `ANDROID_NDK_HOME` and `GRADLE` to specify tool paths. Link the application to the built runtime library; only the runtime library build process requires Rust and core source code.

<a id="1-获取运行库"></a>
## 1. Obtain the Runtime Library

Execute the following command in the root directory of a clean checkout of the public core repository:

```sh
pnpm sdk:build:android -- --pack artifacts/hello-pack
```

This command builds the runtime library, collects notices, and invokes the fixed SDK wrapper to generate the integration package. Output is generated at `artifacts/sdk/android/`. Full toolchain and input validation are detailed in the [source build guide](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-source-build.en.md). Historical archive installation is an optional compatibility path mentioned at the end.

<a id="2-构建-aar-与-maven-仓库"></a>
## 2. Build AAR and Maven Repository

The source build above produces the release AAR, local `maven/` repository, standalone R8 consumer, and verification report in `artifacts/sdk/android/`. The `--pack` argument supplies the verified portable pack used by that consumer. Wrapper-only build commands for a standalone SDK checkout are in the historical archive section.

Add the generated repository to your application's `settings.gradle.kts`, adjusting the path as needed:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("/absolute/weapp-container/artifacts/sdk/android/maven") }
    }
}
```

Add the dependency to your application's `build.gradle.kts` and retain the original bytes of the verified runtime library:

```kotlin
android {
    defaultConfig { minSdk = 26 }
    packaging {
        jniLibs.keepDebugSymbols += setOf(
            "**/libweapp_container_sdk.so", "**/libc++_shared.so",
        )
    }
}
dependencies {
    implementation("io.github.weapp-container:weapp-container-android:0.1.0-preview.7.0")
}
```

Here, a locally built Maven repository is used; the coordinate does not need to be published to a public Maven service. The consumer's release uses a test signature; for production applications, a custom signature must be configured.

<a id="3-嵌入页面"></a>
## 3. Embed Page

Place the complete content of the portable pack into your application's `src/main/assets/portable-pack/`. Create and attach a View on the main thread:

```kotlin
import android.content.Context
import android.view.ViewGroup
import com.sonofmagic.weapp.container.sdk.*
import org.json.JSONObject

fun mountContainer(context: Context, parent: ViewGroup): WeappContainerSession {
    val session = WeappContainerSession(context, storageKey = "catalog:account-42")
    session.eventListener = ContainerEventListener { event ->
        android.util.Log.d("Container", event.toString())
    }
    session.registerHostMethod("host.profile", HostMethod { _, reply ->
        reply.resolve(JSONObject().put("name", "Ada"))
    })
    val view = WeappContainerView(context)
    parent.addView(view, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ))
    view.bind(session)
    session.load(PackSource.Asset("portable-pack")).whenComplete { _, error ->
        if (error != null) android.util.Log.e("Container", "加载失败", error)
    }
    return session
}
```

`PackSource.Directory(File(...))` can load a locally downloaded and unpacked directory from the host. `LoadOptions(route = "pages/detail/index", options = JSONObject())` can pass an initial route and launch parameters. The first load waits for the View's actual layout and window information; after attaching the View to the window, it waits for loading to complete.

<a id="4-通信与导航"></a>
## 4. Communication and Navigation

```kotlin
session.sendMessage(JSONObject().put("type", "refresh"))
session.navigate("pages/detail/index", JSONObject().put("id", "42"))
session.back()
```

The mini-program side uses `weappContainer.invoke` to invoke host methods, and uses `postMessage` / `onMessage` to exchange messages, with types defined in [weapp-container.d.ts](types/weapp-container.d.ts). Host methods must be registered before the first `load`. Asynchronous tasks can be canceled via `call.onCancel`; completion is signaled via `reply.resolve` / `reply.reject`. Replies from canceled or stale sessions do not affect new instances.

<a id="5-生命周期与存储"></a>
## 5. Lifecycle and Storage

- When the host is paused or resumed, invoke `session.setForeground(false)` / `session.setForeground(true)`.
- `storageKey` uses a stable business/account identifier; only one writer is allowed per key; pass `null` to select in-memory storage.
- `view.unbindAsync()` waits for unbinding of previous rendering resources and retains the session, which can be used to replace the View.
- On application termination, wait for `session.closeAsync()` to complete; it persists data and releases native resources. If a separate persistence point is required, call `session.flushStorage()`.

Session operations return `CompletableFuture`; public callbacks are executed on the main thread. Do not call `get()` / `join()` to wait for results on the main thread. One session is bound to one View; independent sessions can coexist.

```kotlin
session.flushStorage().thenCompose { session.closeAsync() }
    .whenComplete { _, error ->
        if (error != null) android.util.Log.e("Container", "关闭失败", error)
    }
```


<a id="验证与更多用法"></a>
## Verification and Additional Usage

```sh
npm test
node tools/device-smoke.mjs --serial YOUR_ALREADY_RUNNING_TARGET
```

`npm test` validates the input contract of the wrapper layer. Device testing requires building the consumer and explicitly specifying a running device; the tool only installs and cleans the test application. The build report does not indicate successful device, GPU, or performance validation. Actual runtime results must be independently verified against the specific runtime library.

- [Embedding, Window Size, Touch, and Lifecycle](docs/EMBEDDING.en.md)
- [Host Services: Device Capabilities, Identity, Payment, and Permissions](HOST-SERVICES.en.md)
- [Host Service Integration Example](examples/host-services/README.en.md)
- [Cross-end Session Contract](CONTRACT.en.md)
- [Source File List](SOURCE-INVENTORY.json) and [Historical Export Records](ORIGIN.json)
The runtime capabilities depend on the accompanying runtime libraries and their distribution strategy; `restricted-showcase` permits only demonstration packages and APIs authorized at compile time, and no capabilities can be re-enabled via host configuration. Support for native input methods, accessibility, and complex gestures is detailed in the embedded documentation. When distributing applications, retain SDK `LICENSE`, runtime library `RUNTIME-LICENSE`, and third-party declarations.

<a id="历史归档与迁移"></a>
## Historical Archiving and Migration

Historical Release Audit (2026-11-12): Both default runtime pins do not match the currently named assets in the old repository; default online preparations will be rejected by integrity checks. Please build from clean source code matching the required version, or explicitly select a standalone-validated manifest via `--manifest`; do not simply modify old hashes. [Differences and Migration Records](https://github.com/weapp-container/weapp-container/blob/main/docs/runtime-artifacts-migration.en.md).

The runtime distribution tooling has been migrated to the [Core Repository](https://github.com/weapp-container/weapp-container/tree/main/tools/runtime-artifacts). New manifests use the exact version from the Core Repository's release; use `--manifest` to explicitly select. The updated downloader redirects the old lock's `runtime-artifacts` identifier to a private `weapp-container/runtime-artifacts-private-archive`, permitting only nine frozen versions, preserving the original version, hash, and accompanying licenses, with receipt records of the actual repository. Legacy downloaders must be upgraded; the new repository with the same name retains only deprecated tooling source code. No cross-repository rollback is available; private archives still require read permissions, and source code licensing does not imply historical releases are publicly available.

Core runtime and SDK source code are licensed under Apache-2.0 and can be built as per the [Main Repository Source Code Build Instructions](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-source-build.en.md) to produce the current runtime. Existing precompiled archives retain their original licensing, access requirements, and exact version and hash locks; no alternative binaries are released in this source update.

<a id="可选安装已审核的历史归档"></a>
### Optional: Install a Historically Audited Archive

```sh
git clone https://github.com/weapp-container/sdk-android.git
cd sdk-android
git checkout ca8c21a4d4a075b7180761875287bf9c7abaee53
```

After obtaining runtime access authorization, provide credentials via the environment variable `WEAPP_CONTAINER_RUNTIME_TOKEN`, with permissions restricted to **Contents: read** for private product repositories. Do not embed credentials in source code or command-line arguments.

```sh
node tools/prepare-runtime.mjs
```

Use an authorized offline archive:

```sh
node tools/prepare-runtime.mjs --artifact-dir /absolute/runtime-archives
```

The tool validates **0.1.0-preview.7.0**, ABI 1, architecture, size, and SHA-256 against the [current public lock](runtime-artifacts.0.1.0-preview.7.0.lock.json), and installs it into `.runtime/install/native` and `.runtime/install/licenses`. Installation fails if the archive is missing, download permissions are unavailable, or validation does not pass; existing installations are preserved during failure. The pin in the lock file does not imply that the runtime is publicly downloadable.

Custom archive manifests use `--manifest /absolute/reviewed-lock.json`. On slow networks, download timeouts can be adjusted via `WEAPP_CONTAINER_RUNTIME_TIMEOUT_MS`, ranging from `1` to `3600000` milliseconds, with a default of 15 minutes.

After archive installation, a packaged layer can be built from an independent SDK checkout:

```sh
node tools/build.mjs --native-root .runtime/install/native --licenses-root .runtime/install/licenses --pack /absolute/verified-portable-pack
```

Source candidate `0.1.0-preview.7.1` is prepared for core runtime draft automation. Public downloads and immutable locks still refer to `0.1.0-preview.7.0`; this source update does not publish a new SDK package.
