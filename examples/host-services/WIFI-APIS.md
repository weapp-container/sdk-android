# Android Wi-Fi

宿主显式 `AndroidNativeDeviceServices(..., enableWifi = true)`，或单独安装
`AndroidWifiApis(activity, permissions)`。SDK 不打开全局 Wi-Fi radio，不执行
`enableNetwork`、`disconnect` 或 `bindProcessToNetwork`。

Manifest 需要 ACCESS_WIFI_STATE、CHANGE_WIFI_STATE、ACCESS_NETWORK_STATE、
ACCESS_FINE_LOCATION，以及 Android 13+ NEARBY_WIFI_DEVICES。位置用途与 OS 权限
分别核验；SSID/BSSID 是位置数据，读完整信息还需要系统位置服务可用。
Android 版本、系统定位开关、权限撤销、系统扫描节流与设备能力均可能拒绝操作。

| API | 实现与范围 |
| --- | --- |
| startWifi / stopWifi | 初始化/关闭本 adapter。stop 保留独立注册的订阅供下次 start 使用；换号/close 才移除它们。 |
| getConnectedWifi | 从实际 WifiInfo 返回信息；partialInfo:true 只返回 SSID。BSSID/security/frequency/RSSI 不可见时完整查询明确失败，不猜值。 |
| getWifiList / onGetWifiList / offGetWifiList | 真实 startScan 与更新成功的广播。最多 64 项，按扫描 timestamp 排除旧缓存；OS 拒绝/节流不冒充成功扫描。 |
| onWifiConnected / offWifiConnected | 本 adapter 观察到真实连接变化后提供完整可见信息。 |
| onWifiConnectedWithPartialInfo / offWifiConnectedWithPartialInfo | 仅返回实际 SSID。最多 32 个订阅，有界最新事件按 owner 串行分发。 |
| connectWifi | 每次先原生确认。Android 10+ 使用 WifiNetworkSpecifier，经系统批准取得应用 local-only 网络 lease；不是互联网连接，不更改进程默认网络。支持 open/WPA2 ASCII passphrase，WEP/enterprise/WPA3-only 不冒充支持。 |
| connectWifi({maunal:true}) | 保留官方拼写。打开真实系统 Wi-Fi 设置，等待实际 SSID/BSSID 匹配才完成，30 秒超时。系统设置不能接收密码，password 必须为空，用户在那里输入凭据。Android 8/9 也使用此路径，不调用旧全局配置 API。 |

`setWifiList` 是微信 iOS 热点列表流程，不注册 Android handler。参考
[微信 setWifiList](https://developers.weixin.qq.com/miniprogram/dev/api/device/wifi/wx.setWifiList.html)。

系统可能记住之前的 NetworkSpecifier 批准，因此 SDK 每次仍显示独立原生连接确认。
单 STA 设备实际 Wi-Fi 关联可能受系统影响，不能承诺其他应用网络完全不受影响。
local-only lease 需要使用对应 Network 的 socket 才能路由到该网络；此 adapter 不把
普通 HTTP/TCP、宿主其他 socket 或整个进程偷偷切过去。宿主的默认网络维持系统管理。

隐藏/后台释放本 adapter 的 local request；显式 maunal 系统设置流程允许用户离开
当前 Activity 后返回完成。停止、换号、取消和关闭撤销本 adapter 的 request 和
callback，不删除宿主已保存的网络，不关闭系统设置页面或 radio。

官方约束来源：

- [Android Wi-Fi scanning overview](https://developer.android.com/develop/connectivity/wifi/wifi-scan)：扫描可能被节流，getScanResults 可以是旧缓存，必须核对成功广播。
- [Android Wi-Fi Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)：Android 10+ 的应用网络请求不提供互联网，系统可记住批准。

[开发证据](../../test/evidence/host-services-wifi-development.json) 只证明当前开发
snapshot 在任务自有 Android emulator 上的真实查询、扫描、partial 事件、stop/start
订阅、停止屏障和未修改全局绑定/radio。connect 确认、AP 切换、密码/系统拒绝、
物理射频和 Android 13+ 权限仍待设备验收。
