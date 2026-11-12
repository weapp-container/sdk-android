# Weapp Container Android SDK

首次公开发布正在准备；安装命令须在对应注册源出现本版本后使用。历史私有归档及其锁文件保持原样。

版本 `0.1.0-preview.7.0`；Android API 26+，arm64-v8a / x86_64；Java 17。配置 Maven Central 后直接依赖公开坐标：

```kotlin
repositories { google(); mavenCentral() }
dependencies { implementation("io.github.weapp-container:weapp-container-android:0.1.0-preview.7.0") }
```

AAR 包含 JNI 接入层、两种 ABI 的运行库和第三方许可。普通应用安装无需 Node.js、Rust 或私有仓库授权；Java/Kotlin 包名保持 `com.sonofmagic.weapp.container.sdk`。

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
