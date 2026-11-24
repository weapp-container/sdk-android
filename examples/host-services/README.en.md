# Android Host Service Guides

[简体中文](README.md) · **English**

This directory collects integration guidance for native Android device capabilities. Host services are opt-in. Before integrating them, read the [host capability overview](../../HOST-SERVICES.en.md) and confirm ownership of trusted host context, the permission broker and the session lifecycle.

| Guide | Coverage |
| --- | --- |
| [Wi-Fi API](WIFI-APIS.en.md) | Supported methods, system permissions, connection confirmation and network-routing boundaries |
| [NFC API](NFC-APIS.en.md) | Tag technology objects, native I/O, cancellation and resource-cleanup boundaries |

Start with the implementation sources for [AndroidWifiApis.kt](../../library/src/main/java/com/sonofmagic/weapp/container/sdk/AndroidWifiApis.kt) and [AndroidNfcTerminal.kt](../../library/src/main/java/com/sonofmagic/weapp/container/sdk/AndroidNfcTerminal.kt). They describe the SDK's internal adapters and do not replace the host application's own permission handling, lifecycle management or target-device acceptance.
