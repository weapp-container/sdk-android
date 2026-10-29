# Android SDK preview

`com.sonofmagic.weapp:weapp-container-android:0.1.0-preview.5.1` embeds the real
QuickJS runtime and Skia GLES renderer in an Android View. Minimum Android is
API 26. The release AAR packages `arm64-v8a` and `x86_64`, JNI consumer R8 rules,
and its C++ runtime. Applications do not install Rust or build JNI.

## Build and consume

Use the standalone commands in [the root README](../README.md). Runtime archives are supplied independently under private binary terms; no parent source checkout or Rust compiler is required.

## Embedding

```kotlin
import com.sonofmagic.weapp.container.sdk.*

val session = WeappContainerSession(context, storageKey = "account-42:catalog")
session.eventListener = ContainerEventListener { event -> /* ready/navigation/message/error/closed */ }
session.registerHostMethod("host.profile", HostMethod { call, reply ->
    fetchProfileAsync { profile -> reply.resolve(profile) }
    call.onCancel = { cancelProfileFetch() }
})
val view = WeappContainerView(context)
parent.addView(view, layoutParams)
view.bind(session)
session.load(PackSource.Asset("portable-pack"))
    .whenComplete { _, error -> /* main thread */ }
```

`PackSource.Directory(File(...))` accepts an already downloaded/unpacked local
pack. Assets are copied into a private temporary directory and verified by the
shared SDK; temporary copies are removed after load. Remote executable URLs
are not accepted. `LoadOptions(route, options)` chooses the first route and
launch options. Failed replacement leaves the previous instance active and
reattaches its surface. Register host methods before requesting the first load.

The host chooses the stable storage key. Never derive it from a session ID or
pack hash. A key admits one writer; `closeAsync()` releases that lease after
persistence completes. Pass `null` deliberately for memory-only storage.

`WeappContainerView` is a TextureView, so it composes as an ordinary subrectangle
with native host UI. One session binds to one View; multiple independent sessions
can coexist. `unbind()` and View/Surface destruction preserve the session.
Rebinding presents the current frame without recreating the JavaScript isolate.
Framework adapters can await `unbindAsync()` before replacing an embedded View;
it completes after the worker confirms retirement of the old surface resources.
The SDK reads root WindowInsets without changing the host's listener. It owns
only its EGL context and target, and never terminates a shared EGL display.

First load waits for positive measured View dimensions, actual display density,
window insets and orientation before native session creation or module execution.
For a session without a View, supply `initialMetrics` or call
`setWindowMetrics(WindowMetrics(...))` with real host observations. No default
375×667 geometry is injected. Android safe-area portrait-origin semantics remain
unproven, so the adapter omits that optional field.

Forward Activity/feature lifecycle with `setForeground(false)` on pause and
`setForeground(true)` on resume. Resume waits for a fresh View observation before
calling App/Page show hooks. Hidden Views stop presentation and app timers while
the independent worker pump continues HTTP, host replies, cancellation, timeout
and storage completion when Android allows the process to execute.

All session operations return `CompletableFuture`; callbacks and public events
run on the main thread. Never block that thread with `get()`/`join()`. JavaScript,
JNI, EGL and the completion pump live on one dedicated HandlerThread. Host replies
are marshalled back to that thread; they never reenter a native callback.

```kotlin
session.sendMessage(JSONObject().put("type", "refresh"))
session.navigate("pages/details/index", JSONObject().put("id", "42"))
session.back()
session.flushStorage().thenCompose { session.closeAsync() }
view.unbind()
```

Host methods accept JSON values and reply with `HostReply.resolve(value)` or
`reject(HostErrorCode, message, retryable)`. `HostCall` exposes an opaque ID,
deadline, cancellation token and cancellation callback. Replies settle at most
once; cancelled and stale replies cannot affect a replacement instance.
`weappContainer.invoke/postMessage/onMessage` are declared in
`../types/weapp-container.d.ts`. `wx.request` uses the shared asynchronous HTTP
implementation. Upload, download and WebSocket are not preview capabilities.

Touch batches include all active/changed pointers and a single-pointer tap
recognizer; hardware key events are supported. Native text editing/IME,
accessibility semantics, longpress and advanced gesture recognition remain
preview limitations. `snapshot()` explicitly requests RGBA diagnostic readback;
normal GLES presentation never copies page pixels through JSON/Java.

## Validation

The build verifies the release AAR, Maven dependency resolution, an independent
consumer with R8 enabled, both ELF architectures, and all packaged native LOAD
segments' 16 KB alignment. The consumer uses only the published Maven coordinate.

```sh
node tools/device-smoke.mjs --serial emulator-5554
```

The device runner requires an explicitly named already-running device and refuses
to overwrite an installed consumer package. It installs and removes only its own
consumer/test APKs, leaving the emulator running. Instrumentation exercises actual
GLES, initial provider geometry, host Insets coexistence, asynchronous bridge/UTF-8,
timeout/cancel, real MotionEvent tap/scroll and JavaScript timers, navigation/back,
transactional reload, persistent reopen, detached resize, Activity pause/resume and
HTTP completion while hidden. The test consumer retains public APIs and Kotlin
helpers referenced only by its separate instrumentation APK; SDK internals still
undergo R8 optimization using the published consumer rules. Its receipt is target-specific; packaging checks alone
do not establish x86 device execution, physical hardware or performance parity.

When a renderer attachment observes a missing `GL_VERSION` or a current EGL context
that differs from this session's owner, the presenter retires its own EGL window
and context and attempts one fresh context. It never terminates the shared EGL
display or reloads the JavaScript session. A second invalid context returns an
error and attachment stays failed until an explicit load or View rebind; routine
layout/visibility observations cannot restart the recovery cycle. Ordinary renderer errors with a valid current context are not retried.
Error diagnostics retain the current/owned EGL handles and GL strings. Committed
load events are delivered even if renderer attachment fails.

The independent consumer also runs 12 rounds of concurrent two-session load/close
replacement. Its log receipt counts actual invalid-context recoveries separately
from this targeted instrumentation suite:

```sh
node tools/gles-recovery.mjs --serial emulator-5554 --offline
```

The suite uses real TextureView windows, EGL contexts, native sessions and Skia
presentation. A presenter-scoped test dependency unbinds the current EGL context
at the probe or during native attachment; there is no process-wide fault switch.
It verifies bounded recovery, persistent failure, no retry for ordinary errors,
event ordering, sibling rendering, and stable-key storage across recovery and
reopen. Passing injection tests does not establish that an intermittent driver's
original loss mechanism has been fully localized.
