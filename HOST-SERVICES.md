# Android 宿主能力

这些适配器需要匹配当前 host-services 协议的 native runtime；旧 preview runtime
不能通过只替换 Kotlin 源码获得新能力。源码编译通过不代表设备、渠道或商户验收。

先由宿主验证发行者和安装包，配置 `application`、`account`，再注册适配器，最后
加载 pack。`registerApiHandler` 接入微信 API；业务扩展继续使用
`registerHostMethod`。未安装的 API 明确不支持。

`HostServicesClient.identityState` 在可信账号明确为 `principalId: null` 且 epoch
为精确十进制 u64（快照辅助层接受 `"0"`，公开 owner 配置仍要求正数）时准备未登录快照，不再读取已撤销的宿主 bearer。初始准备、
换号清理准备与前台刷新都使用同一入口；已登录 principal 仍必须查询真实后端，
网络、权限或凭据失败继续传递，不会回退为成功。快照不包含旧用户资料或手机号授权。

`readHostApiResource(context, offset, length)` 透传资源 setter 的原始可信 context，
返回 `{bytes,totalBytes}`；单块最多 1 MiB。先检查 runtime 的
`hostServices.apiResourceChunks == "1.0"`，保留 context 中的十进制 epoch、deadline
和 session/call ID，不用 transport ID 替换。只读命令仍受运行时账号、期限与调用
存活检查；换号、结算、取消或超时后拒绝，旧 runtime 明确不支持。它不会创建
owner、进入 JS 或触发表面呈现，也不赋予任意路径读取权限。

```kotlin
val toolkit = AndroidNativeDeviceServices(activity,
    networkOrigins = setOf("https://api.example.com", "wss://api.example.com"),
    permissions = permissionBroker)

session.configureHostServices(trustedConfiguration).thenCompose {
    toolkit.install(session)
}.thenCompose { session.load(pack, options) }
```

宿主必须持有 toolkit 直到 session 关闭。Activity 重建时，在主线程调用
`toolkit.rebindActivity(activityOrNull)`；空值停止旧资源，新的 Activity 不重复注册
handler。宿主通过 `AndroidPermissionBroker` 接入真实 Activity Result 权限流程，
不能把点击“允许”直接当成系统授权成功。

定位、相机、麦克风和蓝牙使用独立的小程序授权提示。授权记录按可信宿主、租户、
发行者、应用、环境、账号及 epoch 隔离；系统授权已存在也需要小程序授权。
`wx.getSetting` 只返回已询问过的作用域。宿主仍须按实际使用声明 CAMERA、
RECORD_AUDIO、定位、Android 12+ BLUETOOTH_SCAN / BLUETOOTH_CONNECT，以及旧系统
所需的蓝牙/定位权限；SDK 不替应用默认申请所有危险权限。

toolkit 统一拥有文件、设备、蓝牙、媒体、网络和录音适配器，也可按需独立安装。
`session.closeAsync()` 等待主线程原生资源完成清理后才关闭核心；清理失败保留
原 owner 和文件 lease，再次调用 closeAsync 重试。完成后再关闭 toolkit。

网络使用独立 OkHttp 4.12 客户端，支持 request、受管文件 multipart upload、
download、进度/响应头/分块事件和 WebSocket。宿主明确列出 HTTPS/WSS origin，
客户端不共享宿主 Cookie、不跟随重定向；开发时仅显式
`allowLoopbackForDevelopment = true` 可允许 loopback HTTP/WS。请求/响应 1 MiB，
受管下载/上传 8 MiB，socket 消息和待发送队列 1 MiB；事件入口受数量和字节上限
限制，最终 HTTP 结果等待此前事件送达。换号与关闭实际取消并等待在途 I/O。

