# Android SDK Reference App

[简体中文](README.md) · **English**

This app is a runnable Android SDK consumer. It uses the sibling `consumer` project and `portable-pack` asset to demonstrate session creation, View binding, messages, navigation, durable storage, foreground state, transactional reload failure, and close/reopen. The test signature is for local preview and acceptance only.

## Run

From the core repository root, create the fixture, build the native runtime and notices, then invoke the Android SDK wrapper to build the consumer:

```sh
node sdk/contract/fixtures/make-acceptance-pack.mjs --out artifacts/hello-pack
node scripts/sdk-native-build.mjs --target aarch64-linux-android --out artifacts/sdk/native
node scripts/sdk-licenses.mjs --target aarch64-linux-android --features quickjs-native-android,skia-gpu-gles
node scripts/sdk-native-build.mjs --target x86_64-linux-android --out artifacts/sdk/native
node scripts/sdk-licenses.mjs --target x86_64-linux-android --features quickjs-native-android,skia-gpu-gles
node sdk/android/tools/build.mjs --native-root artifacts/sdk/native \
  --licenses-root artifacts/sdk/licenses --pack artifacts/hello-pack \
  --out artifacts/sdk/android
adb -s <serial> install -t artifacts/sdk/android/consumer/build/outputs/apk/release/*.apk
adb -s <serial> shell am start -n com.sonofmagic.weapp.container.sdkconsumer/com.sonofmagic.weapp.container.consumer.ConsumerActivity
```

The device or emulator must already be running and use API 26+. The build needs JDK 17, Gradle 8.13, Android SDK / Build Tools 36, NDK `27.1.12297006`, and CMake 3.22.1. The SDK builder creates the independent consumer, test APK, and verification receipt in its output directory.

## Controls and Source

The top buttons call the real SDK operations in `ConsumerActivity.kt`:

- `Reload` loads the pack again.
- `Detail` / `Back` exercise native navigation.
- `Echo` sends a message and waits for a mini-program reply.
- `Store`, `Flush`, and `Read` cover the write, persistence-point, and read path.
- `Pause` / `Resume` forward host foreground state.
- `Close` calls `unbindAsync()` before `closeAsync()`.
- `Reopen` creates a new session with the same storage key and verifies the boundary.

Instrumentation uses the same controls and events through `src/androidTest/.../SdkInstrumentation.kt`. Results apply only to the device, runtime, and SDK version recorded for that run. They do not replace official DevTools, GPU, or physical-device release acceptance.
