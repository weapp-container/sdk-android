package com.sonofmagic.weapp.container.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Owns only its foreground iBeacon scan; never changes the shared Bluetooth radio. */
class AndroidBeaconApis(activity: Activity, private val permissions: AndroidPermissionBroker? = null) : AutoCloseable {
    private var binding: Activity? = activity
    private val application = activity.application
    private val main = Handler(Looper.getMainLooper())
    private val scopes = AndroidManagedFileScopes()
    private val consent = AndroidAppPermissionConsent({ checkNotNull(binding) }, { scopes.auxiliaryRoot(it) })
    private val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var session: WeappContainerSession? = null
    private var visible = true
    private var activityVisible = true
    private var generation = 0L
    private var owner: HostCall? = null
    private var pending: Pair<HostCall, HostReply>? = null
    private var uuids = emptySet<UUID>()
    private var scanner: BluetoothLeScanner? = null
    private var scan: ScanCallback? = null
    private var receiver: BroadcastReceiver? = null
    private val beacons = linkedMapOf<String, AndroidBeaconPayload>()
    private val subscriptions = linkedMapOf<String, HostCall>()
    private val sequences = mutableMapOf<String, Long>()
    private data class Event(val owner: HostCall, val generation: Long, val method: String, val value: JSONObject)
    private val events = linkedMapOf<String, Event>()
    private var delivering = false
    val supportedApis = setOf("wx.startBeaconDiscovery", "wx.stopBeaconDiscovery", "wx.getBeacons", "wx.onBeaconUpdate", "wx.offBeaconUpdate", "wx.onBeaconServiceChange", "wx.offBeaconServiceChange")
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { if (a === binding) { activityVisible = true; reconcile() } }
        override fun onActivityStopped(a: Activity) { if (a === binding) { activityVisible = false; safePause() } }
        override fun onActivityDestroyed(a: Activity) { if (a === binding) safePause() }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    }
    fun install(target: WeappContainerSession): CompletableFuture<Void> {
        check(session == null); session = target; scopes.bind(target); application.registerActivityLifecycleCallbacks(lifecycle)
        val observer = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { reconcile() } }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(LocationManager.MODE_CHANGED_ACTION) }
        if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(observer, filter, Context.RECEIVER_NOT_EXPORTED) else application.registerReceiver(observer, filter)
        receiver = observer
        return CompletableFuture.allOf(target.registerHostResourceCleanup("beacon-${UUID.randomUUID()}") { reset() }, target.registerHostVisibilityHandler("beacon-${UUID.randomUUID()}") { visible = it; if (it) reconcile() else pause() }, *supportedApis.map { api -> target.registerApiHandler(api) { call, reply ->
            val context = call.context
            if (context == null) fail(api, reply, "trusted native context required", 11006)
            else scopes.prepare(context).whenComplete { _, error -> if (!call.isCancelled) { if (error != null) fail(api, reply, "permission scope unavailable", 11002) else invoke(api, call, reply) } }
        } }.toTypedArray())
    }
    fun rebindActivity(activity: Activity?) { if (binding !== activity) { reset(); binding = activity; activityVisible = activity != null } }
    private fun ok(api: String, reply: HostReply, data: JSONObject = JSONObject()) { reply.resolve(data.put("errMsg", "$api:ok").put("errCode", 0)) }
    private fun fail(api: String, reply: HostReply, text: String, code: Int) { reply.reject(if (code == 11000) HostErrorCode.UNSUPPORTED else HostErrorCode.INVALID_REQUEST, text, false, JSONObject().put("errMsg", "$api:fail $text").put("errCode", code)) }
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        val value = call.payload as? JSONObject ?: JSONObject()
        try {
            when {
                api.startsWith("wx.off") -> { remove(value.getString("subscriptionId")); ok(api, reply) }
                api.startsWith("wx.on") -> { val id = value.getString("subscriptionId"); require(subscriptions.size < 32 && id !in subscriptions); subscriptions[id] = call; sequences[id] = 0; reply.retainUntilCancelled(); call.addCancellationHandler { if (subscriptions[id] === call) remove(id) }; ok(api, reply) }
                api == "wx.getBeacons" -> { if (owner == null) fail(api, reply, "not startBeaconDiscovery", 11004) else ok(api, reply, snapshot()) }
                api == "wx.stopBeaconDiscovery" -> { val previous = owner; if (previous == null && pending == null) fail(api, reply, "not startBeaconDiscovery", 11004) else { stop(); previous?.let { queue(it, "wx.onBeaconServiceChange", service()) }; ok(api, reply) } }
                else -> {
                    if (owner != null || pending != null) { fail(api, reply, "already start", 11003); return }
                    val list = value.getJSONArray("uuids"); require(list.length() in 1..20)
                    val requested = (0 until list.length()).map { val text = list.getString(it); require(text.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))); UUID.fromString(text) }.toSet()
                    check(visible && activityVisible && binding != null) { "foreground host required" }
                    val accepted = generation; pending = call to reply
                    call.addCancellationHandler { if (pending?.first === call) pending = null }
                    permission(call) { granted ->
                        if (pending?.first !== call || accepted != generation || call.isCancelled) return@permission
                        pending = null
                        if (!granted) { fail(api, reply, "location or Bluetooth permission denied", 11002); return@permission }
                        if (!available()) { fail(api, reply, "Bluetooth or location service unavailable", if (locationAvailable()) 11001 else 11002); return@permission }
                        try { owner = call; uuids = requested; beacons.clear(); startScan(); ok(api, reply); queue(call, "wx.onBeaconServiceChange", service()) }
                        catch (error: Throwable) { runCatching { stop() }; fail(api, reply, error.message ?: "system error", 11005) }
                    }
                }
            }
        } catch (error: Exception) { fail(api, reply, error.message ?: "invalid data", 11006) }
    }
    private fun requestedPermissions() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun permission(call: HostCall, completion: (Boolean) -> Unit) {
        consent.request("scope.userLocation", call) { location -> if (!location || call.isCancelled || pending?.first !== call) completion(false) else consent.request("scope.bluetooth", call) { bluetooth ->
            val host = binding
            if (!bluetooth || host == null) completion(false)
            else if (requestedPermissions().all { host.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) completion(true)
            else permissions?.request(requestedPermissions(), call) { granted -> main.post { if (!call.isCancelled) completion(granted && binding?.let { a -> requestedPermissions().all { a.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } } == true) } } ?: completion(false)
        } }
    }
    private fun locationAvailable(): Boolean = runCatching { binding?.let { host -> val location = host.getSystemService(Context.LOCATION_SERVICE) as LocationManager; location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } == true }.getOrDefault(false)
    private fun available(): Boolean = runCatching { binding?.let { host -> requestedPermissions().all { host.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } } == true && manager.adapter?.isEnabled == true && locationAvailable() }.getOrDefault(false)
    private fun snapshot() = JSONObject().put("beacons", JSONArray(beacons.values.map { JSONObject().put("uuid", it.uuid.toString()).put("major", it.major).put("minor", it.minor).put("rssi", it.rssi).put("accuracy", it.accuracy).put("proximity", 0) }))
    private fun service() = JSONObject().put("available", available()).put("discovering", scan != null)
    private fun startScan() {
        if (scan != null || owner == null || !visible || !activityVisible) return
        val source = checkNotNull(manager.adapter?.bluetoothLeScanner) { "BLE scanner unavailable" }
        val accepted = generation; val acceptedOwner = checkNotNull(owner); val requested = uuids
        val callback = object : ScanCallback() {
            private val latest = linkedMapOf<String, AndroidBeaconPayload>()
            private var scheduled = false
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val payload = result.scanRecord?.getManufacturerSpecificData(0x004c)?.let { AndroidBeaconPayload.decode(it, result.rssi) } ?: return
                if (payload.uuid !in requested) return
                synchronized(latest) { if (payload.key in latest || latest.size < 64) latest[payload.key] = payload; if (scheduled) return; scheduled = true }
                main.post {
                    val values = synchronized(latest) { val copy = latest.values.toList(); latest.clear(); scheduled = false; copy }
                    if (scan !== this || owner !== acceptedOwner || accepted != generation || !visible || !activityVisible) return@post
                    for (value in values) { if (value.key !in beacons && beacons.size >= 64) beacons.remove(beacons.keys.first()); beacons[value.key] = value }
                    queue(acceptedOwner, "wx.onBeaconUpdate", snapshot())
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.take(64).forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) } }
            override fun onScanFailed(errorCode: Int) { main.post { if (scan === this) { scan = null; scanner = null; queue(acceptedOwner, "wx.onBeaconServiceChange", service()) } } }
        }
        scanner = source; scan = callback
        try { source.startScan(listOf(ScanFilter.Builder().setManufacturerData(0x004c, byteArrayOf(2, 0x15)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback) }
        catch (error: Throwable) { try { stopNative() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }; throw error }
    }
    private fun stopNative() { scan?.let { scanner?.stopScan(it) }; scan = null; scanner = null }
    private fun pause() { generation++; val active = pending; pending = null; active?.first?.cancel(); active?.second?.reject(HostErrorCode.CANCELLED, "Beacon owner left foreground"); stopNative(); events.clear(); owner?.let { queue(it, "wx.onBeaconServiceChange", service()) } }
    private fun safePause() { runCatching { pause() }.onFailure { android.util.Log.e("WeappContainer", "Beacon scan cleanup needs retry", it) } }
    private fun reconcile() { runCatching { if (available() && visible && activityVisible) startScan() else stopNative(); owner?.let { queue(it, "wx.onBeaconServiceChange", service()) } }.onFailure { android.util.Log.e("WeappContainer", "Beacon scan cleanup needs retry", it) } }
    private fun stop() { pause(); owner = null; uuids = emptySet(); beacons.clear(); events.clear() }
    private fun remove(id: String) { subscriptions.remove(id); sequences.remove(id) }
    private fun sameOwner(a: HostCall, b: HostCall) = a.context?.let { x -> b.context?.let { y -> x.getString("sessionId") == y.getString("sessionId") && x.getJSONObject("application").toString() == y.getJSONObject("application").toString() && x.getJSONObject("account").toString() == y.getJSONObject("account").toString() } } == true
    private fun queue(owner: HostCall, method: String, value: JSONObject) { events[method] = Event(owner, generation, method, value); drain() }
    private fun drain() {
        if (delivering || events.isEmpty()) return
        val event = events.entries.first().also { events.remove(it.key) }.value
        if (event.generation != generation) { drain(); return }; delivering = true
        var future = CompletableFuture.completedFuture<Any?>(null)
        for ((id, call) in subscriptions.toMap()) if (call.method == event.method && sameOwner(call, event.owner)) future = future.thenCompose {
            if (event.generation != generation || subscriptions[id] !== call || call.isCancelled) CompletableFuture.completedFuture(null)
            else { val context = requireNotNull(call.context); val next = (sequences[id] ?: 0) + 1; sequences[id] = next; session?.emitHostApiEvent(JSONObject().put("sessionId", context.getString("sessionId")).put("accountEpoch", context.getJSONObject("account").get("epoch")).put("subscriptionId", id).put("sequence", next.toString()).put("value", event.value)) ?: CompletableFuture.completedFuture(null) }
        }
        future.whenComplete { _, error -> main.post { delivering = false; if (error != null) runCatching { stop() }; drain() } }
    }
    private fun reset() { stop(); consent.reset(); scopes.clear(); subscriptions.clear(); sequences.clear() }
    override fun close() { reset(); receiver?.let { application.unregisterReceiver(it) }; receiver = null; application.unregisterActivityLifecycleCallbacks(lifecycle); binding = null; session = null }
}