RecorderManager 使用真实 MediaRecorder，独立检查 `scope.record` 和 RECORD_AUDIO。
当前仅 AAC ADTS，100–60000ms、最多 4 MiB，默认 8000Hz、双声道、48000bps、
60000ms。支持官方列出的九档 8000–48000Hz，编码码率按每档采样率的官方范围
分别核验；auto/mic audioSource 与 default audioMode 可用，其他输入源、模式和
frameSize 明确拒绝。临时原生文件完成后导入同一受管目录。隐藏、后台、取消、换号和关闭
停止麦克风；释放或删除失败时保留资源供清理重试。
导入前由 MediaExtractor 核对真实输出采样率与声道；设备 encoder 静默降级时发出
Error 并删除 staging，不把错误格式的文件作为成功结果交付。

系统适配器按宿主 Manifest 声明安装通讯录、日历与扫码 API。通讯录需要
WRITE_CONTACTS，日历需要 READ_CALENDAR / WRITE_CALENDAR；小程序授权和实际 OS
权限分别核验，并在原生 UI 确认联系人或选择可写日历之后才提交系统 provider
事务。日历不生成官方小程序链接/签名；提醒提前量以秒输入，Android 仅支持能精确
表示为整分钟的非负提前量。

相机扫码使用 Camera2 实际 YUV 帧与 ZXing Core 3.5.3，支持普通二维码、条码、
Data Matrix、PDF417；当前不提供相册入口或微信码解码。其他相机 owner 应先释放
设备，再打开扫码 UI。取消、隐藏和关闭等待 CameraDevice.onClosed 与解码线程结束。
[Maven 依赖与许可证](THIRD-PARTY-NOTICES.md) 包括 OkHttp、Okio、ZXing 以及
OkHttp public suffix 数据的许可文本；Gradle 将这些文本保留到 AAR assets。

NFC 需要宿主显式 `enableNfc = true`、NFC permission 与实际 NfcAdapter，当前只
安装 NDEF reader/writer。它使用真实 reader mode、标签连接、读写、超时与关闭，
不声明 ISO-DEP、MIFARE 或 HCE 支持。NDEF 字节/事件都有上限，换号等待 I/O
线程和 discovery 回调排空。外部真实标签尚未验收。

SOTER 的支持/录入查询同时检查实际指纹硬件和宿主的
`AndroidSoterAttestationProvider`。没有 provider 时返回空 supportMode/false，
不注册 startSoterAuthentication。provider 必须运行真实 SOTER 认证并签名指定
challenge；SDK 校验结果的 raw challenge 与非空 base64 签名，普通指纹成功不能
替代可信签名。当前只提供 fingerprint 模式集成入口。

设备适配器包括真实剪贴板、网络状态、电量、窗口亮度与常亮、震动、原生系统 UI、
WGS84 定位及传感器。传感器当前支持 normal 采样；GCJ-02 需要独立坐标服务。
前台持续定位使用 LocationManager，支持 start/stopLocationUpdate、
on/offLocationChange 及 on/offLocationChangeError。单次与持续定位共享权限边界，
小程序隐藏或 Activity 进入后台暂停采样，回到前台才按原 owner 恢复；停止、换号
和关闭移除原 listener。事件只保留最新位置/错误，串行送达；不注册后台定位。
BLE 适配器拥有自己的扫描器和 GATT，按设备串行提交操作；关闭不会关闭宿主全局
蓝牙。取消在途 GATT 操作会关闭该连接，以隔离迟到回调。

iBeacon 通过独立 BluetoothLeScanner 解析真实 Apple manufacturer 0x004c / 0215
广播，提供 start/stopBeaconDiscovery、getBeacons、on/offBeaconUpdate 和
on/offBeaconServiceChange。需要独立位置与蓝牙小程序授权、精确位置 OS 权限、
位置/蓝牙服务开启，以及对应 Android 版本的 scan/connect 权限；宿主不能为
iBeacon scan 声明 neverForLocation，否则系统可能过滤这些位置广播。
最多 20 个标准 UUID、64 个结果、32 个订阅；后台/隐藏暂停自己的 scanner，
前台恢复，换号/关闭停止并丢弃旧代回调。Android proximity 按官方契约固定为 0，
accuracy 是基于真实 RSSI 与广播 measured power 的路径损耗估计，无法估计时为 -1。
不需要先打开 BLE adapter API，不修改或关闭宿主 radio。

