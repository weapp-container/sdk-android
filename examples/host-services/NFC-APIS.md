# Android NFC

基线固定为微信基础库 3.17.3、miniprogram-api-typings 5.2.3，官方 66 叶清单见
NFC-CONTRACT.json。保持 `enableNfc: false` 默认关闭和既有 NDEF 接口兼容。
显式启用且具有实际 NfcAdapter 与 Manifest NFC 权限后才注册。

NFCAdapter 的 8 个 get*、发现/停止/订阅及 tech，Ndef 的 7 个叶，以及 IsoDep、
MifareClassic、MifareUltralight、NfcA/B/F/V 的 connect/close/isConnected/
setTimeout/transceive/getMaxTransceiveLength 均调用 Android 官方 TagTechnology。
IsoDep.getHistoricalBytes、NfcA.getAtqa/getSak 返回真实原生数据。每次 Discovered
的 techs 来自实际 Tag.techList；硬件/标签没有某技术时 get(tag) 明确失败，不能
把 API 存在解释为任意设备支持 Mifare Classic。缺失 historicalBytes 不伪造数据。

只启用此 Activity 的 reader mode，不切 NFC radio、不使用全局技术断开。
每个子对象首次绑定实际 tag epoch，新标签/停止/后台/账号退休使旧子对象失效，
需重新获取。单个 reader 同时仅执行一个 native I/O；transceive 输入受真实
maxTransceiveLength 限制，输出与 NDEF 最多 1 MiB，NDEF 最多 64 records。
NfcB/NfcV 无官方 timeout setter，使用有界总期限并关闭本 reader 的 TagTechnology；
其他技术同时设置真实 Android timeout。原生 close 请求不会冒充 worker 已结束。
账户清理等待所有 native worker 终态及发现队列屏障，失败保留资源以供重试。
事件队列最多 16 个/8 MiB，背压即停止本 reader。

实际 Kotlin 编译和 `test/AndroidNfcTerminalTest.kt` 验证 native terminal 边界；
不宣称已经进行真实标签读写、各芯片技术或权限界面验收。
