package com.sonofmagic.weapp.container.sdk

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Each installation owns its scanner, GATTs and callbacks; closing never disables the host radio. */
class AndroidBluetoothApis(activity: Activity, private val permissions: AndroidPermissionBroker? = null) : AutoCloseable {
    private var binding: Activity? = activity
    private val activity: Activity get() = checkNotNull(binding) { "Native Activity is detached" }
    private val scopes = AndroidManagedFileScopes()
    private val consent = AndroidAppPermissionConsent({ this.activity }, { scopes.auxiliaryRoot(it) })
    fun rebindActivity(activity: Activity?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (binding === activity) return
        try { reset() } finally { binding = activity }
    }
    private val main = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var session: WeappContainerSession? = null
    private var opened = false
    private var scan: ScanCallback? = null
    private var receiver: BroadcastReceiver? = null
    private val devices = linkedMapOf<String, JSONObject>()
    private val gatts = mutableMapOf<String, BluetoothGatt>()
    private val connected = mutableSetOf<String>()
    private val mtu = mutableMapOf<String, Int>()
    private data class Operation(val call: HostCall, val reply: HostReply, val kind: String, val uuid: UUID? = null)
    private val operations = mutableMapOf<String, Operation>()
    private val subscriptions = mutableMapOf<String, HostCall>()
    private val sequence = mutableMapOf<String, Long>()
    val supportedApis = setOf("wx.openBluetoothAdapter", "wx.closeBluetoothAdapter", "wx.getBluetoothAdapterState", "wx.onBluetoothAdapterStateChange", "wx.offBluetoothAdapterStateChange",
        "wx.startBluetoothDevicesDiscovery", "wx.stopBluetoothDevicesDiscovery", "wx.getBluetoothDevices", "wx.getConnectedBluetoothDevices", "wx.onBluetoothDeviceFound", "wx.offBluetoothDeviceFound",
        "wx.createBLEConnection", "wx.closeBLEConnection", "wx.getBLEDeviceServices", "wx.getBLEDeviceCharacteristics", "wx.readBLECharacteristicValue", "wx.writeBLECharacteristicValue", "wx.notifyBLECharacteristicValueChange",
        "wx.onBLEConnectionStateChange", "wx.offBLEConnectionStateChange", "wx.onBLECharacteristicValueChange", "wx.offBLECharacteristicValueChange", "wx.getBLEDeviceRSSI", "wx.setBLEMTU", "wx.getBLEMTU", "wx.openSystemBluetoothSetting")
    fun install(target: WeappContainerSession): CompletableFuture<Void> {
        check(session == null); session = target; scopes.bind(target)
        return CompletableFuture.allOf(target.registerHostResourceCleanup("bluetooth-${UUID.randomUUID()}") { reset() }, *supportedApis.map { api -> target.registerApiHandler(api) { call, reply ->
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
    private fun ok(api: String, reply: HostReply, value: JSONObject = JSONObject()) { reply.resolve(value.put("errMsg", "$api:ok")) }
    private fun fail(api: String, reply: HostReply, message: String, code: Int = 10008) { reply.reject(if (code == 2001) HostErrorCode.PERMISSION_DENIED else HostErrorCode.INVALID_REQUEST, message, false, JSONObject().put("errCode", code).put("errMsg", "$api:fail $message")) }
    private fun uuid(value: String): UUID = UUID.fromString(if (value.length == 4) "0000$value-0000-1000-8000-00805f9b34fb" else value)
    private fun binary(bytes: ByteArray) = JSONObject().put("__weappBinary", JSONObject().put("type", "ArrayBuffer").put("bytes", JSONArray().apply { bytes.forEach { put(it.toInt() and 255) } }))
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        if (call.isCancelled) return
        val value = call.payload as? JSONObject ?: JSONObject()
        try {
            if (api == "wx.openBluetoothAdapter") {
                val requested = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
                permission(requested, call) { granted ->
                    if (!granted) { fail(api, reply, "OS permission denied", 2001); return@permission }
                    val adapter = manager.adapter
                    if (adapter == null || !adapter.isEnabled) { fail(api, reply, "Bluetooth unavailable or disabled", 10001); return@permission }
                    opened = true; observeAdapter(); ok(api, reply)
                }; return
            }
            if (api == "wx.openSystemBluetoothSetting") { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); ok(api, reply); return }
            if (api.startsWith("wx.on") || api.startsWith("wx.off")) {
                val id = value.getString("subscriptionId")
                if (api.startsWith("wx.off")) { subscriptions.remove(id); sequence.remove(id) }
                else { subscriptions[id] = call; call.onCancel = { subscriptions.remove(id); sequence.remove(id) }; reply.retainUntilCancelled() }
                ok(api, reply); return
            }
            check(opened) { "Bluetooth adapter has not been opened" }
            when (api) {
                "wx.closeBluetoothAdapter" -> { reset(); ok(api, reply) }
                "wx.getBluetoothAdapterState" -> ok(api, reply, JSONObject().put("available", manager.adapter?.isEnabled == true).put("discovering", scan != null))
                "wx.getBluetoothDevices" -> ok(api, reply, JSONObject().put("devices", JSONArray(devices.values.toList())))
                "wx.getConnectedBluetoothDevices" -> {
                    val services = value.optJSONArray("services")?.let { list -> (0 until list.length()).map { uuid(list.getString(it)) }.toSet() } ?: emptySet()
                    val result = JSONArray(); for (id in connected) if (services.isEmpty() || gatts[id]?.services?.any { it.uuid in services } == true) result.put(devices[id] ?: JSONObject().put("deviceId", id).put("name", gatts[id]?.device?.name ?: ""))
                    ok(api, reply, JSONObject().put("devices", result))
                }
                "wx.startBluetoothDevicesDiscovery" -> startScan(api, call, reply, value)
                "wx.stopBluetoothDevicesDiscovery" -> { stopScan(); ok(api, reply) }
                "wx.createBLEConnection" -> {
                    val id = value.getString("deviceId"); check(!gatts.containsKey(id)) { "Device already has an owned connection" }
                    val device = manager.adapter.getRemoteDevice(id)
                    operations[id] = Operation(call, reply, "connect")
                    val gatt = try { device.connectGatt(activity, false, callback, BluetoothDevice.TRANSPORT_LE) }
                        catch (error: Throwable) { operations.remove(id); throw error }
                    if (gatt == null) operations.remove(id)
                    checkNotNull(gatt) { "GATT creation failed" }; gatts[id] = gatt; mtu[id] = 23
                    call.onCancel = { retire(id) }
                }
                "wx.closeBLEConnection" -> { val id = value.getString("deviceId"); require(gatts.containsKey(id)); retire(id); ok(api, reply) }
                "wx.getBLEDeviceServices" -> {
                    val id = value.getString("deviceId"); val gatt = owned(id)
                    check(!operations.containsKey(id)) { "One GATT operation at a time is required per device" }
                    if (gatt.services.isNotEmpty()) services(api, reply, gatt) else begin(id, Operation(call, reply, "services")) { gatt.discoverServices() }
                }
                "wx.getBLEDeviceCharacteristics" -> {
                    val service = owned(value.getString("deviceId")).getService(uuid(value.getString("serviceId"))); requireNotNull(service) { "Service not found" }
                    val result = JSONArray(); for (characteristic in service.characteristics) {
                        val flags = characteristic.properties
                        result.put(JSONObject().put("uuid", characteristic.uuid.toString()).put("properties", JSONObject()
                            .put("read", flags and BluetoothGattCharacteristic.PROPERTY_READ != 0).put("write", flags and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                            .put("writeNoResponse", flags and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0).put("notify", flags and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0).put("indicate", flags and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)))
                    }; ok(api, reply, JSONObject().put("characteristics", result))
                }
                "wx.readBLECharacteristicValue", "wx.writeBLECharacteristicValue", "wx.notifyBLECharacteristicValueChange" -> {
                    val id = value.getString("deviceId"); val gatt = owned(id)
                    check(!operations.containsKey(id)) { "One GATT operation at a time is required per device" }
                    val characteristic = gatt.getService(uuid(value.getString("serviceId")))?.getCharacteristic(uuid(value.getString("characteristicId"))); requireNotNull(characteristic) { "Characteristic not found" }
                    if (api == "wx.readBLECharacteristicValue") begin(id, Operation(call, reply, "read", characteristic.uuid)) { gatt.readCharacteristic(characteristic) }
                    else if (api == "wx.writeBLECharacteristicValue") {
                        val bytes = value.getJSONObject("value").getJSONObject("__weappBinary").getJSONArray("bytes"); require(bytes.length() <= 512)
                        characteristic.value = ByteArray(bytes.length()) { index -> bytes.getInt(index).also { require(it in 0..255) }.toByte() }
                        characteristic.writeType = if (value.optString("writeType") == "writeNoResponse") BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        begin(id, Operation(call, reply, "write", characteristic.uuid)) { gatt.writeCharacteristic(characteristic) }
                    } else {
                        val enabled = value.getBoolean("state"); val descriptor = characteristic.getDescriptor(uuid("2902")); requireNotNull(descriptor) { "CCCD not found" }
                        check(gatt.setCharacteristicNotification(characteristic, enabled)) { "Cannot configure local notification" }
                        descriptor.value = if (!enabled) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE else if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        begin(id, Operation(call, reply, "notify", characteristic.uuid)) { gatt.writeDescriptor(descriptor) }
                    }
                }
                "wx.getBLEDeviceRSSI" -> { val id = value.getString("deviceId"); begin(id, Operation(call, reply, "rssi")) { owned(id).readRemoteRssi() } }
                "wx.setBLEMTU" -> { val id = value.getString("deviceId"); val requested = value.getInt("mtu"); require(requested in 23..517); begin(id, Operation(call, reply, "mtu")) { owned(id).requestMtu(requested) } }
                "wx.getBLEMTU" -> { val id = value.getString("deviceId"); owned(id); ok(api, reply, JSONObject().put("mtu", mtu[id] ?: 23)) }
            }
        } catch (error: SecurityException) { fail(api, reply, "OS permission denied", 2001) }
        catch (error: Exception) { fail(api, reply, error.message ?: "Bluetooth operation failed") }
    }
    private fun permission(requested: Array<String>, call: HostCall, completion: (Boolean) -> Unit) {
        consent.request("scope.bluetooth", call) { appGranted ->
            if (!appGranted) completion(false)
            else if (requested.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) completion(true)
            else if (permissions == null) completion(false)
            else permissions.request(requested, call) { granted -> main.post { if (!call.isCancelled && binding != null) completion(granted && requested.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) } }
        }
    }
    private fun startScan(api: String, call: HostCall, reply: HostReply, value: JSONObject) {
        val requested = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permission(requested, call) { granted ->
            if (!granted) { fail(api, reply, "OS permission denied", 2001); return@permission }
            try {
                check(scan == null) { "Discovery already running" }
                val filters = value.optJSONArray("services")?.let { list -> (0 until list.length()).map { ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid(list.getString(it)))).build() } } ?: emptyList()
                val scanner = manager.adapter.bluetoothLeScanner; checkNotNull(scanner) { "BLE scanner unavailable" }
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) { main.post {
                        if (scan !== this || !opened) return@post
                        val id = result.device.address; val prior = devices.containsKey(id); val record = result.scanRecord
                        val device = JSONObject().put("deviceId", id).put("name", result.device.name ?: "").put("localName", record?.deviceName ?: "").put("RSSI", result.rssi)
                            .put("advertisData", binary(record?.bytes ?: byteArrayOf())).put("advertisServiceUUIDs", JSONArray(record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList<String>()))
                        devices[id] = device
                        if (!prior || value.optBoolean("allowDuplicatesKey")) emit("wx.onBluetoothDeviceFound", JSONObject().put("devices", JSONArray().put(device)))
                    } }
                    override fun onScanFailed(errorCode: Int) { main.post { if (scan === this) { scan = null; emit("wx.onBluetoothAdapterStateChange", JSONObject().put("available", true).put("discovering", false).put("errCode", errorCode)) } } }
                }
                scan = callback
                try { scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback) }
                catch (error: Throwable) { scan = null; try { scanner.stopScan(callback) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }; throw error }
                ok(api, reply)
            } catch (error: Exception) { fail(api, reply, error.message ?: "BLE scan failed") }
        }
    }
    private fun stopScan() { val active = scan; scan = null; if (active != null) manager.adapter?.bluetoothLeScanner?.stopScan(active) }
    private fun observeAdapter() {
        if (receiver != null) return
        val observer = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) emit("wx.onBluetoothAdapterStateChange", JSONObject().put("available", intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF) == BluetoothAdapter.STATE_ON).put("discovering", scan != null)) } }
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(observer, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED) else activity.registerReceiver(observer, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        receiver = observer
    }
    private fun owned(id: String): BluetoothGatt { require(id in connected) { "Device not connected by this app" }; return checkNotNull(gatts[id]) }
    private fun begin(id: String, operation: Operation, submit: () -> Boolean) {
        check(!operations.containsKey(id)) { "One GATT operation at a time is required per device" }; operations[id] = operation
        operation.call.onCancel = { retire(id) }
        try {
            if (!submit()) { operations.remove(id); fail(operation.call.method, operation.reply, "GATT operation was not accepted") }
        } catch (error: Throwable) { operations.remove(id); throw error }
    }
    private fun finish(gatt: BluetoothGatt, kind: String, status: Int, uuid: UUID? = null, data: JSONObject = JSONObject()) {
        val id = gatt.device.address; if (gatts[id] !== gatt) return
        val operation = operations[id]?.takeIf { it.kind == kind && (it.uuid == null || it.uuid == uuid) } ?: return
        operations.remove(id)
        if (!operation.call.isCancelled) { if (status == BluetoothGatt.GATT_SUCCESS) ok(operation.call.method, operation.reply, data) else fail(operation.call.method, operation.reply, "GATT status $status", status) }
    }
    private fun services(api: String, reply: HostReply, gatt: BluetoothGatt) { ok(api, reply, JSONObject().put("services", JSONArray(gatt.services.map { JSONObject().put("uuid", it.uuid.toString()).put("isPrimary", it.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) }))) }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { main.post {
            val id = gatt.device.address; if (gatts[id] !== gatt) return@post
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) { connected.add(id); finish(gatt, "connect", status) }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                operations.remove(id)?.let { fail(it.call.method, it.reply, "Device disconnected", if (status == 0) 10006 else status) }; retire(id)
            }
            emit("wx.onBLEConnectionStateChange", JSONObject().put("deviceId", id).put("connected", id in connected))
        } }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { main.post {
            val id = gatt.device.address; if (gatts[id] !== gatt) return@post
            val operation = operations[id]?.takeIf { it.kind == "services" } ?: return@post; operations.remove(id)
            if (status == BluetoothGatt.GATT_SUCCESS) services(operation.call.method, operation.reply, gatt) else fail(operation.call.method, operation.reply, "Service discovery failed", status)
        } }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { characteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status) }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) { characteristicRead(gatt, characteristic, value.copyOf(), status) }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { characteristicChanged(gatt, characteristic, characteristic.value ?: byteArrayOf()) }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { characteristicChanged(gatt, characteristic, value.copyOf()) }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { main.post { finish(gatt, "write", status, characteristic.uuid) } }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { main.post { finish(gatt, "notify", status, descriptor.characteristic.uuid) } }
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) { main.post { finish(gatt, "rssi", status, data = JSONObject().put("RSSI", rssi)) } }
        override fun onMtuChanged(gatt: BluetoothGatt, value: Int, status: Int) { main.post { if (gatts[gatt.device.address] === gatt && status == BluetoothGatt.GATT_SUCCESS) mtu[gatt.device.address] = value; finish(gatt, "mtu", status, data = JSONObject().put("mtu", value)) } }
    }
    private fun characteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray, status: Int) { val data = bytes.copyOf(); main.post { finish(gatt, "read", status, characteristic.uuid, JSONObject().put("value", binary(data))); if (status == BluetoothGatt.GATT_SUCCESS) characteristicChanged(gatt, characteristic, data) } }
    private fun characteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) { val data = bytes.copyOf(); main.post {
        if (gatts[gatt.device.address] === gatt) emit("wx.onBLECharacteristicValueChange", JSONObject().put("deviceId", gatt.device.address).put("serviceId", characteristic.service.uuid.toString()).put("characteristicId", characteristic.uuid.toString()).put("value", binary(data)))
    } }
    private fun emit(api: String, value: JSONObject) {
        for ((id, call) in subscriptions.toMap()) if (call.method == api && !call.isCancelled) {
            val context = call.context ?: continue; val next = (sequence[id] ?: 0) + 1; sequence[id] = next
            session?.emitHostApiEvent(JSONObject().put("sessionId", context.getString("sessionId")).put("accountEpoch", context.getJSONObject("account").get("epoch")).put("subscriptionId", id).put("sequence", next).put("value", value))
        }
    }
    private fun retire(id: String) {
        operations.remove(id)?.let { if (!it.call.isCancelled) fail(it.call.method, it.reply, "Owned connection closed", 10006) }
        connected.remove(id); mtu.remove(id)
        gatts[id]?.let { gatt ->
            var failure: Throwable? = null
            try { gatt.disconnect() } catch (error: Throwable) { failure = error }
            try { gatt.close(); gatts.remove(id) } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
            if (failure != null) throw failure!!
        }
    }
    private fun reset() {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error) } }
        attempt { stopScan() }
        attempt { consent.reset() }; scopes.clear()
        for (id in gatts.keys.toList()) attempt { retire(id) }
        receiver?.let { observer -> attempt { activity.unregisterReceiver(observer); receiver = null } }
        subscriptions.clear(); sequence.clear(); devices.clear(); opened = false
        if (failure != null) throw failure!!
    }
    override fun close() { check(Looper.myLooper() == Looper.getMainLooper()); reset(); session = null }
}
