# Android SDK Reference App

**简体中文** · [English](README.en.md)

这个应用是 Android SDK 的可运行消费者。它使用同级 `consumer` 工程和 `portable-pack` asset，演示会话创建、View 绑定、消息、导航、持久化、前后台、失败重载以及关闭后重开。测试签名只用于本地预览和验收。

## 运行

从核心仓库根目录生成 fixture、构建 native runtime 与 notices，再调用 Android SDK 包装层构建 consumer：

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

设备或模拟器必须已经启动，且 API 26+。构建需要 JDK 17、Gradle 8.13、Android SDK / Build Tools 36、NDK `27.1.12297006` 和 CMake 3.22.1。SDK 构建器会在输出目录生成独立 consumer、测试 APK 和校验回执。

## 操作与代码位置

顶部按钮对应 `ConsumerActivity.kt` 的真实 SDK 调用：

- `Reload` 重新加载 pack。
- `Detail` / `Back` 调用原生导航。
- `Echo` 发送消息并等待小程序回执。
- `Store`、`Flush`、`Read` 覆盖同步写入、持久化点和读取。
- `Pause` / `Resume` 转发宿主前后台状态。
- `Close` 先 `unbindAsync()` 再 `closeAsync()`。
- `Reopen` 用相同 storage key 创建新会话，验证数据边界。

Instrumentation 使用同一组控件和事件，入口为 `src/androidTest/.../SdkInstrumentation.kt`。它只报告本次设备、运行库和 SDK 版本的结果，不能替代官方 DevTools、GPU 或真机发布验收。
