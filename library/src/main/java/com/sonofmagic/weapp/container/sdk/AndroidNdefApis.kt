package com.sonofmagic.weapp.container.sdk

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** NDEF reader/writer only. Other NFC technology methods stay unregistered. */
internal class AndroidNdefApis(activity: Activity, private val files: AndroidManagedFiles) : AutoCloseable {
    private var binding: Activity? = activity
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val consent = AndroidAppPermissionConsent({ checkNotNull(binding) }, { files.auxiliaryRoot(it) })
    private var session: WeappContainerSession? = null
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private var reader: String? = null
    private var timeoutMs = 5000L
    @Volatile private var native: Ndef? = null
    private val adapters = mutableMapOf<String, HostCall>()
    private data class Child(val parent: String, val owner: HostCall, var message: JSONObject? = null)
    private val children = mutableMapOf<String, Child>()
    private data class Subscription(val call: HostCall, val objectId: String, val event: String, var sequence: Long = 0)
    private val subscriptions = mutableMapOf<String, Subscription>()
    private class Work(val reply: HostReply) { val drained = CountDownLatch(1) }
    private val work = mutableSetOf<Work>()
    private val latestTag = AtomicReference<Pair<Long, Tag>?>(null)
    private val tagScheduled = AtomicBoolean(false)
    private data class Discovery(val generation: Long, val native: Ndef, val id: ByteArray)
    private val discovery = AtomicReference<Discovery?>(null)
    private val discoveryScheduled = AtomicBoolean(false)
    private var eventDelivery = CompletableFuture.completedFuture<Any?>(null)
    private var pendingEvents = 0
    private var pendingBytes = 0
    private val supportedApis = setOf("wx.getNFCAdapter", "object:NFCAdapter.tech", "object:NFCAdapter.startDiscovery", "object:NFCAdapter.stopDiscovery", "object:NFCAdapter.getNdef", "object:NFCAdapter.onDiscovered", "object:NFCAdapter.offDiscovered", "object:Ndef.connect", "object:Ndef.close", "object:Ndef.isConnected", "object:Ndef.setTimeout", "object:Ndef.writeNdefMessage", "object:Ndef.onNdefMessage", "object:Ndef.offNdefMessage")
    fun install(target: WeappContainerSession): CompletableFuture<Void> {
        check(session == null); session = target
        if (adapter == null || binding?.checkSelfPermission(Manifest.permission.NFC) != PackageManager.PERMISSION_GRANTED) return CompletableFuture.completedFuture(null)
        return CompletableFuture.allOf(target.registerHostResourceCleanup("nfc-${UUID.randomUUID()}") { reset() }, *supportedApis.map { api -> target.registerApiHandler(api) { call, reply ->
            try { check(!closed && binding != null && !call.isCancelled); invoke(api, call, reply) }
            catch (error: Throwable) { fail(reply, error) }
        } }.toTypedArray())
    }
    private fun sameOwner(a: HostCall, b: HostCall): Boolean = a.context?.let { x -> b.context?.let { y -> x.getString("sessionId") == y.getString("sessionId") && x.getJSONObject("application").toString() == y.getJSONObject("application").toString() && x.getJSONObject("account").toString() == y.getJSONObject("account").toString() } } == true
    private fun ok(reply: HostReply, api: String, value: JSONObject = JSONObject()) { reply.resolve(value.put("errMsg", "${api.substringAfterLast('.')}:ok")) }
    private fun fail(reply: HostReply, error: Throwable) { reply.reject(if (error is SecurityException) HostErrorCode.PERMISSION_DENIED else HostErrorCode.INVALID_REQUEST, error.message ?: "NDEF operation failed", false, JSONObject().put("errCode", -1).put("nativeException", error.javaClass.name)) }
    private fun binary(bytes: ByteArray) = JSONObject().put("__weappBinary", JSONObject().put("type", "ArrayBuffer").put("bytes", JSONArray().apply { bytes.forEach { put(it.toInt() and 255) } }))
    private fun bytes(value: Any?): ByteArray {
        val objectValue = value as? JSONObject ?: error("NDEF fields require ArrayBuffer")
        val marker = objectValue.getJSONObject("__weappBinary"); require(marker.getString("type") == "ArrayBuffer"); val array = marker.getJSONArray("bytes"); require(array.length() <= 1048576)
        return ByteArray(array.length()) { val byte = array.getDouble(it); require(byte.isFinite() && byte in 0.0..255.0 && byte == kotlin.math.floor(byte)); byte.toInt().toByte() }
    }
    private fun emit(objectId: String, event: String, value: JSONObject, only: String? = null) {
        val recipients = subscriptions.toMap().filter { (id, subscription) -> (only == null || only == id) && subscription.objectId == objectId && subscription.event == event && !subscription.call.isCancelled }
        if (recipients.isEmpty()) return
        val size = value.toString().toByteArray().size
        if (pendingEvents >= 16 || size > 8 * 1048576 - pendingBytes) { stop(); return }
        val accepted = generation; pendingEvents++; pendingBytes += size
        var delivery = eventDelivery
        for ((id, subscription) in recipients) delivery = delivery.thenCompose {
            if (closed || generation != accepted || subscription.call.isCancelled) CompletableFuture.completedFuture(null)
            else { val context = requireNotNull(subscription.call.context)
                session!!.emitHostApiEvent(JSONObject().put("sessionId", context.getString("sessionId")).put("accountEpoch", context.getJSONObject("account").get("epoch")).put("subscriptionId", id).put("objectId", objectId).put("sequence", (++subscription.sequence).toString()).put("event", event).put("value", value))
            }
        }
        eventDelivery = delivery.handle { _, _ -> null }
        delivery.whenComplete { _, error -> main.post { pendingEvents--; pendingBytes -= size; if (error != null && accepted == generation) runCatching { stop() } } }
    }
    private fun messageValue(message: NdefMessage): JSONObject {
        require(message.byteArrayLength <= 1048576)
        return JSONObject().put("records", JSONArray().apply { message.records.forEach { record -> put(JSONObject().put("tnf", record.tnf.toInt()).put("type", binary(record.type)).put("id", binary(record.id)).put("payload", binary(record.payload))) } })
    }
    private fun <T> io(call: HostCall, reply: HostReply, operation: () -> T, completion: (T) -> Unit) {
        val accepted = generation; val current = checkNotNull(native) { "Discover an NDEF tag first" }; val item = Work(reply)
        synchronized(work) { check(work.size < 8); work.add(item) }
        val timedOut = AtomicBoolean(false)
        val timeout = Runnable { if (item.drained.count > 0) { timedOut.set(true); runCatching { current.close() } } }
        main.postDelayed(timeout, timeoutMs)
        call.addCancellationHandler { runCatching { current.close() }.getOrThrow() }
        try { worker.execute {
            val result = runCatching { check(generation == accepted && native === current && !call.isCancelled); operation() }
            main.removeCallbacks(timeout); synchronized(work) { work.remove(item) }; item.drained.countDown()
            main.post { if (!call.isCancelled) { if (closed || generation != accepted || native !== current) reply.reject(HostErrorCode.CANCELLED, "NDEF tag or owner retired") else if (timedOut.get()) reply.reject(HostErrorCode.TIMEOUT, "Native NDEF timeout") else result.fold({ runCatching { completion(it) }.onFailure { fail(reply, it) } }, { fail(reply, it) }) } }
        } } catch (error: Throwable) { main.removeCallbacks(timeout); synchronized(work) { work.remove(item) }; item.drained.countDown(); throw error }
    }
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        val value = call.payload as? JSONObject ?: JSONObject(); val id = value.getString("objectId")
        if (api == "wx.getNFCAdapter") {
            require(adapters.size < 16); adapters[id] = call; reply.retainUntilCancelled(); call.addCancellationHandler { if (reader == id) stop(); adapters.remove(id); children.entries.removeAll { it.value.parent == id } }
            ok(reply, api, JSONObject().put("objectId", id).put("properties", JSONObject().put("tech", JSONObject().put("ndef", "NDEF")))); return
        }
        if (api == "object:NFCAdapter.getNdef") {
            val parent = value.getString("receiverId"); require(adapters[parent]?.let { sameOwner(it, call) } == true && children.size < 32)
            children[id] = Child(parent, call); reply.retainUntilCancelled(); call.addCancellationHandler { children.remove(id); if (reader == parent) native?.close() }; ok(reply, api, JSONObject().put("objectId", id)); return
        }
        val child = children[id]; val parent = if (api.startsWith("object:Ndef.")) checkNotNull(child) { "NDEF object retired" }.parent else id
        require(adapters[parent]?.let { sameOwner(it, call) } == true)
        if (api.contains(".on") || api.contains(".off")) {
            val subscriptionId = value.getString("subscriptionId")
            if (api.contains(".off") || value.optString("action") == "unsubscribe") subscriptions.remove(subscriptionId)
            else { require(subscriptions.size < 128); subscriptions[subscriptionId] = Subscription(call, id, api.substringAfterLast('.').removePrefix("on")); reply.retainUntilCancelled(); call.addCancellationHandler { subscriptions.remove(subscriptionId) } }
            ok(reply, api)
            if (api == "object:Ndef.onNdefMessage" && value.optString("action") != "unsubscribe") child?.message?.let { emit(id, "NdefMessage", it, subscriptionId) }
            return
        }
        when (api) {
            "object:NFCAdapter.tech" -> ok(reply, api)
            "object:NFCAdapter.startDiscovery" -> {
                require(reader == null && adapter?.isEnabled == true)
                val accepted = generation
                files.prepareScope(requireNotNull(call.context)).whenComplete { _, error -> main.post {
                    if (closed || generation != accepted || call.isCancelled) return@post
                    if (error != null) { fail(reply, error); return@post }
                    consent.request("scope.nfc", call) { granted ->
                        if (!granted) reply.reject(HostErrorCode.PERMISSION_DENIED, "App NFC permission denied")
                        else if (generation == accepted && !closed && !call.isCancelled) runCatching {
                            check(reader == null); reader = parent
                            adapter!!.enableReaderMode(checkNotNull(binding), { tag -> if (!closed && accepted == generation) { latestTag.set(accepted to tag); scheduleTag() } }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
                            ok(reply, api)
                        }.onFailure { reader = null; fail(reply, it) }
                    }
                } }
            }
            "object:NFCAdapter.stopDiscovery" -> { if (reader == parent) stop(); ok(reply, api) }
            "object:Ndef.isConnected" -> { require(reader == parent); ok(reply, api, JSONObject().put("isConnected", native?.isConnected == true)) }
            "object:Ndef.setTimeout" -> { val requested = value.getLong("timeout"); require(requested in 100..30000); timeoutMs = requested; ok(reply, api) }
            "object:Ndef.close" -> { require(reader == parent); val current = native; io(call, reply, { current?.close(); Unit }) { ok(reply, api) } }
            "object:Ndef.connect" -> {
                require(reader == parent); val current = checkNotNull(native)
                io(call, reply, { if (!current.isConnected) current.connect(); current.ndefMessage }) { message ->
                    if (message != null) child?.message = messageValue(message)
                    ok(reply, api); child?.message?.let { emit(id, "NdefMessage", it) }
                }
            }
            "object:Ndef.writeNdefMessage" -> {
                require(reader == parent); val current = checkNotNull(native); val records = mutableListOf<NdefRecord>()
                val texts = value.optJSONArray("texts") ?: JSONArray(); for (i in 0 until texts.length()) records.add(NdefRecord.createTextRecord("en", texts.getString(i)))
                val uris = value.optJSONArray("uris") ?: JSONArray(); for (i in 0 until uris.length()) records.add(NdefRecord.createUri(uris.getString(i)))
                val raw = value.optJSONArray("records") ?: JSONArray(); require(texts.length() + uris.length() + raw.length() in 1..64)
                for (i in 0 until raw.length()) { val record = raw.getJSONObject(i); val tnf = record.getDouble("tnf"); require(tnf.isFinite() && tnf in 0.0..7.0 && tnf == kotlin.math.floor(tnf)); records.add(NdefRecord(tnf.toInt().toShort(), bytes(record.get("type")), bytes(record.get("id")), bytes(record.get("payload")))) }
                val message = NdefMessage(records.toTypedArray()); require(message.byteArrayLength <= 1048576)
                io(call, reply, { check(current.isConnected && current.isWritable && current.maxSize >= message.byteArrayLength); current.writeNdefMessage(message); Unit }) { ok(reply, api) }
            }
        }
    }
    private fun scheduleTag() { if (tagScheduled.compareAndSet(false, true)) worker.execute {
        try {
            while (true) {
                val (accepted, tag) = latestTag.getAndSet(null) ?: break
                if (accepted != generation || closed) continue
                val current = Ndef.get(tag) ?: continue
                native?.close(); if (accepted != generation || closed) { current.close(); continue }; native = current
                discovery.set(Discovery(accepted, current, tag.id)); scheduleDiscovery()
            }
        } catch (error: Throwable) { android.util.Log.e("WeappContainer", "NFC discovery failed", error) }
        finally { tagScheduled.set(false); if (latestTag.get() != null && !closed) scheduleTag() }
    } }
    private fun scheduleDiscovery() { if (discoveryScheduled.compareAndSet(false, true)) main.post {
        val value = discovery.getAndSet(null); discoveryScheduled.set(false)
        val owner = reader
        if (value != null && value.generation == generation && owner != null && native === value.native) { children.values.filter { it.parent == owner }.forEach { it.message = null }; emit(owner, "Discovered", JSONObject().put("id", binary(value.id)).put("techs", JSONArray().put("NDEF"))) }
        if (discovery.get() != null && !closed) scheduleDiscovery()
    } }
    fun stop() {
        if (closed) return
        generation++; consent.reset(); latestTag.set(null); discovery.set(null)
        if (reader != null) { binding?.let { adapter?.disableReaderMode(it) }; reader = null }
        native?.close()
        val pending = synchronized(work) { work.toList() }; pending.forEach { it.reply.reject(HostErrorCode.CANCELLED, "NDEF discovery stopped") }; check(pending.all { it.drained.await(5, TimeUnit.SECONDS) }) { "NDEF I/O has not drained" }
        val discoveryDrained = CountDownLatch(1); worker.execute { discoveryDrained.countDown() }
        check(discoveryDrained.await(5, TimeUnit.SECONDS)) { "NDEF discovery has not drained" }; native?.close(); native = null
    }
    fun reset() { stop(); adapters.clear(); children.clear(); subscriptions.clear(); timeoutMs = 5000 }
    fun rebindActivity(activity: Activity?) { if (binding !== activity) { reset(); binding = activity } }
    override fun close() { if (closed) return; reset(); closed = true; worker.shutdown(); check(worker.awaitTermination(5, TimeUnit.SECONDS)) { "NDEF worker has not retired" }; binding = null }
}
