<a id="本地宿主账号与-reference-支付接入"></a>
# Local Host Account and Reference Payment Integration

[简体中文](README.md) · **English**


This file can be integrated with a standalone host debug target; it depends on the current SDK module, including real HTTP calls, native authorization prompts, and account cleanup hooks. First, start the private core repository's `demo-business-server.mjs`, then inject the real demo pack root and host-native-specific control token as instructed in its README.

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

The initial identity state is fetched from the real backend during configure/load phase. After issuing a login code, the guest passes the code to `/demo/login`. The application client secret and app bearer remain within the business service; the guest only obtains an opaque business session. After exchange, the guest invokes `weappContainer.invoke("demo.identity.refresh", {})`, triggering the host to request the real backend and update synchronization state. This method does not accept account or credential parameters. Nickname, avatar, and phone number are obtained via the SDK's native authorization UI and are not automatically granted upon existing host login.

`ReferencePayment`/Reference Channel displays a clear local test confirmation UI, then uses a control-protected `/demo/reference-confirm` to request backend signature processing for test notifications. The standard payment adapter continues to query the server for paid status before succeeding. This channel is only explicitly installed in this debug example; in production, the wallet channel should be explicitly installed with the official SDK and should not fall back to the reference channel upon official failure.

Before switching numbers, first use bootstrap to prepare new host credentials. Each session's cleanup hook requests a new account snapshot, ensuring readiness before native resource cleanup ACK; the core manager shared across host/tenant uniformly propagates revocation. Guest objects, subscriptions, and directory switching are handled by the SDK. Example credentials are retained within the native domain by principal/epoch, and each request selects from a frozen context—new tokens must not be assigned to previous calls.

Environment origin and control token are injected via the host's native debug configuration. The load options passed to the guest contain only the public business origin and must not include the control token, host bearer, or application key.
Android uses debug-specific cleartext rules and explicit adb reverse; iOS uses debug-specific ATS local networking. In production, HTTPS and the existing host security credential system are used.
