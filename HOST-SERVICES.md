<a id="android-hosting-capabilities"></a>
# Android 宿主能力

**简体中文** · [English](HOST-SERVICES.en.md)

适配器需要匹配当前 host-services 协议的 native runtime。宿主在加载 pack 前配置可信运行时上下文，再安装所需的原生能力；未安装的 API 保持不支持。

```kotlin
val toolkit = AndroidNativeDeviceServices(activity,
    networkOrigins = setOf("https://api.example.com", "wss://api.example.com"),
    permissions = permissionBroker)

session.configureHostServices(trustedConfiguration).thenCompose {
    toolkit.install(session)
}.thenCompose { session.load(pack, options) }
```

宿主持有 toolkit 直到 session 关闭。Activity 重建时，在主线程调用 `toolkit.rebindActivity(activityOrNull)`。系统权限由宿主的权限 broker 接入；应用内授权状态和系统权限分别检查。

原生能力按需安装，包括受管文件、HTTP/WebSocket、定位与传感器、蓝牙与 beacon、相机与媒体、录音、NFC、Wi-Fi、剪贴板、电量和窗口能力。每种能力只注册已实现且满足设备、权限和宿主配置要求的 API；不支持的选项明确报错。

文件通过受管虚拟路径访问，guest 不会得到宿主文件路径。网络按宿主配置限制 origin、请求大小和重定向。设备操作由当前 session owner 持有；隐藏、取消、关闭或 owner 变更时停止相应工作，并等待原生资源实际释放后再完成清理。

标准小程序 API 与业务扩展共用通用注册边界：`registerApiHandler` 接收 API 调用，`registerHostMethod` 注册宿主业务方法。应用服务由宿主自行提供。

设备、系统权限和硬件行为应在目标设备上单独验收；源码编译或本地模拟不能替代运行验收。
