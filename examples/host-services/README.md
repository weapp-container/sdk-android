# Android 宿主服务指南

**简体中文** · [English](README.en.md)

本目录整理 Android 原生设备能力的接入说明。宿主服务默认按需启用，接入前先阅读[宿主能力总览](../../HOST-SERVICES.md)，确认宿主上下文、权限代理和 session 生命周期的所有权。

| 指南 | 内容 |
| --- | --- |
| [Wi-Fi API](WIFI-APIS.md) | 支持的方法、系统权限、连接确认和网络路由边界 |
| [NFC API](NFC-APIS.md) | Tag 技术对象、原生 I/O、取消和资源清理边界 |

实现代码可从 [AndroidWifiApis.kt](../../library/src/main/java/com/sonofmagic/weapp/container/sdk/AndroidWifiApis.kt) 和 [AndroidNfcTerminal.kt](../../library/src/main/java/com/sonofmagic/weapp/container/sdk/AndroidNfcTerminal.kt) 开始阅读。它们展示 SDK 内部适配方式，不能代替宿主应用自己的权限、生命周期和目标设备验收。
