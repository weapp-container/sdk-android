# Weapp Container Android SDK

运行库发行工具已迁入 [核心仓库](https://github.com/weapp-container/weapp-container/tree/main/tools/runtime-artifacts)。新清单使用核心仓库的精确版本 Release；用 `--manifest` 显式选取。当前默认锁继续读取旧 `runtime-artifacts` 的历史版本，版本、哈希及随附许可不变。旧仓库归档后只保留历史下载；无跨仓库回退。私有仓库下载仍需对应读取权限，源码许可不代表发行物公开可用。

核心运行时与 SDK 自有源码采用 Apache-2.0，可按[主仓库源码构建说明](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-repositories.md)构建当前运行库。现有预编译归档保留原许可、访问要求及精确版本和哈希锁；本次源码更新不发布替代二进制。

**简体中文** · [English](README.en.md)

在 Android 应用中嵌入小程序页面。SDK 提供 Kotlin 会话、原生 `TextureView`、宿主消息通道和本地持久化，使用 QuickJS 执行 JavaScript、Skia GLES 渲染页面。

当前源码版本：**0.1.0-preview.6.0**。SDK 封装源码采用 [Apache-2.0](LICENSE)；运行时源码在主仓库按 Apache-2.0 提供。本仓库包含 SDK 包装层源码，运行库单独构建或安装。

## 准备环境

| 项目 | 要求 |
| --- | --- |
| 应用系统 | Android API 26+ |
| 原生架构 | `arm64-v8a`、`x86_64` |
| 构建工具 | Node.js 24+、Python 3、JDK 17+、Gradle 8.9 |
| Android 工具链 | SDK / Build Tools 36、NDK `27.1.12297006`、CMake `3.22.1` |

配置 `ANDROID_HOME`；可用 `ANDROID_NDK_HOME` 和 `GRADLE` 指定工具路径。应用接入使用预编译运行库，无需 Rust 或核心源码。

## 1. 获取运行库

```sh
git clone https://github.com/weapp-container/sdk-android.git
cd sdk-android
```

获得运行库访问授权后，通过凭据环境提供 `WEAPP_CONTAINER_RUNTIME_TOKEN`，权限限定为私有产物仓库的 **Contents: read**。不要把凭据写进源码或命令参数。

```sh
node tools/prepare-runtime.mjs
```

使用已获授权的离线归档：

```sh
node tools/prepare-runtime.mjs --artifact-dir /absolute/runtime-archives
```

工具按 [runtime-artifacts.lock.json](runtime-artifacts.lock.json) 校验 **0.1.0-preview.6.0**、ABI 1、架构、大小和 SHA-256，安装到 `.runtime/install/native` 和 `.runtime/install/licenses`。缺少归档、无下载权限或校验不符时会失败；已有安装在失败时保留。锁文件中的 pin 不代表运行库可公开下载。

自定义归档清单使用 `--manifest /absolute/reviewed-lock.json`。慢速网络可通过 `WEAPP_CONTAINER_RUNTIME_TIMEOUT_MS` 调整下载超时，范围为 `1`–`3600000` 毫秒，默认 15 分钟。

## 2. 构建 AAR 与 Maven 仓库

准备一份已经构建并校验的 portable pack 目录：

```sh
node tools/build.mjs --native-root .runtime/install/native --licenses-root .runtime/install/licenses --pack /absolute/verified-portable-pack
```

输出位于 `artifacts/android`，包括 release AAR、本地 `maven/` 仓库、独立 R8 consumer 及校验报告。`--pack` 用于构建该 consumer，不从核心仓库自动寻找样例。

在应用的 `settings.gradle.kts` 中加入生成的仓库，路径按实际目录调整：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("/absolute/sdk-android/artifacts/android/maven") }
    }
}
```

在应用的 `build.gradle.kts` 中添加依赖，并保留已校验运行库的原始字节：

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

这里使用本地构建的 Maven 仓库，不要求该坐标已发布到公共 Maven 服务。consumer 的 release 使用测试签名，正式应用需配置自己的签名。

## 3. 嵌入页面

将 portable pack 的完整内容放到应用的 `src/main/assets/portable-pack/`。在主线程创建并挂载 View：

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

`PackSource.Directory(File(...))` 可加载宿主已下载、解包的本地目录。`LoadOptions(route = "pages/detail/index", options = JSONObject())` 可传入初始路由和启动参数。首次加载等待 View 的真实布局和窗口信息；把 View 挂到窗口后再等待加载完成。

## 4. 通信与导航

```kotlin
session.sendMessage(JSONObject().put("type", "refresh"))
session.navigate("pages/detail/index", JSONObject().put("id", "42"))
session.back()
```

小程序侧使用 `weappContainer.invoke` 调用宿主方法，使用 `postMessage` / `onMessage` 交换消息，类型见 [weapp-container.d.ts](types/weapp-container.d.ts)。宿主方法应在首次 `load` 前注册。异步任务通过 `call.onCancel` 取消，使用 `reply.resolve` / `reply.reject` 完成；已取消或旧会话的回复不会影响新实例。

## 5. 生命周期与存储

- 宿主暂停、恢复时调用 `session.setForeground(false)` / `session.setForeground(true)`。
- `storageKey` 使用稳定的业务/账号标识，同一 key 同时只允许一个写入者；传 `null` 选择纯内存存储。
- `view.unbindAsync()` 等待旧渲染资源解绑，保留会话，可用于替换 View。
- 业务结束时等待 `session.closeAsync()` 完成；它会持久化数据并释放原生资源。需单独建立持久化点时调用 `session.flushStorage()`。

会话操作返回 `CompletableFuture`，公共回调在主线程执行。不要在主线程调用 `get()` / `join()` 等待结果。一个会话同时绑定一个 View；独立会话可并存。

```kotlin
session.flushStorage().thenCompose { session.closeAsync() }
    .whenComplete { _, error ->
        if (error != null) android.util.Log.e("Container", "关闭失败", error)
    }
```

## 验证与更多用法

```sh
npm test
node tools/device-smoke.mjs --serial YOUR_ALREADY_RUNNING_TARGET
```

`npm test` 检查封装层输入契约。设备测试需先构建 consumer，并显式指定已运行的设备；工具只安装、清理本次测试应用。构建报告不代表真机、GPU 或性能验收通过，各端结果需绑定实际运行库独立验证。

- [嵌入、窗口尺寸、触摸与生命周期](docs/EMBEDDING.md)
- [宿主服务：设备能力、身份、支付与权限](HOST-SERVICES.md)
- [宿主服务接入示例](examples/host-services/README.md)
- [跨端会话契约](CONTRACT.md)
- [源文件清单](SOURCE-INVENTORY.json) 与 [历史导出记录](ORIGIN.json)

运行能力取决于配套运行库及其分发策略；`restricted-showcase` 只允许编译时授权的演示包与 API，不能通过宿主配置重新开启禁用能力。原生输入法、无障碍与复杂手势的支持范围见嵌入文档。分发应用时保留 SDK `LICENSE`、运行库 `RUNTIME-LICENSE` 和第三方声明。
