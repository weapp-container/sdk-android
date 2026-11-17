package com.sonofmagic.weapp.container.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

/** Integrate with the host's Activity Result permission flow; do not request from guest JS. */
fun interface AndroidPermissionBroker {
    fun request(permissions: Array<String>, call: HostCall, completion: (Boolean) -> Unit)
}

/** OS implementations registered explicitly; absent APIs stay unavailable in capabilities. */
class AndroidDeviceApis(
    activity: Activity,
    private val permissions: AndroidPermissionBroker? = null,
) : AutoCloseable {
    private var binding: Activity? = activity
    private val activity: Activity get() = checkNotNull(binding) { "Native Activity is detached" }
    private val scopes = AndroidManagedFileScopes()
    private val consent = AndroidAppPermissionConsent({ this.activity }, { scopes.auxiliaryRoot(it) })
    private val application = activity.application
    private var locationVisible = true
    private var activityVisible = true
    private var locationGeneration = 0L
    private data class LocationStart(val call: HostCall, val reply: HostReply, val generation: Long)
    private var locationStart: LocationStart? = null
    private var locationOwner: HostCall? = null
    private var locationListener: LocationListener? = null
    private var locationManager: LocationManager? = null
    private val locationSubscriptions = mutableMapOf<String, HostCall>()
    private data class LocationEmission(val owner: HostCall, val generation: Long, val method: String, val value: JSONObject)
    private val pendingLocationEvents = linkedMapOf<String, LocationEmission>()
    private var locationDelivering = false
    private data class SingleLocation(val call: HostCall, val reply: HostReply, val manager: LocationManager, val listener: LocationListener)
    private val singleLocations = mutableMapOf<String, SingleLocation>()
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { if (a === binding) { activityVisible = true; resumeLocationStream() } }
        override fun onActivityStopped(a: Activity) { if (a === binding) { activityVisible = false; runCatching { pauseLocations() }.onFailure { android.util.Log.e("WeappContainer", "Location cleanup needs retry", it) } } }
        override fun onActivityDestroyed(a: Activity) { if (a === binding) runCatching { pauseLocations() }.onFailure { android.util.Log.e("WeappContainer", "Location cleanup needs retry", it) } }
        override fun onActivityCreated(a: Activity, state: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
    }
    fun rebindActivity(activity: Activity?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (binding === activity) return
        try { resetResources() } finally { binding = activity; activityVisible = activity != null }
    }
    private val main = Handler(Looper.getMainLooper())
    private var session: WeappContainerSession? = null
    private val cleanup = linkedMapOf<String, () -> Unit>()
    private var toast: Toast? = null
    private var loading: ProgressDialog? = null
    private val sensorEnabled = mutableSetOf<String>()
    private var brightnessBefore: Float? = null
    private var keepScreenBefore: Boolean? = null
    val supportedApis: Set<String> = setOf(
        "wx.getClipboardData", "wx.setClipboardData", "wx.getNetworkType", "wx.onNetworkStatusChange", "wx.offNetworkStatusChange",
        "wx.getBatteryInfo", "wx.getScreenBrightness", "wx.setScreenBrightness", "wx.setKeepScreenOn", "wx.vibrateLong", "wx.vibrateShort",
        "wx.showToast", "wx.hideToast", "wx.showLoading", "wx.hideLoading", "wx.showModal", "wx.showActionSheet", "wx.makePhoneCall",
        "wx.openAppAuthorizeSetting", "wx.getSetting", "wx.authorize", "wx.getLocation", "wx.getFuzzyLocation",
        "wx.startLocationUpdate", "wx.stopLocationUpdate", "wx.onLocationChange", "wx.offLocationChange", "wx.onLocationChangeError", "wx.offLocationChangeError",
        "wx.startAccelerometer", "wx.stopAccelerometer", "wx.onAccelerometerChange", "wx.offAccelerometerChange",
        "wx.startGyroscope", "wx.stopGyroscope", "wx.onGyroscopeChange", "wx.offGyroscopeChange",
        "wx.startCompass", "wx.stopCompass", "wx.onCompassChange", "wx.offCompassChange",
    )
    fun install(target: WeappContainerSession): CompletableFuture<Void> {
        check(session == null) { "Device adapter belongs to one controller" }; session = target; scopes.bind(target); application.registerActivityLifecycleCallbacks(lifecycle)
        return CompletableFuture.allOf(target.registerHostResourceCleanup("device-${java.util.UUID.randomUUID()}") { resetResources() }, target.registerHostVisibilityHandler("location-${java.util.UUID.randomUUID()}") { visible -> locationVisible = visible; if (visible) resumeLocationStream() else pauseLocations() }, *supportedApis.map { api -> target.registerApiHandler(api) { call, reply ->
            val context = call.context
            if (context == null) reply.reject(HostErrorCode.PERMISSION_DENIED, "Trusted native context required")
            else scopes.prepare(context).whenComplete { _, error ->
                if (!call.isCancelled) {
                    if (error != null) reply.reject(HostErrorCode.PERMISSION_DENIED, error.message ?: "Managed permission scope unavailable")
                    else invoke(api, call, reply)
                }
            }
        } }.toTypedArray())
    }
    private fun options(call: HostCall) = call.payload as? JSONObject ?: JSONObject()
    private fun ok(api: String, reply: HostReply, data: JSONObject = JSONObject()) { reply.resolve(data.put("errMsg", "$api:ok")) }
    private fun fail(api: String, reply: HostReply, message: String, code: HostErrorCode = HostErrorCode.INVALID_REQUEST, errCode: Int = -1) {
        reply.reject(code, message, false, JSONObject().put("errMsg", "$api:fail $message").put("errCode", errCode))
    }
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        if (call.context == null) { fail(api, reply, "trusted native context required", HostErrorCode.PERMISSION_DENIED); return }
        val value = options(call)
        try {
            when (api) {
                "wx.getClipboardData" -> {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val data = clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() ?: ""
                    ok(api, reply, JSONObject().put("data", data))
                }
                "wx.setClipboardData" -> {
                    val data = value.getString("data"); require(data.length <= 1048576)
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", data)); ok(api, reply)
                }
                "wx.getNetworkType" -> ok(api, reply, networkType())
                "wx.onNetworkStatusChange" -> {
                    val manager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) { main.post { emit(call, networkType()) } }
                        override fun onLost(network: Network) { main.post { emit(call, networkType()) } }
                        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { main.post { emit(call, networkType()) } }
                    }
                    manager.registerDefaultNetworkCallback(callback)
                    retain(call, reply) { manager.unregisterNetworkCallback(callback) }; ok(api, reply)
                }
                "wx.offNetworkStatusChange" -> { remove(value.getString("subscriptionId")); ok(api, reply) }
                "wx.getBatteryInfo" -> {
                    val manager = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    ok(api, reply, JSONObject().put("level", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("isCharging", manager.isCharging))
                }
                "wx.getScreenBrightness" -> {
                    val brightness = activity.window.attributes.screenBrightness
                    val actual = if (brightness < 0) Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255.0 else brightness.toDouble()
                    ok(api, reply, JSONObject().put("value", actual.coerceIn(0.0, 1.0)))
                }
                "wx.setScreenBrightness" -> {
                    val brightness = value.getDouble("value"); require(brightness.isFinite() && brightness in 0.0..1.0)
                    if (brightnessBefore == null) brightnessBefore = activity.window.attributes.screenBrightness
                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness.toFloat() }
                    ok(api, reply)
                }
                "wx.setKeepScreenOn" -> {
                    if (keepScreenBefore == null) keepScreenBefore = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                    if (value.getBoolean("keepScreenOn")) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    ok(api, reply)
                }
                "wx.vibrateLong", "wx.vibrateShort" -> {
                    val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (!vibrator.hasVibrator()) { fail(api, reply, "vibrator unavailable", HostErrorCode.UNSUPPORTED); return }
                    vibrator.vibrate(VibrationEffect.createOneShot(if (api == "wx.vibrateLong") 400L else 15L, VibrationEffect.DEFAULT_AMPLITUDE)); ok(api, reply)
                }
                "wx.showToast" -> {
                    toast?.cancel(); toast = Toast.makeText(activity, value.getString("title"), if (value.optLong("duration", 1500) > 2000) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).also { it.show() }; ok(api, reply)
                }
                "wx.hideToast" -> { toast?.cancel(); toast = null; ok(api, reply) }
                "wx.showLoading" -> {
                    loading?.dismiss(); loading = ProgressDialog(activity).apply { setMessage(value.optString("title")); setCancelable(false); show() }
                    ok(api, reply)
                }
                "wx.hideLoading" -> { loading?.dismiss(); loading = null; ok(api, reply) }
                "wx.showModal" -> {
                    val dialog = AlertDialog.Builder(activity).setTitle(value.optString("title")).setMessage(value.optString("content"))
                        .setPositiveButton(value.optString("confirmText", "确定")) { _, _ -> ok(api, reply, JSONObject().put("confirm", true).put("cancel", false)) }
                    if (value.optBoolean("showCancel", true)) dialog.setNegativeButton(value.optString("cancelText", "取消")) { _, _ -> ok(api, reply, JSONObject().put("confirm", false).put("cancel", true)) }
                    val shown = dialog.setOnCancelListener { ok(api, reply, JSONObject().put("confirm", false).put("cancel", true)) }.show()
                    call.onCancel = { shown.dismiss() }
                }
                "wx.showActionSheet" -> {
                    val list = value.getJSONArray("itemList"); require(list.length() in 1..6)
                    val shown = AlertDialog.Builder(activity).setItems(Array(list.length()) { list.getString(it) }) { _, index -> ok(api, reply, JSONObject().put("tapIndex", index)) }
                        .setOnCancelListener { fail(api, reply, "cancel", HostErrorCode.CANCELLED) }.show()
                    call.onCancel = { shown.dismiss() }
                }
                "wx.makePhoneCall" -> {
                    val phone = value.getString("phoneNumber"); require(phone.matches(Regex("[+0-9 ()-]{1,64}")))
                    activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))); ok(api, reply)
                }
                "wx.openSetting", "wx.openAppAuthorizeSetting" -> { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))); ok(api, reply) }
                "wx.getSetting" -> ok(api, reply, JSONObject().put("authSetting", authSettings(call)))
                "wx.authorize" -> {
                    val requested = scopePermissions[value.getString("scope")]
                    if (requested == null) fail(api, reply, "scope requires a host-specific authorization flow", HostErrorCode.UNSUPPORTED)
                    else permission(requested, call) { granted -> if (granted) ok(api, reply) else fail(api, reply, "auth deny", HostErrorCode.PERMISSION_DENIED, 2001) }
                }
                "wx.getLocation", "wx.getFuzzyLocation" -> locate(api, call, reply, value)
                "wx.startLocationUpdate", "wx.stopLocationUpdate", "wx.onLocationChange", "wx.offLocationChange", "wx.onLocationChangeError", "wx.offLocationChangeError" -> locationStream(api, call, reply, value)
                "wx.openLocation" -> {
                    require(value.optString("type", "wgs84") == "wgs84") { "GCJ-02 coordinates require a licensed coordinate adapter" }
                    val latitude = value.getDouble("latitude"); val longitude = value.getDouble("longitude"); require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude"))); ok(api, reply)
                }
                else -> sensor(api, call, reply, value)
            }
        } catch (error: SecurityException) { fail(api, reply, "OS permission denied", HostErrorCode.PERMISSION_DENIED, 2001) }
        catch (error: Exception) { fail(api, reply, error.message ?: "native operation failed") }
    }
    private val scopePermissions = mapOf("scope.userLocation" to arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), "scope.camera" to arrayOf(Manifest.permission.CAMERA), "scope.record" to arrayOf(Manifest.permission.RECORD_AUDIO))
    private fun authSettings(call: HostCall) = JSONObject().apply {
        for ((scope, requested) in scopePermissions) consent.setting(call, scope, requested.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })?.let { put(scope, it) }
    }
    private fun permission(requested: Array<String>, call: HostCall, completion: (Boolean) -> Unit) {
        val scope = when { requested.any { it == Manifest.permission.CAMERA } -> "scope.camera"; requested.any { it == Manifest.permission.RECORD_AUDIO } -> "scope.record"; else -> "scope.userLocation" }
        consent.request(scope, call) { granted ->
            if (!granted) completion(false)
            else if (requested.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) completion(true)
            else if (permissions == null) completion(false)
            else permissions.request(requested, call) { result -> main.post { if (!call.isCancelled && binding != null) completion(result && requested.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) } }
        }
    }
    private fun networkType(): JSONObject {
        val manager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        // Cellular generation cannot be inferred from transport alone.
        val type = when { network == null -> "none"; network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "unknown"; else -> "unknown" }
        return JSONObject().put("networkType", type).put("isConnected", network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    }
    private fun locate(api: String, call: HostCall, reply: HostReply, value: JSONObject) {
        if (value.optString("type", "wgs84") != "wgs84") { fail(api, reply, "GCJ-02 requires a coordinate adapter", HostErrorCode.UNSUPPORTED); return }
        val accepted = locationGeneration
        val precise = api != "wx.getFuzzyLocation"; val requested = arrayOf(if (precise) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION)
        permission(requested, call) { granted ->
            if (accepted != locationGeneration || !locationVisible || !activityVisible || binding == null || call.isCancelled) { reply.reject(HostErrorCode.CANCELLED, "Location owner left the foreground"); return@permission }
            if (!granted) { fail(api, reply, "auth deny", HostErrorCode.PERMISSION_DENIED, 2001); return@permission }
            val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = if (precise && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            if (!manager.isProviderEnabled(provider)) { fail(api, reply, "location service disabled"); return@permission }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    try { removeSingleLocation(call.id) } catch (error: Throwable) { fail(api, reply, error.message ?: "Native location cleanup failed"); return }
                    if (!call.isCancelled && accepted == locationGeneration) {
                        val result = if (!precise) JSONObject().put("latitude", kotlin.math.round(location.latitude * 100) / 100).put("longitude", kotlin.math.round(location.longitude * 100) / 100)
                        else JSONObject().put("latitude", location.latitude).put("longitude", location.longitude).put("accuracy", location.accuracy.toDouble())
                            .put("speed", location.speed.toDouble()).put("altitude", location.altitude).put("horizontalAccuracy", location.accuracy.toDouble()).put("verticalAccuracy", location.verticalAccuracyMeters.toDouble())
                        ok(api, reply, result)
                    }
                }
                override fun onProviderDisabled(provider: String) { runCatching { removeSingleLocation(call.id) }; fail(api, reply, "location service disabled") }
                override fun onProviderEnabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            singleLocations[call.id] = SingleLocation(call, reply, manager, listener)
            try { manager.requestLocationUpdates(provider, 0, 0f, listener, Looper.getMainLooper()) } catch (error: Throwable) { removeSingleLocation(call.id); throw error }
            call.addCancellationHandler { removeSingleLocation(call.id) }
        }
    }
    private fun removeSingleLocation(id: String) { val pending = singleLocations[id] ?: return; pending.manager.removeUpdates(pending.listener); if (singleLocations[id] === pending) singleLocations.remove(id) }
    private fun locationStream(api: String, call: HostCall, reply: HostReply, value: JSONObject) {
        if (api.startsWith("wx.off")) { remove(value.getString("subscriptionId")); ok(api, reply); return }
        if (api.startsWith("wx.on")) {
            val id = value.getString("subscriptionId"); require(locationSubscriptions.size < 32)
            locationSubscriptions[id] = call; retain(call, reply) { locationSubscriptions.remove(id) }; ok(api, reply); return
        }
        if (api == "wx.stopLocationUpdate") { stopLocationStream(true); ok(api, reply); return }
        if (value.optString("type", "wgs84") != "wgs84") { fail(api, reply, "GCJ-02 requires a coordinate adapter", HostErrorCode.UNSUPPORTED); return }
        check(locationStart == null && locationVisible && activityVisible) { "Location start is pending or outside the foreground" }
        val pending = LocationStart(call, reply, locationGeneration); locationStart = pending
        call.addCancellationHandler { if (locationStart === pending) locationStart = null }
        permission(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), call) { granted ->
            if (locationStart !== pending || pending.generation != locationGeneration || !locationVisible || !activityVisible || call.isCancelled || binding == null) { reply.reject(HostErrorCode.CANCELLED, "Location start owner retired"); return@permission }
            locationStart = null
            if (!granted) { fail(api, reply, "auth deny", HostErrorCode.PERMISSION_DENIED, 2001); return@permission }
            try {
                if (locationOwner == null) {
                    check(locationListener == null) { "Previous native location cleanup requires retry" }
                    // This API returns no resource object. The registered native
                    // cleanup owns the continuing producer until stop/account reset.
                    locationOwner = call
                }
                startNativeLocation(); ok(api, reply)
            } catch (error: Throwable) { if (locationOwner === call) locationOwner = null; fail(api, reply, error.message ?: "Native location start failed") }
        }
    }
    private fun startNativeLocation() {
        val owner = locationOwner ?: return
        if (locationListener != null || !locationVisible || !activityVisible || binding == null || owner.isCancelled) return
        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { manager.isProviderEnabled(it) } ?: error("Location service disabled")
        val accepted = locationGeneration
        val listener = object : LocationListener {
            override fun onLocationChanged(value: Location) {
                if (locationListener !== this || locationOwner !== owner || accepted != locationGeneration || !locationVisible || !activityVisible || owner.isCancelled) return
                queueLocationEvent(owner, "wx.onLocationChange", JSONObject().put("latitude", value.latitude).put("longitude", value.longitude).put("accuracy", value.accuracy.toDouble()).put("horizontalAccuracy", value.accuracy.toDouble()).put("verticalAccuracy", value.verticalAccuracyMeters.toDouble()).put("altitude", value.altitude).put("speed", value.speed.toDouble()))
            }
            override fun onProviderDisabled(provider: String) { if (locationListener === this && locationOwner === owner) queueLocationEvent(owner, "wx.onLocationChangeError", JSONObject().put("errMsg", "startLocationUpdate:fail location service disabled").put("errCode", 2)) }
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        locationManager = manager; locationListener = listener
        try { manager.requestLocationUpdates(provider, 1000, 0f, listener, Looper.getMainLooper()) }
        catch (error: Throwable) { try { stopNativeLocation() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }; throw error }
    }
    private fun stopNativeLocation() { locationListener?.let { locationManager?.removeUpdates(it) }; locationListener = null; locationManager = null }
    private fun stopLocationStream(clearOwner: Boolean) {
        val pending = locationStart; locationStart = null; pending?.call?.cancel(); pending?.reply?.reject(HostErrorCode.CANCELLED, "Location update stopped")
        stopNativeLocation(); pendingLocationEvents.clear(); if (clearOwner) locationOwner = null
    }
    private fun pauseLocations() {
        locationGeneration++; stopLocationStream(false)
        for ((id, pending) in singleLocations.toMap()) { removeSingleLocation(id); pending.reply.reject(HostErrorCode.CANCELLED, "Location owner left the foreground") }
    }
    private fun resumeLocationStream() { runCatching { startNativeLocation() }.onFailure { error -> locationOwner?.let { queueLocationEvent(it, "wx.onLocationChangeError", JSONObject().put("errMsg", "startLocationUpdate:fail ${error.message}").put("errCode", if (error is SecurityException) 2001 else 2)) } } }
    private fun queueLocationEvent(owner: HostCall, method: String, value: JSONObject) { pendingLocationEvents[method] = LocationEmission(owner, locationGeneration, method, value); drainLocationEvents() }
    private fun drainLocationEvents() {
        if (locationDelivering || pendingLocationEvents.isEmpty()) return
        val event = pendingLocationEvents.entries.first().also { pendingLocationEvents.remove(it.key) }.value
        if (event.owner !== locationOwner || event.generation != locationGeneration || event.owner.isCancelled) { drainLocationEvents(); return }
        locationDelivering = true
        val recipients = locationSubscriptions.values.filter { it.method == event.method && !it.isCancelled && it.context?.getString("sessionId") == event.owner.context?.getString("sessionId") && it.context?.getJSONObject("account")?.toString() == event.owner.context?.getJSONObject("account")?.toString() && it.context?.getJSONObject("application")?.toString() == event.owner.context?.getJSONObject("application")?.toString() }.toList()
        var delivery = CompletableFuture.completedFuture<Any?>(null)
        for (subscription in recipients) delivery = delivery.thenCompose {
            if (event.owner !== locationOwner || event.generation != locationGeneration || subscription.isCancelled) CompletableFuture.completedFuture(null)
            else emitAsync(subscription, event.value)
        }
        delivery.whenComplete { _, error -> main.post { locationDelivering = false; if (error != null && event.owner === locationOwner) runCatching { stopLocationStream(true) }; drainLocationEvents() } }
    }
    private fun sensor(api: String, call: HostCall, reply: HostReply, value: JSONObject) {
        val family = when { api.contains("Accelerometer") -> "Accelerometer"; api.contains("Gyroscope") -> "Gyroscope"; api.contains("Compass") -> "Compass"; else -> { fail(api, reply, "not installed", HostErrorCode.UNSUPPORTED); return } }
        when {
            api.startsWith("wx.start") -> {
                if (value.optString("interval", "normal") != "normal") { fail(api, reply, "this adapter supports normal sampling only", HostErrorCode.UNSUPPORTED); return }
                val manager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val type = when (family) { "Accelerometer" -> Sensor.TYPE_ACCELEROMETER; "Gyroscope" -> Sensor.TYPE_GYROSCOPE; else -> Sensor.TYPE_ROTATION_VECTOR }
                if (manager.getDefaultSensor(type) == null) { fail(api, reply, "sensor unavailable", HostErrorCode.UNSUPPORTED); return }
                sensorEnabled.add(family); ok(api, reply)
            }
            api.startsWith("wx.stop") -> { sensorEnabled.remove(family); ok(api, reply) }
            api.startsWith("wx.off") -> { remove(value.getString("subscriptionId")); ok(api, reply) }
            else -> {
                val manager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val sensor = manager.getDefaultSensor(when (family) { "Accelerometer" -> Sensor.TYPE_ACCELEROMETER; "Gyroscope" -> Sensor.TYPE_GYROSCOPE; else -> Sensor.TYPE_ROTATION_VECTOR })
                if (sensor == null) { fail(api, reply, "sensor unavailable", HostErrorCode.UNSUPPORTED); return }
                val listener = object : SensorEventListener {
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    override fun onSensorChanged(event: SensorEvent) {
                        if (family !in sensorEnabled || call.isCancelled) return
                        val data = if (family == "Compass") {
                            val matrix = FloatArray(9); SensorManager.getRotationMatrixFromVector(matrix, event.values)
                            val orientation = FloatArray(3); SensorManager.getOrientation(matrix, orientation)
                            JSONObject().put("direction", (Math.toDegrees(orientation[0].toDouble()) + 360) % 360).put("accuracy", event.accuracy)
                        } else JSONObject().put("x", event.values[0] / if (family == "Accelerometer") SensorManager.GRAVITY_EARTH else 1f)
                            .put("y", event.values[1] / if (family == "Accelerometer") SensorManager.GRAVITY_EARTH else 1f).put("z", event.values[2] / if (family == "Accelerometer") SensorManager.GRAVITY_EARTH else 1f)
                        emit(call, data)
                    }
                }
                if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) { fail(api, reply, "sensor registration failed"); return }
                retain(call, reply) { manager.unregisterListener(listener) }; ok(api, reply)
            }
        }
    }
    private val sequences = mutableMapOf<String, Long>()
    private fun emit(call: HostCall, value: JSONObject) { emitAsync(call, value) }
    private fun emitAsync(call: HostCall, value: JSONObject): CompletableFuture<Any?> {
        if (call.isCancelled) return CompletableFuture.completedFuture(null)
        val context = call.context ?: return CompletableFuture.completedFuture(null); val id = options(call).optString("subscriptionId"); if (id.isEmpty()) return CompletableFuture.completedFuture(null)
        val sequence = (sequences[id] ?: 0) + 1; sequences[id] = sequence
        return session?.emitHostApiEvent(JSONObject().put("sessionId", context.getString("sessionId")).put("accountEpoch", context.getJSONObject("account").get("epoch"))
            .put("subscriptionId", id).put("sequence", sequence).put("value", value)) ?: CompletableFuture.completedFuture(null)
    }
    private fun retain(call: HostCall, reply: HostReply, dispose: () -> Unit) {
        val id = options(call).getString("subscriptionId"); cleanup[id] = dispose
        call.onCancel = { remove(id) }; reply.retainUntilCancelled()
    }
    private fun remove(id: String) { cleanup[id]?.invoke(); cleanup.remove(id); sequences.remove(id) }
    private fun restoreDisplay() {
        brightnessBefore?.let { original -> activity.window.attributes = activity.window.attributes.apply { screenBrightness = original } }; brightnessBefore = null
        keepScreenBefore?.let { if (it) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }; keepScreenBefore = null
    }
    private fun resetResources() {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error) } }
        for (id in cleanup.keys.toList()) attempt { remove(id) }
        attempt { pauseLocations(); stopLocationStream(true) }
        attempt { consent.reset() }; scopes.clear()
        attempt { toast?.cancel(); toast = null }; attempt { loading?.dismiss(); loading = null }
        attempt { restoreDisplay() }; sensorEnabled.clear()
        if (failure != null) throw failure!!
    }
    override fun close() { check(Looper.myLooper() == Looper.getMainLooper()); resetResources(); application.unregisterActivityLifecycleCallbacks(lifecycle); session = null }
}
