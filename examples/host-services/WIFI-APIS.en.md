# Android Wi-Fi

[简体中文](WIFI-APIS.md) · **English**


Host explicit `AndroidNativeDeviceServices(..., enableWifi = true)`, or install it separately
`AndroidWifiApis(activity, permissions)`. SDK does not open global Wi-Fi radio and does not execute
`enableNetwork`, `disconnect` or `bindProcessToNetwork`.

Manifest requires ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_NETWORK_STATE,
ACCESS_FINE_LOCATION, and Android 13+ NEARBY_WIFI_DEVICES. Location usage and OS permissions
Verify separately; SSID/BSSID is location data, and reading complete information requires the system location service to be available.
Android version, system location switch, permission revocation, system scan throttling and device capabilities may deny the operation.

| API | Implementation and Scope |
| --- | --- |
| startWifi / stopWifi | initialize/close this adapter. stop retains independently registered subscriptions for the next start; remove them only in account change/close. |
| getConnectedWifi | Returns information from the actual WifiInfo; partialInfo:true returns only the SSID. If BSSID/security/frequency/RSSI is not visible, the complete query will fail explicitly and the value will not be guessed. |
| getWifiList / onGetWifiList / offGetWifiList | Real startScan broadcast with successful update. Maximum 64 entries, excluding old caches by scan timestamp; OS rejection/throttling is not reported as a successful scan. |
| onWifiConnected / offWifiConnected | This adapter provides complete visible information after observing real connection changes. |
| onWifiConnectedWithPartialInfo / offWifiConnectedWithPartialInfo | Only the actual SSID is returned. Up to 32 subscriptions, bounded serial distribution of latest events by owner. |
| connectWifi | Confirmed every time Mr. Android 10+ uses WifiNetworkSpecifier, and obtains the application's local-only network lease with system approval; it is not an Internet connection and does not change the process default network. Supports open/WPA2 ASCII passphrase, WEP/enterprise/WPA3-only does not pretend to be supported. |
| connectWifi({maunal:true}) | Keep the official spelling. Open the real system Wi-Fi settings and wait for the actual SSID/BSSID match to complete, 30 seconds timeout. The system settings cannot accept passwords, password must be empty and the user enters their credentials there. Android 8/9 also uses this path and does not call the old global configuration API. |

`setWifiList` is the hotspot list process of WeChat iOS and does not register the Android handler. Reference
[WeChat setWifiList](https://developers.weixin.qq.com/miniprogram/dev/api/device/wifi/wx.setWifiList.html).

The system may remember previous NetworkSpecifier approvals, so the SDK still shows independent native connection confirmation each time.
The actual Wi-Fi association of a single STA device may be affected by the system, and we cannot promise that other application networks will not be affected at all.
local-only lease needs to use the socket corresponding to the Network to route to the network; this adapter does not
Ordinary HTTP/TCP, host other sockets or the entire process secretly cuts through. The host's default network maintains system management.

hiding/backgrounding releases this adapter's local request; explicit maunal system setup process allows users to leave
Return to completion after the current Activity. Stop, change number, cancel and close to cancel the adapter's request and
callback, does not delete the host's saved network, does not close the system settings page or radio.

Official binding source:

- [Android Wi-Fi scanning overview](https://developer.android.com/develop/connectivity/wifi/wifi-scan): Scanning may be throttled, getScanResults may be an old cache, must be checked for successful broadcast.
- [Android Wi-Fi Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap): Android 10+ app network requests do not provide Internet, the system can remember to approve.

[Development Evidence](../../test/evidence/host-services-wifi-development.json) Only proves the current development
snapshot real query, scan, partial event, stop/start on the task's own Android emulator
Subscription, the stop barrier, and unchanged global bindings/radio state. connect confirmation, AP switching, password/system denial,
Physical radio and Android 13+ permissions still pending device acceptance.