Wi-Fi 需要宿主显式 `enableWifi = true`。公共 OS 实现、系统确认、应用 local-only
网络 lease、maunal 系统设置、扫描节流和实际权限范围见
[Wi-Fi 接入](examples/host-services/WIFI-APIS.md)。

文件适配器公开受管 `wxfile://user/` 虚拟路径和基础异步文件 API。同步
FileSystemManager 方法由核心直接执行，使用同一个受管目录与真实 flock 操作锁。每块读写最多 1 MiB、单文件 8 MiB、单账号应用 32 MiB；写入等待
同步和原子替换。guest 拒绝并隐藏 `.weapp-*` 内部锁文件。原生相机照片写入同一 namespace。宿主路径不传入小程序。
SDK 创建 native session 时总是设置 app-private `managedFilesRoot`，独立于键值存储
配置。文件与授权记录只使用 core `managedFileScope` 返回并经应用/账号身份核验的
`root` 和 `auxiliaryRoot`；资源清理完成前保留共享 lease。账号删除统一调用 core
`deleteAccountData`，在清理 ACK 与 surface detach 后同时移除文件和授权记录。

媒体驱动从 `hostComponents` 的已接受节点建立资源，通过 `hostComponentFrame`
传输真实 Camera2 / 视频解码 RGBA 帧，交给原 Skia PaintPlan 完成变换、裁剪和
层级合成。当前预览上限 320×240、5 fps，队列只保留最新待提交帧。Android 视频
目前读取受管本地文件，使用 MediaPlayer 播放音频并从同一文件按播放位置解码
视频帧；不支持直播、DRM、录制、全屏和弹幕。CameraContext 拍照来自捕获的当前
帧；不提供高分辨率独立静态拍摄。上下文只接受 runtime 签发的 componentTarget，
不能用 guest 的节点 ID 或 ownerScopeId 替代。后台、节点卸载和换号停止设备；
相机关闭等待 CameraDevice.onClosed，清理失败会阻止新账号展示。

身份与支付是单独的宿主接入：

```kotlin
val backend = HostServicesClient(URI("https://accounts.example.com"),
    hostToken = { call -> secureHostSession.tokenFor(call.context!!) })
val identity = AndroidHostIdentity(activity, backend)
val prepared = identity.prepareConfiguration(trustedConfiguration)
// 等待 prepared，configureHostServices 后安装 identity，之后才 load。
```

token closure 始终留在 native；小程序只收到短时、单次、绑定应用的 code。应用后端
用自己的 confidential client 兑换 code。签发 code 尚未等于应用已登录；
`wx.checkSession` 查询服务端已兑换的有效会话。兑换完成、撤权、宿主凭据刷新及
回到前台后，宿主调用 `identity.refreshState(session, trustedContext)`；同步
`weappContainer.identity.getState` 和 onChange 来自这一真实服务端状态。
刷新在发起请求之前读取 native state，验证传入的 application/account 并冻结实际
内部 sessionId；只有 ready owner 能发起该刷新，切换中或空 owner 直接拒绝。
旧请求完成时仍携带该身份，不会重新绑定到新 owner。回调携带的
原始 ApiCallContext 同样原样透传。运行时按完整应用/包、账号 epoch 和内部会话
拒绝过时快照；这项校验不要求人工 native refresh 对应一个 pending API call。
发起异步身份刷新前及提交带 context 的快照时，SDK 都要求 runtime 声明
`hostServices.snapshotOwnerContext == "1.0"`；缺少该标记时明确报
`ERR_UNSUPPORTED_CAPABILITY`，防止旧 runtime 静默忽略新的 owner 校验字段。

服务端报告 `invalid_app_session`、`ERR_INVALID_APP_SESSION`，或在公开
`backendCode` 字段中报告同一原因时，SDK 在拒绝原调用前尝试刷新身份快照。
分类和刷新使用同一归一化规则，传给小程序的原始 `errCode`、`errMsg` 与公开扩展
不改写。刷新自身失败时保留原调用错误和最后成功快照；它不伪造新的未登录状态。

