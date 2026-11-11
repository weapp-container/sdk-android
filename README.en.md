# Weapp Container Android SDK

The core runtime and SDK source are available under Apache-2.0. Build the current runtime from the [core source guide](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-repositories.md). Existing prebuilt archives retain their original licenses, access requirements and exact version/hash pins; this source update does not publish replacement binaries.

[简体中文](README.md) · **English**

Embed mini-program pages in an Android app with a Kotlin session, a native `TextureView`, host messaging, and local persistence. JavaScript runs in QuickJS; Skia GLES renders the page.

Source version: **0.1.0-preview.6.0**. The wrapper source is [Apache-2.0](LICENSE). Current runtime source is Apache-2.0 in the core repository. This SDK repository contains wrapper source; runtime binaries are built or installed separately.

## Requirements

| Component | Requirement |
| --- | --- |
| Application | Android API 26+ |
| Architectures | `arm64-v8a`, `x86_64` |
| Build tools | Node.js 24+, Python 3, JDK 17+, Gradle 8.9 |
| Android toolchain | SDK / Build Tools 36, NDK `27.1.12297006`, CMake `3.22.1` |

Set `ANDROID_HOME`. Use `ANDROID_NDK_HOME` and `GRADLE` to select tool paths if needed. Integration uses prebuilt runtime binaries without a Rust toolchain or core source.

## 1. Obtain the runtime

```sh
git clone https://github.com/weapp-container/sdk-android.git
cd sdk-android
```

After obtaining runtime access, supply `WEAPP_CONTAINER_RUNTIME_TOKEN` through your credential environment, restricted to **Contents: read** on the private artifact repository. Keep credentials out of source files and command arguments.

```sh
node tools/prepare-runtime.mjs
```

For authorized offline archives:

```sh
node tools/prepare-runtime.mjs --artifact-dir /absolute/runtime-archives
```

The installer checks **0.1.0-preview.6.0**, ABI 1, architecture, size, and SHA-256 against [runtime-artifacts.lock.json](runtime-artifacts.lock.json), then installs to `.runtime/install/native` and `.runtime/install/licenses`. Missing archives, denied access, or integrity mismatches fail the operation and preserve the previous installation. A committed pin does not imply public download access.

Use `--manifest /absolute/reviewed-lock.json` for an explicitly reviewed archive manifest. `WEAPP_CONTAINER_RUNTIME_TIMEOUT_MS` adjusts the download deadline from `1` to `3600000` milliseconds; the default is 15 minutes.

## 2. Build the AAR and Maven repository

Supply a previously built and verified portable pack directory:

```sh
node tools/build.mjs --native-root .runtime/install/native --licenses-root .runtime/install/licenses --pack /absolute/verified-portable-pack
```

`artifacts/android` contains the release AAR, local `maven/` repository, independent R8 consumer, and validation reports. `--pack` supplies that consumer; the builder does not search a core checkout for examples.

Add the generated repository to your app's `settings.gradle.kts`, adjusting the path:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("/absolute/sdk-android/artifacts/android/maven") }
    }
}
```

Add the dependency in your app's `build.gradle.kts` and preserve the verified runtime bytes:

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
    implementation("com.sonofmagic.weapp:weapp-container-android:0.1.0-preview.6.0")
}
```

This uses your locally built repository and does not require a public Maven publication. The sample consumer uses test signing for its release variant; configure your own signing for a production application.

## 3. Embed a page

Copy the complete portable pack into your app's `src/main/assets/portable-pack/`. Create and mount the view on the main thread:

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
        if (error != null) android.util.Log.e("Container", "Load failed", error)
    }
    return session
}
```

Use `PackSource.Directory(File(...))` for a local pack already downloaded and unpacked by the host. `LoadOptions(route = "pages/detail/index", options = JSONObject())` sets the initial route and launch options. Initial loading waits for real view layout and window metrics, so mount the view before awaiting completion.

## 4. Messaging and navigation

```kotlin
session.sendMessage(JSONObject().put("type", "refresh"))
session.navigate("pages/detail/index", JSONObject().put("id", "42"))
session.back()
```

Mini-program code calls host methods through `weappContainer.invoke` and exchanges messages through `postMessage` / `onMessage`. See [weapp-container.d.ts](types/weapp-container.d.ts). Register host methods before the first `load`. Cancel asynchronous work through `call.onCancel` and settle it with `reply.resolve` / `reply.reject`; cancelled and stale replies cannot affect a replacement instance.

## 5. Lifetime and storage

- Forward host pause/resume with `session.setForeground(false)` / `session.setForeground(true)`.
- Use a stable application/account `storageKey`. Each key admits one writer; pass `null` for memory-only storage.
- Await `view.unbindAsync()` before replacing a view. It retires the old presentation resources and preserves the session.
- Await `session.closeAsync()` when the feature ends to persist data and release native resources. Use `session.flushStorage()` for an explicit durability point.

Session operations return `CompletableFuture`; public callbacks run on the main thread. Do not block that thread with `get()` / `join()`. One session binds to one view at a time; independent sessions may coexist.

```kotlin
session.flushStorage().thenCompose { session.closeAsync() }
    .whenComplete { _, error ->
        if (error != null) android.util.Log.e("Container", "Close failed", error)
    }
```

## Validation and further reading

```sh
npm test
node tools/device-smoke.mjs --serial YOUR_ALREADY_RUNNING_TARGET
```

`npm test` checks wrapper input contracts. Build the consumer before device tests and explicitly select an already-running device; the tool installs and cleans up only its own test apps. Build reports do not establish physical-device, GPU, or performance acceptance. Validate each target against its actual runtime independently.

- [Embedding, window metrics, touch, and lifetime](docs/EMBEDDING.md)
- [Host services: devices, identity, payments, and permissions](HOST-SERVICES.md)
- [Host service integration example](examples/host-services/README.md)
- [Cross-platform session contract](CONTRACT.md)
- [Source inventory](SOURCE-INVENTORY.json) and [historical extraction receipt](ORIGIN.json)

Capabilities depend on the paired runtime and its distribution policy. `restricted-showcase` admits only compiled-in demo packs and APIs; host configuration cannot re-enable disabled capabilities. See the embedding guide for native IME, accessibility, and advanced gesture limitations. Retain the SDK `LICENSE`, runtime `RUNTIME-LICENSE`, and third-party notices when distributing an application.
