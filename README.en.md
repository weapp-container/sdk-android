# Weapp Container Android SDK

First public publication is being prepared. Use these commands once this version is visible in its registry. Historical private archives and locks retain their original identities.

Version `0.1.0-preview.7.0` requires Android API 26+, arm64-v8a / x86_64 and Java 17. Resolve the public coordinate from Maven Central:

```kotlin
repositories { google(); mavenCentral() }
dependencies { implementation("io.github.weapp-container:weapp-container-android:0.1.0-preview.7.0") }
```

The AAR includes JNI bindings, both runtime ABIs and dependency licenses. Application installation requires no Node.js, Rust or private repository access. Java/Kotlin namespaces remain `com.sonofmagic.weapp.container.sdk`.

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
