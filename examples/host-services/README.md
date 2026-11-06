# 本地宿主账号与 reference 支付接入

此文件可以加入独立宿主的 debug target；依赖当前 SDK 模块，包含真实 HTTP 调用、
原生授权提示和账号清理 hook。先启动私有核心仓库的 `demo-business-server.mjs`，
按其 README 注入真实 demo pack root 和宿主原生专用 control token。

```kotlin
val environment = DemoEnvironment(URI("http://127.0.0.1:8787"), nativeDebugControlToken)
val domain = DemoHostDomain(environment) // 多个 controller 共用一个 domain。
val services = ReferenceHostServices(activity, domain)
domain.bootstrap("demo-A", "1").thenCompose {
    services.installAndLoad(session, PackSource.Directory(verifiedPackDirectory),
        LoadOptions(options = JSONObject().put("businessOrigin", environment.origin.toString())))
}
// /demo/login 成功兑换后调用 services.refresh(session)。
// 原生 app 退出：services.logoutApp(session)；guest 也可调用 identity.logout。
// 换号一次即可传播到相同 host/tenant 的所有 controller：
// domain.switchAccount(session, "demo-B", "2", freshLaunches)
// await session.closeAsync() 后 services.close()；全部 controller 关闭后 domain.close()。
```

初始身份状态在 configure/load 前向真实后端请求。签发登录 code 后，guest 将 code
交给 `/demo/login`，应用 client secret 和 app bearer 都留在业务服务；guest 只取得
opaque businessSession。兑换后 guest 调用 `weappContainer.invoke("demo.identity.refresh", {})`，触发宿主
请求真实后端并更新同步状态。此方法不接受账号或凭据参数。昵称头像、手机号分别通过 SDK 的原生授权 UI，不会由已有宿主登录自动授予。

`ReferencePayment`/reference channel 展示明确的本地测试确认 UI，再通过 control
保护的 `/demo/reference-confirm` 请求后端签名处理测试通知。标准 payment adapter
继续查询服务端 paid 状态后才成功。此渠道只在此 debug 示例显式安装；正式钱包
渠道应显式安装官方 SDK，不从官方失败切换到 reference。

换号前先通过 bootstrap 准备新宿主凭据。每个 session 的 cleanup hook 请求新账号
快照，在原生资源清理 ACK 之前准备好；共享 host/tenant 的核心 manager 统一传播
撤销，guest 对象、订阅和目录切换由 SDK 完成。示例凭据按 principal/epoch 保留在
native 域内，每次请求按冻结上下文选择，不能把新 token 配给旧 call。

环境 origin、control token 由宿主的 native debug 配置注入。传给 guest 的 load options
只包含公开 businessOrigin，不能包含 control token、host bearer 或应用密钥。
Android 使用 debug 专用 cleartext 规则及显式 adb reverse；iOS 使用 debug 专用 ATS
local networking。生产环境使用 HTTPS 和既有宿主安全凭据系统。
