# Weapp Container Android SDK

**简体中文** · [English](README.en.md)

<a id="public-distribution-candidate-preview7"></a>
## 公开发行候选：preview.7

下列安装示例描述 preview.7 发行候选，使用前须确认对应注册源或 Release 已提供该版本；源码公开不代表包已发布或设备验收完成。本文后续源码构建示例使用下面固定的 preview.6 checkout。当前核心构建器仍输出 preview.6，不要将该运行库与 preview.7 包装层混用。

首次公开发布正在准备；安装命令须在对应注册源出现本版本后使用。历史私有归档及其锁文件保持原样。

版本 `0.1.0-preview.7.0`；Android API 26+，arm64-v8a / x86_64；Java 17。配置 Maven Central 后直接依赖公开坐标：

```kotlin
repositories { google(); mavenCentral() }
dependencies { implementation("io.github.weapp-container:weapp-container-android:0.1.0-preview.7.0") }
```

AAR 包含 JNI 接入层、两种 ABI 的运行库和第三方许可。普通应用安装无需 Node.js、Rust 或私有仓库授权；Java/Kotlin 包名保持 `com.sonofmagic.weapp.container.sdk`。

<a id="reviewed-source-build-checkout"></a>
### 已复核的源码构建版本

```sh
git clone https://github.com/weapp-container/sdk-android.git sdk-android-source
cd sdk-android-source
git checkout be2a7c6085bbf2562f0a351855c69ee5167b3770
```



[开发文档](https://container.weapp.dev/docs/) · [源码构建](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-source-build.md) · [API 与协议](https://github.com/weapp-container/sdk-contract/blob/main/README.md)

在 Android 应用中嵌入小程序页面。SDK 提供 Kotlin 会话、原生 `TextureView`、宿主消息通道和本地持久化，使用 QuickJS 执行 JavaScript、Skia GLES 渲染页面。

已复核源码示例版本：**0.1.0-preview.6.0**。SDK 封装源码采用 [Apache-2.0](LICENSE)；运行时源码在主仓库按 Apache-2.0 提供。本仓库包含 SDK 包装层源码，运行库单独构建或安装。

<a id="start-from-source-code"></a>
## 从当前源码开始

首次接入优先使用公开的核心仓库及其固定 SDK 提交，不需要历史归档的下载 token：

```sh
git clone --recurse-submodules https://github.com/weapp-container/weapp-container.git
cd weapp-container
corepack enable
pnpm install --frozen-lockfile
node sdk/contract/fixtures/make-acceptance-pack.mjs --out artifacts/hello-pack
```

随后按[源码构建说明](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-source-build.md)准备目标平台的运行库和 notices，再用本仓库的构建工具生成接入包。平台工具链仍是必要依赖；源码公开不表示公共包仓库已有可下载的二进制。已有原生小程序或框架构建产物可以由独立 portable pack 工具打包，不要求 weapp-vite。

下面保留独立 SDK 的安装、视图与生命周期说明。涉及 `prepare-runtime` / `prepare-native` 的命令属于显式归档路径，必须使用核验过的配套 manifest；历史默认锁可能无法下载，详见文末说明。

<a id="prepare-environment"></a>
## 准备环境

| 项目 | 要求 |
| --- | --- |
| 应用系统 | Android API 26+ |
| 原生架构 | `arm64-v8a`、`x86_64` |
| 构建工具 | Node.js 24+、Python 3、JDK 17+、Gradle 8.9 |
| Android 工具链 | SDK / Build Tools 36、NDK `27.1.12297006`、CMake `3.22.1` |

配置 `ANDROID_HOME`；可用 `ANDROID_NDK_HOME` 和 `GRADLE` 指定工具路径。应用链接构建后的运行库；只有运行库构建过程需要 Rust 和核心源码。

<a id="1-obtain-the-runtime-library"></a>
## 1. 获取运行库

在公开核心仓库的干净 checkout 根目录执行：

```sh
pnpm sdk:build:android -- --pack artifacts/hello-pack
```

该命令从当前源码构建运行库，收集 notices，并调用固定的 SDK 包装层生成接入包。输出在 `artifacts/sdk/android/`。完整工具链和输入检查见[源码构建](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-source-build.md)。历史归档安装属于文末的可选兼容路径。

<a id="2-build-aar-and-maven-repository"></a>
## 2. 构建 AAR 与 Maven 仓库

上一节的源码构建已在 `artifacts/sdk/android/` 生成 release AAR、本地 `maven/` 仓库、独立 R8 consumer 及校验报告。`--pack` 使用前面生成并校验的 portable pack 构建该 consumer。独立 SDK checkout 的包装层构建命令见历史归档章节。

在应用的 `settings.gradle.kts` 中加入生成的仓库，路径按实际目录调整：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("/absolute/weapp-container/artifacts/sdk/android/maven") }
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

<a id="3-embed-page"></a>
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

<a id="4-communication-and-navigation"></a>
## 4. 通信与导航

```kotlin
session.sendMessage(JSONObject().put("type", "refresh"))
session.navigate("pages/detail/index", JSONObject().put("id", "42"))
session.back()
```

小程序侧使用 `weappContainer.invoke` 调用宿主方法，使用 `postMessage` / `onMessage` 交换消息，类型见 [weapp-container.d.ts](types/weapp-container.d.ts)。宿主方法应在首次 `load` 前注册。异步任务通过 `call.onCancel` 取消，使用 `reply.resolve` / `reply.reject` 完成；已取消或旧会话的回复不会影响新实例。

<a id="5-lifecycle-and-storage"></a>
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

<a id="verification-and-additional-usage"></a>
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

<a id="historical-archiving-and-migration"></a>
## 历史归档与迁移

历史发行审计（2026-11-12）：两个默认 runtime pin 均与旧仓库当前同名资产不符，默认在线准备会被完整性校验拒绝。请从匹配的干净源码构建，或通过 `--manifest` 显式选取独立审核过的配套清单；不要仅修改旧哈希。[差异与迁移记录](https://github.com/weapp-container/weapp-container/blob/main/docs/runtime-artifacts-migration.md)。

运行库发行工具已迁入 [核心仓库](https://github.com/weapp-container/weapp-container/tree/main/tools/runtime-artifacts)。新清单使用核心仓库的精确版本 Release；用 `--manifest` 显式选取。更新后的下载器把旧锁中的 `runtime-artifacts` 标识定向到私有的 `weapp-container/runtime-artifacts-private-archive`，仅允许九个冻结版本，保留原版本、哈希及随附许可，回执记录实际存储库。旧版下载器需升级；同名新仓库只保留停更工具源码。无跨仓库回退，私有档案仍需读取权限，源码许可不代表历史发行物公开可用。

核心运行时与 SDK 自有源码采用 Apache-2.0，可按[主仓库源码构建说明](https://github.com/weapp-container/weapp-container/blob/main/docs/sdk-source-build.md)构建当前运行库。现有预编译归档保留原许可、访问要求及精确版本和哈希锁；本次源码更新不发布替代二进制。

<a id="optional-install-a-historically-audited-archive"></a>
### 可选：安装已审核的历史归档


```sh
git clone https://github.com/weapp-container/sdk-android.git
cd sdk-android
git checkout be2a7c6085bbf2562f0a351855c69ee5167b3770
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


归档安装完成后，可在独立 SDK checkout 执行包装层构建：

```sh
node tools/build.mjs --native-root .runtime/install/native --licenses-root .runtime/install/licenses --pack /absolute/verified-portable-pack
```