资料与手机号提示先向可信后端查询授权政策，原生 UI 展示服务端 purpose，
授权记录和后续 code 同时绑定 purpose 与 permissionVersion。小程序传入的 desc
不决定用途；拒绝同步服务端并刷新快照，取消保留取消语义。服务端政策变化后，
旧授权不能继续使用。

换号先替换宿主安全凭据，在 `accountCleanupHandler` 内等待
`identity.prepareAccountState(session, operationId, newTrustedContext)`，再完成清理
future。新的快照必须在清理 ACK 之前准备；旧账号任意快照不能复用。

连续换号立即阻止旧身份，但清理 hook 按实际终结串行执行。新 epoch 的 hook 和
ACK 必须等待此前已启动的 future 完成；取消或忽略旧结果不代表原生资源已释放。
尚未开始的过时 hook 会跳过。关闭同样等待整条清理队列；失败可在当前操作显式重试。
宿主应在准确的设备、文件和后台工作全部结束后才完成 hook future。
可用 `node tools/test-account-cleanup.mjs --kotlin-lib <compiler-lib-directory>`
编译运行取消、迟到终结、失败重试和关闭屏障测试。

`AndroidAppPayments(activity, backend, wechatAppId)` 同时注册 `wx.requestPayment` 与 `weappContainer.payment.request`，只接收服务端 `orderId`，拒绝
JSAPI 签名冒充 APP Pay。宿主显式引入官方微信/支付宝 Android SDK，并配置 Manifest、
应用 ID、回调 Activity 和混淆规则。微信回调从官方 WXApi.handleIntent 的
IWXAPIEventHandler 传给 `onWechatPayResponse`。官方回调之后仍须后端验签确认 paid
才向小程序报告成功。取消等待不会关闭订单或退款；旧支付回调不会分派给新账号。

实际设备权限、蓝牙外设、相机画面、媒体帧、账号切换和真实商户交易仍须以当前
native 产物分别验收，不能由模拟响应、目录清单或源码存在性替代。

## 开发闭环验证

会话失效刷新有独立的有限原生回归，可复用已经运行的 arm64 模拟器及明确选定的
preview.6.0 native slice，不依赖相机、联系人、支付或授权提示：

```sh
node tools/identity-refresh-probe.mjs \
  --serial emulator-5554 \
  --native-dir /absolute/native/aarch64-linux-android \
  --reference-root /absolute/host-services-reference \
  --out /absolute/new-identity-probe-output
```

它经公开 SDK、JNI 和真实 QuickJS 调用 HTTP/SQLite 参考服务，检查三种失效错误形式、
回调/complete/Promise 一次结算、原错优先于刷新断连，以及实际旧账号快照响应在新
账号呈现后才释放时由 native 拒绝。输出记录所用 native producer、SDK 源码摘要、
实际系统/协议和任务资源清理。探针保留借用模拟器，只卸载自己的包和 reverse、关闭
自己的服务。手写 pack、当前源码 wrapper 与显式选择的历史 runtime 组成开发回归，
不能作为全新 SDK 归档安装、编译器交付或当前 release 验收。

`test/HostServicesProbeActivity.kt` 和 `tools/host-services-probe.mjs` 使用完整公开 SDK、
真实 JNI 和 JS 引擎运行 authored pack。探针自动启动独立 loopback reference 业务服务，
只向 native Activity 注入临时 control token；guest 使用公开业务地址兑换登录 code、
获取 businessSession、创建固定测试商品订单。测试通过实际 Android input 点击 Skia
手机号按钮，并分别处理资料、手机号、相机和 reference 支付的原生同意 UI。

在私有核心 checkout 已生成 arm64 Android dev slice 后，选择一个已经运行的模拟器：

```sh
node sdk/android/tools/host-services-probe.mjs \
  --serial emulator-5580 \
  --native-dir /absolute/development/aarch64-linux-android \
  --out /absolute/new-probe-output \
  --accept-app-consent
```

