<a id="android-sdk-preview"></a>
# Android SDK 预览

**简体中文** · [English](EMBEDDING.en.md)


`com.sonofmagic.weapp:weapp-container-android:0.1.0-preview.5.3` 将真实的 QuickJS 运行时和 Skia GLES 渲染器嵌入到 Android View 中。最低 Android API 为 26。发布版 AAR 包为 `arm64-v8a` 和 `x86_64`，支持 JNI 消费者 R8 规则，以及其 C++ 运行时。应用无需安装 Rust 或构建 JNI。

<a id="build-and-consume"></a>
## 构建与使用

请参考 [根目录的 README](../README.md) 中的独立命令。运行时归档文件在私有二进制条款下提供；无需父源码检出或 Rust 编译器。

<a id="embedding"></a>
## 嵌入

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

`PackSource.Directory(File(...))` 接受已下载或解压的本地包。资源会被复制到私有临时目录，并由共享 SDK 进行验证；加载后临时副本将被移除。不支持远程可执行 URL。`LoadOptions(route, options)` 选择第一条路径和启动选项。替换失败时，将保留之前的实例并重新附加其表面。在请求首次加载前，需预先注册宿主方法。

宿主选择稳定的存储密钥。切勿从会话 ID 或包哈希中派生该密钥。一个密钥仅允许一个写入者；`closeAsync()` 在持久化完成后释放该租约。如需仅用于内存存储，请明确传递 `null`。

`WeappContainerView` 是一个 TextureView，因此可作为普通子矩形与原生宿主 UI 合成。一个会话绑定到一个 View；多个独立会话可共存。`unbind()` 和 View/Surface 的销毁不会影响会话。重新绑定时将展示当前帧，而不会重新创建 JavaScript 隔离环境。框架适配器可在替换嵌入 View 前等待 `unbindAsync()`；该操作在工作线程确认旧表面资源退役后完成。SDK 会读取根窗口内边距，但不会更改宿主的监听器。它仅拥有自己的 EGL 上下文和目标，从不终止共享的 EGL 显示。

首次加载会等待正向测量的 View 尺寸、实际显示密度、窗口内边距和方向，才进行原生会话创建或模块执行。对于没有 View 的会话，需提供 `initialMetrics` 或调用 `setWindowMetrics(WindowMetrics(...))` 并传入真实的宿主观察值。不会注入默认的 375×667 几何尺寸。Android 安全区域的纵向原点语义尚未验证，因此适配器将省略该可选字段。

通过 `setForeground(false)` 在暂停时和 `setForeground(true)` 在恢复时转发 Activity/功能生命周期。恢复时会等待新的 View 观察值，再调用 App/Page 显示钩子。隐藏的 View 会停止呈现和应用定时器，但独立的工作线程将继续处理 HTTP 请求、宿主响应、取消、超时和存储完成，直到 Android 允许进程执行。
所有会话操作返回 `CompletableFuture`；回调和公共事件在主线程上运行。切勿在主线程上阻塞操作，使用 `get()`/`join()`。JavaScript、JNI、EGL 以及完成泵运行在一个专用的 HandlerThread 上。宿主回复会被桥接到该线程；它们永远不会重新进入原生回调。

```kotlin
session.sendMessage(JSONObject().put("type", "refresh"))
session.navigate("pages/details/index", JSONObject().put("id", "42"))
session.back()
session.flushStorage().thenCompose { session.closeAsync() }
view.unbind()
```

宿主方法接受 JSON 值并以 `HostReply.resolve(value)` 或 `reject(HostErrorCode, message, retryable)` 回应。`HostCall` 提供一个不透明的 ID、超时、取消令牌和取消回调。回复最多仅结算一次；被取消或已过期的回复不会影响替换实例。`weappContainer.invoke/postMessage/onMessage` 在 `../types/weapp-container.d.ts` 中声明。`wx.request` 使用共享的异步 HTTP 实现。上传、下载和 WebSocket 不属于预览功能。

触摸批次包含所有活动/变更的指针以及单指触摸识别器；支持硬件按键事件。原生文本编辑/IME、可访问性语义、长按和高级手势识别仍为预览限制。`snapshot()` 显式请求 RGBA 诊断读回；正常 GLES 显示从不会通过 JSON/Java 复制页面像素。

<a id="validation"></a>
## 验证

构建会验证发布版 AAR、Maven 依赖解析、一个独立的启用 R8 的消费者、两种 ELF 架构，以及所有打包原生 LOAD 段的 16 KB 对齐。消费者仅使用已发布的 Maven 坐标。

```sh
node tools/device-smoke.mjs --serial emulator-5554
```

设备运行器需要一个显式命名的已运行设备，并拒绝覆盖已安装的消费者包。它仅安装和移除自己的消费者/测试 APK，保持模拟器运行。仪器化测试会验证实际的 GLES、初始提供者几何、宿主边距共存、异步桥接/UTF-8、超时/取消、真实的 MotionEvent 触摸/滚动和 JavaScript 定时器、导航/返回、事务性重载、持久重开、分离缩放、Activity 暂停/恢复、隐藏状态下的 HTTP 完成。测试消费者保留公共 API 和 Kotlin 辅助工具，这些工具仅被其独立的仪器化 APK 所引用；SDK 内部仍使用已发布的消费者规则进行 R8 优化。其收据为目标特定；仅通过打包检查无法确定 x86 设备执行、物理硬件或性能一致性。

当渲染器附加观察到缺失的 `GL_VERSION` 或当前 EGL 上下文与本会话所有者不同时，呈现器将放弃其自身的 EGL 窗口和上下文，并尝试建立一个全新的上下文。它从不会终止共享的 EGL 显示或重新加载 JavaScript 会话。第二次无效上下文将返回一个
错误和附加信息在显式加载或重新绑定视图之前将持续保持失败状态；布局/可见性观察无法重启恢复循环。对于具有有效当前上下文的普通渲染器错误不会被重试。
错误诊断保留当前/所拥有的EGL句柄和GL字符串。即使渲染器附加失败，已提交的加载事件仍会被传递。

独立消费者还运行了12轮并发的双会话加载/关闭替换测试。其日志收据对实际的无效上下文恢复进行单独计数，与本目标仪器套件中的测试分开：

```sh
node tools/gles-recovery.mjs --serial emulator-5554 --offline
```

该套件使用真实的TextureView窗口、EGL上下文、原生会话和Skia呈现。在探针或原生附加期间，基于展示器作用域的测试依赖会解除当前EGL上下文；不存在进程级的故障切换。它验证了有界恢复、持续失败、对普通错误不重试、事件顺序、兄弟渲染以及在恢复和重新打开期间的稳定键存储。通过注入测试并不能证明已完全将间歇性驱动器的原始丢失机制本地化。
