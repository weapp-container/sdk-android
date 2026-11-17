package com.sonofmagic.weapp.container.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.*
import android.net.wifi.*
import android.os.*
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Public Android Wi-Fi APIs. A requested local network never becomes the process default. */
class AndroidWifiApis(activity: Activity, private val permissions: AndroidPermissionBroker? = null) : AutoCloseable {
    private var binding: Activity? = activity
    private val application = activity.application
    private val main = Handler(Looper.getMainLooper())
    private val wifi = application.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scopes = AndroidManagedFileScopes()
    private val consent = AndroidAppPermissionConsent({ checkNotNull(binding) }, { scopes.auxiliaryRoot(it) })
    private var session: WeappContainerSession? = null
    private var started: HostCall? = null
    private var pendingStart: Pair<HostCall, HostReply>? = null
    private var visible = true
    private var activityVisible = true
    private var generation = 0L
    private var monitor: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var scanRequested = false
    private var scanTime = 0L
    private data class Connection(val call: HostCall, val reply: HostReply, val ssid: String, val bssid: String?, val partial: Boolean, val manual: Boolean, var callback: ConnectivityManager.NetworkCallback? = null, var network: Network? = null, var completed: Boolean = false, var dialog: AlertDialog? = null, var settingsOpened: Boolean = false)
    private var connection: Connection? = null
    private var lastConnected: String? = null
    private val subscriptions = linkedMapOf<String, HostCall>()
    private val sequences = mutableMapOf<String, Long>()
    private data class Event(val owner: HostCall, val generation: Long, val method: String, val value: JSONObject)
    private val events = linkedMapOf<String, Event>()
    private var delivering = false
    val supportedApis = setOf("wx.startWifi", "wx.stopWifi", "wx.getConnectedWifi", "wx.connectWifi", "wx.getWifiList", "wx.onGetWifiList", "wx.offGetWifiList", "wx.onWifiConnected", "wx.offWifiConnected", "wx.onWifiConnectedWithPartialInfo", "wx.offWifiConnectedWithPartialInfo")
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { if (a === binding) { activityVisible = true; observeCurrent() } }
        override fun onActivityStopped(a: Activity) { if (a === binding) { activityVisible = false; safePause() } }
        override fun onActivityDestroyed(a: Activity) { if (a === binding) safePause() }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    }
    fun install(target: WeappContainerSession): CompletableFuture<Void> {
        check(session == null); session = target; scopes.bind(target); application.registerActivityLifecycleCallbacks(lifecycle)
        return CompletableFuture.allOf(target.registerHostResourceCleanup("wifi-${UUID.randomUUID()}") { reset() }, target.registerHostVisibilityHandler("wifi-${UUID.randomUUID()}") { visible = it; if (it) observeCurrent() else pause(false) }, *supportedApis.map { api -> target.registerApiHandler(api) { call, reply ->
            if (call.context == null) fail(api, reply, "trusted native context required", 12007)
            else scopes.prepare(call.context).whenComplete { _, error -> if (!call.isCancelled) { if (error != null) fail(api, reply, "permission scope unavailable", 12007) else invoke(api, call, reply) } }
        } }.toTypedArray())
    }
    fun rebindActivity(activity: Activity?) { if (binding !== activity) { reset(); binding = activity; activityVisible = activity != null } }
    private fun ok(api: String, reply: HostReply, data: JSONObject = JSONObject()) { reply.resolve(data.put("errMsg", "$api:ok").put("errCode", 0)) }
    private fun fail(api: String, reply: HostReply, text: String, code: Int) { reply.reject(if (code == 12001) HostErrorCode.UNSUPPORTED else if (code == 12007) HostErrorCode.PERMISSION_DENIED else HostErrorCode.INVALID_REQUEST, text, false, JSONObject().put("errMsg", "$api:fail $text").put("errCode", code)) }
    private fun foreground() = visible && activityVisible && binding != null
    private fun osPermissions() = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun permission(call: HostCall, completion: (Boolean) -> Unit) { consent.request("scope.userLocation", call) { allowed ->
        val host = binding
        if (!allowed || host == null || call.isCancelled) completion(false)
        else if (osPermissions().all { host.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) completion(true)
        else permissions?.request(osPermissions(), call) { granted -> main.post { if (!call.isCancelled) completion(granted && binding?.let { a -> osPermissions().all { a.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } } == true) } } ?: completion(false)
    } }
    private fun ready(api: String, reply: HostReply): Boolean {
        if (started == null) { fail(api, reply, "not init", 12000); return false }
        if (!foreground()) { fail(api, reply, "weapp in background", 12011); return false }
        if (!wifi.isWifiEnabled) { fail(api, reply, "wifi not turned on", 12005); return false }
        val location = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!location.isProviderEnabled(LocationManager.GPS_PROVIDER) && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { fail(api, reply, "location service not turned on", 12006); return false }
        if (osPermissions().any { application.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { fail(api, reply, "OS permission denied", 12007); return false }
        return true
    }
    private fun boolean(value: JSONObject, key: String): Boolean { if (!value.has(key)) return false; require(value.get(key) is Boolean) { "$key must be boolean" }; return value.getBoolean(key) }
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        val value = call.payload as? JSONObject ?: JSONObject()
        try {
            if (api.startsWith("wx.off")) { remove(value.getString("subscriptionId")); ok(api, reply); return }
            if (api.startsWith("wx.on")) { val id = value.getString("subscriptionId"); require(subscriptions.size < 32 && id !in subscriptions); subscriptions[id] = call; sequences[id] = 0; reply.retainUntilCancelled(); call.addCancellationHandler { if (subscriptions[id] === call) remove(id) }; ok(api, reply); return }
            if (api == "wx.stopWifi") { stopModule(); ok(api, reply); return }
            if (api == "wx.startWifi") {
                if (!application.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) { fail(api, reply, "system not support", 12001); return }
                if (!foreground()) { fail(api, reply, "weapp in background", 12011); return }
                if (pendingStart != null) { fail(api, reply, "duplicate request", 12004); return }
                val accepted = generation; pendingStart = call to reply; call.addCancellationHandler { if (pendingStart?.first === call) pendingStart = null }
                permission(call) { granted ->
                    if (pendingStart?.first !== call || generation != accepted || call.isCancelled) return@permission
                    pendingStart = null
                    if (!foreground()) { fail(api, reply, "weapp in background", 12011); return@permission }
                    if (!granted) { fail(api, reply, "user denied", 12007); return@permission }
                    if (!wifi.isWifiEnabled) { fail(api, reply, "wifi not turned on; enable it in system settings", 12005); return@permission }
                    try { if (started == null) { started = call; observe() }; ok(api, reply); observeCurrent() } catch (error: Exception) { runCatching { reset() }; fail(api, reply, error.message ?: "system internal error", 12010) }
                }; return
            }
            if (!ready(api, reply)) return
            when (api) {
                "wx.getConnectedWifi" -> { val info = currentInfo(); if (info == null) fail(api, reply, "no visible connected Wi-Fi information", 12010) else ok(api, reply, JSONObject().put("wifi", describe(info, boolean(value, "partialInfo")))) }
                "wx.getWifiList" -> {
                    if (scanRequested) { fail(api, reply, "duplicate scan request", 12004); return }
                    scanTime = SystemClock.elapsedRealtimeNanos() / 1000; scanRequested = true
                    @Suppress("DEPRECATION") val accepted = wifi.startScan()
                    if (!accepted) { scanRequested = false; fail(api, reply, "OS rejected or throttled Wi-Fi scan; cached results are not a fresh scan", 12010) }
                    else { val acceptedGeneration = generation; main.postDelayed({ if (acceptedGeneration == generation) scanRequested = false }, 30000); ok(api, reply) }
                }
                "wx.connectWifi" -> connect(api, call, reply, value)
            }
        } catch (error: SecurityException) { fail(api, reply, "OS Wi-Fi permission denied", 12007) }
        catch (error: UnsupportedOperationException) { fail(api, reply, error.message ?: "system not support", 12001) }
        catch (error: Exception) { fail(api, reply, error.message ?: "system internal error", 12010) }
    }
    @Suppress("DEPRECATION") private fun currentInfo(): WifiInfo? {
        val active = connection?.network
        val info = if (active != null && Build.VERSION.SDK_INT >= 29) connectivity.getNetworkCapabilities(active)?.transportInfo as? WifiInfo else wifi.connectionInfo
        return info?.takeIf { it.supplicantState == SupplicantState.COMPLETED && ssid(it) != null }
    }
    private fun ssid(info: WifiInfo): String? = info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
    @Suppress("DEPRECATION") private fun describe(info: WifiInfo, partial: Boolean): JSONObject {
        val name = checkNotNull(ssid(info)) { "SSID is redacted by OS" }
        val value = JSONObject().put("SSID", name); if (partial) return value
        val bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" } ?: throw UnsupportedOperationException("BSSID is redacted by OS; request partialInfo")
        val secure = if (Build.VERSION.SDK_INT >= 31 && info.currentSecurityType != WifiInfo.SECURITY_TYPE_UNKNOWN) info.currentSecurityType != WifiInfo.SECURITY_TYPE_OPEN
            else wifi.scanResults.firstOrNull { it.BSSID.equals(bssid, true) }?.let { it.capabilities.contains("WEP") || it.capabilities.contains("PSK") || it.capabilities.contains("EAP") || it.capabilities.contains("SAE") || it.capabilities.contains("OWE") } ?: throw UnsupportedOperationException("OS did not expose security type; request partialInfo or perform a fresh scan")
        check(info.frequency > 0 && info.rssi != -127) { "OS did not expose signal/frequency; request partialInfo" }
        return value.put("BSSID", bssid).put("secure", secure).put("frequency", info.frequency).put("signalStrength", WifiManager.calculateSignalLevel(info.rssi, 101))
    }
    private fun callback(available: (Network) -> Unit = {}, lost: (Network) -> Unit = {}, capabilitiesChanged: (Network, NetworkCapabilities) -> Unit = { _, _ -> }, unavailable: () -> Unit = {}): ConnectivityManager.NetworkCallback {
        fun dispatch(action: () -> Unit) { runCatching(action).onFailure { error -> android.util.Log.e("WeappContainer", "Wi-Fi native callback failed; owned cleanup remains available", error); connection?.takeIf { !it.completed }?.reply?.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Native Wi-Fi callback failed") } }
        return if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) = dispatch { available(network) }
            override fun onLost(network: Network) = dispatch { lost(network) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = dispatch { capabilitiesChanged(network, capabilities) }
            override fun onUnavailable() = dispatch(unavailable)
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = dispatch { available(network) }
            override fun onLost(network: Network) = dispatch { lost(network) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = dispatch { capabilitiesChanged(network, capabilities) }
            override fun onUnavailable() = dispatch(unavailable)
        }
    }
    private fun observe() {
        val accepted = generation
        val observer = callback(available = { if (accepted == generation) observeCurrent() }, lost = { if (accepted == generation) lastConnected = null }, capabilitiesChanged = { _, _ -> if (accepted == generation) observeCurrent() })
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), observer, main)
        monitor = observer
        val broadcasts = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {
            if (accepted != generation || !foreground()) return
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION && scanRequested) {
                scanRequested = false
                if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) return
                runCatching {
                    val results = wifi.scanResults.filter { it.timestamp >= scanTime && it.SSID.isNotEmpty() }.take(64)
                    val list = JSONArray(results.map { JSONObject().put("SSID", it.SSID).put("BSSID", it.BSSID).put("frequency", it.frequency).put("secure", it.capabilities.let { c -> listOf("WEP", "PSK", "EAP", "SAE", "OWE").any(c::contains) }).put("signalStrength", WifiManager.calculateSignalLevel(it.level, 101)) })
                    started?.let { queue(it, "wx.onGetWifiList", JSONObject().put("wifiList", list)) }
                }.onFailure { android.util.Log.e("WeappContainer", "Wi-Fi scan result unavailable", it) }
            }; observeCurrent()
        } }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply { addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION); addAction(WifiManager.WIFI_STATE_CHANGED_ACTION) }
        if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(broadcasts, filter, Context.RECEIVER_NOT_EXPORTED) else application.registerReceiver(broadcasts, filter)
        receiver = broadcasts
    }
    private fun observeCurrent() {
        if (started == null || !foreground()) return
        runCatching {
            val info = currentInfo() ?: return
            connection?.takeIf { !it.completed && it.manual }?.let { completeConnection(it, info) }
            val name = ssid(info) ?: return; val key = "$name/${info.bssid}"
            if (lastConnected == key) return
            lastConnected = key; val owner = checkNotNull(started)
            queue(owner, "wx.onWifiConnectedWithPartialInfo", JSONObject().put("wifi", describe(info, true)))
            runCatching { describe(info, false) }.onSuccess { queue(owner, "wx.onWifiConnected", JSONObject().put("wifi", it)) }
        }.onFailure { android.util.Log.e("WeappContainer", "Wi-Fi connection information unavailable", it) }
    }
    private fun connect(api: String, call: HostCall, reply: HostReply, value: JSONObject) {
        if (connection != null) { fail(api, reply, "duplicate request; stopWifi releases the owned local lease", 12004); return }
        val ssid = value.getString("SSID"); if (ssid.toByteArray(Charsets.UTF_8).size !in 1..32 || ssid.contains('\u0000')) { fail(api, reply, "invalid SSID", 12008); return }
        val bssid = value.optString("BSSID").takeIf { it.isNotEmpty() }; if (bssid != null) require(bssid.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")))
        val password = value.getString("password"); val manual = boolean(value, "maunal"); val partial = boolean(value, "partialInfo")
        if (manual && password.isNotEmpty()) throw UnsupportedOperationException("Manual system settings cannot receive a password; pass empty password and enter credentials there")
        if (!manual && Build.VERSION.SDK_INT < 29) throw UnsupportedOperationException("App-scoped Wi-Fi requests need Android 10+; use maunal with system settings")
        if (!manual && password.isNotEmpty() && (password.length !in 8..63 || password.any { it.code !in 32..126 })) throw UnsupportedOperationException("This adapter supports open or WPA2 ASCII passphrases, not WEP/enterprise/WPA3-only")
        val operation = Connection(call, reply, ssid, bssid, partial, manual); connection = operation
        call.addCancellationHandler { if (connection === operation && !operation.completed) releaseConnection() }
        fun cancel() { if (connection === operation) { runCatching { releaseConnection() }.onFailure { android.util.Log.e("WeappContainer", "Wi-Fi confirmation cleanup needs retry", it) }; fail(api, reply, "user denied", 12007) } }
        operation.dialog = AlertDialog.Builder(checkNotNull(binding)).setTitle("连接 Wi-Fi")
            .setMessage("小程序 ${call.context?.getJSONObject("application")?.getString("appId")} 请求连接 $ssid。系统可能改变设备当前的 Wi-Fi 关联。${if (manual) "请在系统设置中选择网络并输入密码。" else "这是应用的本地网络请求，不提供互联网连接。"}")
            .setPositiveButton("继续连接") { _, _ ->
                operation.dialog = null
                if (connection === operation && !call.isCancelled && foreground()) try { beginConnection(operation, password) }
                catch (error: Throwable) { runCatching { releaseConnection() }; fail(api, reply, error.message ?: "system internal error", 12010) }
            }.setNegativeButton("取消") { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
    }
    private fun beginConnection(operation: Connection, password: String) {
        val api = "wx.connectWifi"; val reply = operation.reply; val ssid = operation.ssid; val bssid = operation.bssid
        if (operation.manual) {
            operation.settingsOpened = true
            checkNotNull(binding).startActivity(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
        } else {
            val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply { if (password.isNotEmpty()) setWpa2Passphrase(password); if (bssid != null) setBssid(MacAddress.fromString(bssid)) }.build()
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
            val cb = callback(available = { network -> if (connection === operation) { operation.network = network; connectivity.getNetworkCapabilities(network)?.let { capabilities -> (capabilities.transportInfo as? WifiInfo)?.let { completeConnection(operation, it) } } } }, capabilitiesChanged = { network, capabilities -> if (connection === operation) { operation.network = network; (capabilities.transportInfo as? WifiInfo)?.let { completeConnection(operation, it) } } }, lost = { network -> if (connection === operation && operation.network == network) { releaseConnection(); lastConnected = null } }, unavailable = { if (connection === operation) { releaseConnection(); fail(api, reply, "OS declined or could not establish requested local Wi-Fi", 12007) } })
            operation.callback = cb
            try { connectivity.requestNetwork(request, cb, main, 30000) } catch (error: Throwable) { releaseConnection(); throw error }
        }
        main.postDelayed({ if (connection === operation && !operation.completed) { releaseConnection(); fail(api, reply, "connection timeout", 12003) } }, 30000)
    }
    private fun completeConnection(operation: Connection, info: WifiInfo) {
        if (connection !== operation || operation.completed || !foreground() || ssid(info) != operation.ssid || operation.bssid != null && !operation.bssid.equals(info.bssid, true)) return
        try { val data = describe(info, operation.partial); operation.completed = true; ok("wx.connectWifi", operation.reply, JSONObject().put("wifi", data)); observeCurrent() }
        catch (error: Exception) { releaseConnection(); fail("wx.connectWifi", operation.reply, error.message ?: "requested information unavailable", 12001) }
    }
    private fun releaseConnection() { val active = connection ?: return; active.dialog?.dismiss(); active.dialog = null; active.callback?.let { connectivity.unregisterNetworkCallback(it) }; connection = null }
    private fun pause(allowManual: Boolean) { val pending = pendingStart; pendingStart = null; pending?.first?.cancel(); pending?.second?.reject(HostErrorCode.CANCELLED, "Wi-Fi start owner left foreground"); consent.reset(); scanRequested = false; events.clear(); lastConnected = null; if (connection?.manual != true || connection?.settingsOpened != true || !allowManual) { val active = connection; releaseConnection(); if (active != null && !active.completed) active.reply.reject(HostErrorCode.CANCELLED, "Wi-Fi owner left foreground") } }
    private fun safePause() { runCatching { pause(true) }.onFailure { android.util.Log.e("WeappContainer", "Wi-Fi lease cleanup needs retry", it) } }
    private fun remove(id: String) { subscriptions.remove(id); sequences.remove(id) }
    private fun sameOwner(a: HostCall, b: HostCall) = a.context?.let { x -> b.context?.let { y -> x.getString("sessionId") == y.getString("sessionId") && x.getJSONObject("application").toString() == y.getJSONObject("application").toString() && x.getJSONObject("account").toString() == y.getJSONObject("account").toString() } } == true
    private fun queue(owner: HostCall, method: String, data: JSONObject) { events[method] = Event(owner, generation, method, data); drain() }
    private fun drain() {
        if (delivering || events.isEmpty()) return
        val event = events.entries.first().also { events.remove(it.key) }.value
        if (event.generation != generation || !foreground()) { drain(); return }; delivering = true
        var future = CompletableFuture.completedFuture<Any?>(null)
        for ((id, call) in subscriptions.toMap()) if (call.method == event.method && sameOwner(call, event.owner)) future = future.thenCompose {
            if (event.generation != generation || !foreground() || subscriptions[id] !== call || call.isCancelled) CompletableFuture.completedFuture(null)
            else { val context = requireNotNull(call.context); val next = (sequences[id] ?: 0) + 1; sequences[id] = next; session?.emitHostApiEvent(JSONObject().put("sessionId", context.getString("sessionId")).put("accountEpoch", context.getJSONObject("account").get("epoch")).put("subscriptionId", id).put("sequence", next.toString()).put("value", event.value)) ?: CompletableFuture.completedFuture(null) }
        }
        future.whenComplete { _, error -> main.post { delivering = false; if (error != null) runCatching { reset() }; drain() } }
    }
    private fun stopModule() {
        generation++; pause(false)
        receiver?.let { application.unregisterReceiver(it) }; receiver = null
        monitor?.let { connectivity.unregisterNetworkCallback(it) }; monitor = null
        started = null; scopes.clear()
    }
    private fun reset() { stopModule(); subscriptions.clear(); sequences.clear() }
    override fun close() { reset(); application.unregisterActivityLifecycleCallbacks(lifecycle); binding = null; session = null }
}