独立 SDK checkout 还需通过 `--reference-root` 指定参考服务目录。探针拒绝覆盖已有
输出、同名测试应用和 adb reverse 端口，并在结束时卸载自己的应用、删除自己的 UI
临时文件、撤销自己的 reverse 映射、关闭自己的业务服务；模拟器由调用方管理。

当前 [开发记录](test/evidence/host-services-development.json) 绑定未发布的 dev 库、
SDK/fixture/JNI 源码和真实 GPU 截图哈希，并记录构建前后一致的核心源码清单。
已通过登录兑换和快照刷新、独立授权、server-paid reference 订单、同步/异步同目录
文件、A/B 换号隔离、Camera2 JPEG 和 Skia 合成、账号数据删除及资源清理。这里的
相机来自模拟器虚拟场景，支付来自显式 reference 渠道，不代表物理设备、蓝牙外设、
官方钱包、商户交易、编译器兼容性或正式发行验收。

[网络与录音开发记录](test/evidence/host-services-network-recording-development.json)
另行记录 14 项通过的扩展探针：真实 HTTP 响应头/分块、multipart 文件、下载字节、
WebSocket echo/close、实际 AAC ADTS 帧、换号关闭旧请求/socket，以及主动注入一次
清理失败后，确认核心未提前 closed、第二次 close 成功。loopback 网络和模拟器
麦克风不等于生产 TLS、物理录音或发行验收。

系统扩展探针额外通过真实联系人与日历 provider 提交，并独立核对 600 秒提醒
写为 10 分钟、没有 signer 时 SOTER 不可用。探针只删除自身随机 marker 的
联系人与临时 calendar account；扫码识别、实体 NFC 与真实 SOTER 签名仍为
独立设备验收范围。

[前台定位与授权政策开发记录](test/evidence/host-services-location-policy-development.json)
保留独立 18 项探针。显式 `--probe-foreground-location` 使用模拟器 console 注入 GPS，
小程序收到真实 LocationManager 坐标，停止后继续注入仍不再收到事件；原生资料与
手机号 UI 实际显示后端登记用途，授权并完成兑换。工具、原生包装、fixture 与完整
参考后端源码在执行前后逐文件核对哈希；这仍是开发 snapshot 的模拟器证据。

[Beacon 与录音开发记录](test/evidence/host-services-beacon-recorder-development.json)
另行记录默认双声道在此模拟器被 native 降为 mono 后的明确拒绝，随后显式 mono
实际 AAC 的 8000Hz/声道元数据，以及不兼容码率拒绝。默认双声道物理设备录音仍
待验收。Beacon
新源码完整编译，JVM 执行 authored 广播字节的 UUID、无符号 major/minor、距离估计
及畸形长度/前缀边界测试；这不构成真实 Beacon 发射器、权限变更或射频验收。

Native snapshot owner validation belongs to the negotiated runtime. The SDK passes the complete captured context unchanged and preserves the runtime error, including `ERR_CANCELLED` for a retired owner; it updates its rebuild cache only after successful validation. A credential refresh cannot replace this owner check.

Backend errors keep their container classification (`code`, `message`) separate from public API
fields. The adapter merges non-null extension fields in this order, with later sources winning:
response envelope, envelope `details`, nested `error.details`, then nested `error` fields. It
excludes the structural `error`/`details` and classification `code`/`message` keys from the public
projection. Identity rejection preserves projected `errCode` and `errMsg`; only absent values
receive the adapter defaults. Canonical `invalid_request`, `permission_denied`, `timeout`,
`cancelled`, `unsupported` and their uppercase `ERR_*` forms keep the corresponding container
classification. Consent and invalid/stale identity session codes map to `permission_denied`.
The core `ERR_UNSUPPORTED_CAPABILITY` code and `unsupported_capability` alias map to `unsupported`.
Unknown provider codes map to `invalid_request`. The projection carries a typed `HostErrorCode`
directly to `HostReply.reject` so serialization cannot collapse these categories.
Run `npm run test:host-backend-error -- --kotlin-lib <compiler-lib>
--json-jar <org-json.jar>` for the pure projection and final identity rejection regression.
