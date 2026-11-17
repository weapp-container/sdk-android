<a id="android-hosting-capabilities"></a>
# Android Host Capabilities

[简体中文](HOST-SERVICES.md) · **English**

The adapters require a native runtime that matches the current host-services protocol. Before loading a pack, the host configures trusted runtime context and installs the native capabilities it needs. APIs without an installed implementation remain unsupported.

```kotlin
val toolkit = AndroidNativeDeviceServices(activity,
    networkOrigins = setOf("https://api.example.com", "wss://api.example.com"),
    permissions = permissionBroker)

session.configureHostServices(trustedConfiguration).thenCompose {
    toolkit.install(session)
}.thenCompose { session.load(pack, options) }
```

Keep the toolkit alive until the session closes. After an Activity recreation, call `toolkit.rebindActivity(activityOrNull)` on the main thread. The host connects system permissions through its permission broker; app-level authorization and operating-system permissions are checked separately.

Native capabilities can be installed as needed, including managed files, HTTP/WebSocket, location and sensors, Bluetooth and beacons, camera and media, recording, NFC, Wi-Fi, clipboard, battery and window controls. Each capability registers only APIs implemented for the available device, permissions and host configuration. Unsupported options return explicit errors.

Files use a managed virtual path; guest code never receives a host filesystem path. Network access is limited by host-configured origins, request sizes and redirect policy. Device work belongs to the active session owner. Hiding, cancellation, close or owner changes stop the relevant work and wait for native resources to finish releasing before cleanup completes.

Standard mini-program APIs and business extensions share the generic registration boundary: `registerApiHandler` receives API calls and `registerHostMethod` registers host business methods. The SDK leaves application services to the host.

Validate devices, system permissions and hardware behavior on target devices. Source compilation and local simulation do not replace runtime acceptance.
