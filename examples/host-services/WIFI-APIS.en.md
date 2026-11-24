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
| startWifi / stopWifi | Initialize or stop this adapter. `stopWifi` retains its subscriptions for a later start; owner replacement or close removes them. |
| getConnectedWifi | Returns information from the actual WifiInfo; partialInfo:true returns only the SSID. If BSSID/security/frequency/RSSI is not visible, the complete query will fail explicitly and the value will not be guessed. |
| getWifiList / onGetWifiList / offGetWifiList | Real startScan broadcast with successful update. Maximum 64 entries, excluding old caches by scan timestamp; OS rejection/throttling is not reported as a successful scan. |
| onWifiConnected / offWifiConnected | This adapter provides complete visible information after observing real connection changes. |
| onWifiConnectedWithPartialInfo / offWifiConnectedWithPartialInfo | Only the actual SSID is returned. Up to 32 subscriptions, bounded serial distribution of latest events by owner. |
| connectWifi | Requires native user confirmation each time. Android 10+ uses `WifiNetworkSpecifier` and, with system approval, obtains an application-scoped local-only network lease. This is not an Internet connection and does not change the process default network. Open networks and WPA2 ASCII passphrases are supported. WEP, enterprise networks and WPA3-only networks are not supported. |
| connectWifi({maunal:true}) | Preserves the official spelling. Opens the system Wi-Fi settings and waits up to 30 seconds for the actual SSID/BSSID to match. The system settings screen cannot accept a password, so `password` must be empty and the user enters credentials there. Android 8/9 use this path too. It does not call the legacy global configuration API. |

Unsupported Wi-Fi methods remain unavailable unless a host installs its own implementation through the generic API registration interface.

The system may remember previous NetworkSpecifier approvals, so the SDK still shows independent native connection confirmation each time.
The system may affect the Wi-Fi association on a single-STA device, so this adapter cannot guarantee that other applications' network behavior is unaffected.
A local-only lease routes traffic only when sockets use the associated `Network`. This adapter does not redirect ordinary HTTP/TCP traffic, other host sockets, or the entire process. The system continues to manage the host's default network.

Hiding or backgrounding releases this adapter's local request. During the explicit `maunal` settings flow, the user may leave the Activity and return before the request completes. Stop, owner replacement, cancellation and close cancel the adapter's request and callbacks. They do not delete saved host networks, close the system settings page, or turn off the radio.

Official binding source:

- [Android Wi-Fi scanning overview](https://developer.android.com/develop/connectivity/wifi/wifi-scan): Scanning may be throttled, getScanResults may be an old cache, must be checked for successful broadcast.
- [Android Wi-Fi Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap): Android 10+ app network requests do not provide Internet, the system can remember to approve.

The public source does not include the device receipt for this development snapshot, so the device results for queries, scans, partial events, stop/start subscriptions and the stop barrier cannot be independently verified from this repository. Implementation or build results alone do not establish a pass. Native connection confirmation, AP switching, password/system denial, physical radio behavior and Android 13+ permissions also require separate target-device acceptance.
