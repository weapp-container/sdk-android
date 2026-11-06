package com.sonofmagic.weapp.container.sdk

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.*
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
import java.util.concurrent.atomic.AtomicLong

/** NFC tag technologies, scoped to this Activity reader and execution owner. */
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
    @Volatile private var native: TagTechnology? = null
    @Volatile private var tag: Tag? = null
    private val tagEpoch = AtomicLong(0)
    private val adapters = mutableMapOf<String, HostCall>()
    private data class Child(val parent: String, val owner: HostCall, val technology: String, var epoch: Long? = null, var message: JSONObject? = null)
    private val children = mutableMapOf<String, Child>()
    private data class Subscription(val call: HostCall, val objectId: String, val event: String, var sequence: Long = 0)
    private val subscriptions = mutableMapOf<String, Subscription>()
    private class Work(val reply: HostReply, val terminal: AndroidNfcTerminal)
    private val work = mutableSetOf<Work>()
    private val latestTag = AtomicReference<Pair<Long, Tag>?>(null)
    private val tagScheduled = AtomicBoolean(false)
    private data class Discovery(val generation: Long, val tag: Tag, val epoch: Long)
    private val discovery = AtomicReference<Discovery?>(null)
    private val discoveryScheduled = AtomicBoolean(false)
    private var eventDelivery = CompletableFuture.completedFuture<Any?>(null)
    private var pendingEvents = 0
    private var pendingBytes = 0
    private val supportedApis = setOf("object:IsoDep.close", "object:IsoDep.connect", "object:IsoDep.getHistoricalBytes", "object:IsoDep.getMaxTransceiveLength", "object:IsoDep.isConnected", "object:IsoDep.setTimeout", "object:IsoDep.transceive", "object:MifareClassic.close", "object:MifareClassic.connect", "object:MifareClassic.getMaxTransceiveLength", "object:MifareClassic.isConnected", "object:MifareClassic.setTimeout", "object:MifareClassic.transceive", "object:MifareUltralight.close", "object:MifareUltralight.connect", "object:MifareUltralight.getMaxTransceiveLength", "object:MifareUltralight.isConnected", "object:MifareUltralight.setTimeout", "object:MifareUltralight.transceive", "object:NFCAdapter.getIsoDep", "object:NFCAdapter.getMifareClassic", "object:NFCAdapter.getMifareUltralight", "object:NFCAdapter.getNdef", "object:NFCAdapter.getNfcA", "object:NFCAdapter.getNfcB", "object:NFCAdapter.getNfcF", "object:NFCAdapter.getNfcV", "object:NFCAdapter.offDiscovered", "object:NFCAdapter.onDiscovered", "object:NFCAdapter.startDiscovery", "object:NFCAdapter.stopDiscovery", "object:NFCAdapter.tech", "object:Ndef.close", "object:Ndef.connect", "object:Ndef.isConnected", "object:Ndef.offNdefMessage", "object:Ndef.onNdefMessage", "object:Ndef.setTimeout", "object:Ndef.writeNdefMessage", "object:NfcA.close", "object:NfcA.connect", "object:NfcA.getAtqa", "object:NfcA.getMaxTransceiveLength", "object:NfcA.getSak", "object:NfcA.isConnected", "object:NfcA.setTimeout", "object:NfcA.transceive", "object:NfcB.close", "object:NfcB.connect", "object:NfcB.getMaxTransceiveLength", "object:NfcB.isConnected", "object:NfcB.setTimeout", "object:NfcB.transceive", "object:NfcF.close", "object:NfcF.connect", "object:NfcF.getMaxTransceiveLength", "object:NfcF.isConnected", "object:NfcF.setTimeout", "object:NfcF.transceive", "object:NfcV.close", "object:NfcV.connect", "object:NfcV.getMaxTransceiveLength", "object:NfcV.isConnected", "object:NfcV.setTimeout", "object:NfcV.transceive", "wx.getNFCAdapter")
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
    private fun fail(reply: HostReply, error: Throwable) { reply.reject(when(error) { is SecurityException -> HostErrorCode.PERMISSION_DENIED; is UnsupportedOperationException -> HostErrorCode.UNSUPPORTED; else -> HostErrorCode.INVALID_REQUEST }, error.message ?: "NFC operation failed", false, JSONObject().put("errCode", -1).put("nativeException", error.javaClass.name)) }
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
        val accepted = generation; val current = checkNotNull(native) { "Discover an NFC tag first" }; val item = Work(reply, AndroidNfcTerminal { current.close() })
        synchronized(work) { check(work.isEmpty()) { "Another native NFC operation is active" }; work.add(item) }
        val timedOut = AtomicBoolean(false)
        val timeout = Runnable { if (!item.terminal.finished) { timedOut.set(true); runCatching { item.terminal.requestStop() } } }
        main.postDelayed(timeout, timeoutMs)
        call.addCancellationHandler { item.terminal.requestStop() }
        try { worker.execute {
            val result = runCatching { check(generation == accepted && native === current && !call.isCancelled); operation() }
            main.removeCallbacks(timeout); item.terminal.finish(); synchronized(work) { work.remove(item) }
            main.post { if (!call.isCancelled) { if (closed || generation != accepted || native !== current) reply.reject(HostErrorCode.CANCELLED, "NDEF tag or owner retired") else if (timedOut.get()) reply.reject(HostErrorCode.TIMEOUT, "Native NDEF timeout") else result.fold({ runCatching { completion(it) }.onFailure { fail(reply, it) } }, { fail(reply, it) }) } }
        } } catch (error: Throwable) { main.removeCallbacks(timeout); item.terminal.finish(); synchronized(work) { work.remove(item) }; throw error }
    }
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        val value = call.payload as? JSONObject ?: JSONObject(); val id = value.getString("objectId")
        if (api == "wx.getNFCAdapter") {
            require(adapters.size < 16); adapters[id] = call; reply.retainUntilCancelled(); call.addCancellationHandler { if (reader == id) stop(); adapters.remove(id); children.entries.removeAll { it.value.parent == id } }
            ok(reply, api, JSONObject().put("objectId", id).put("properties", JSONObject().put("tech", JSONObject().put("ndef", "NDEF").put("isoDep", "ISO_DEP").put("mifareClassic", "MIFARE_CLASSIC").put("mifareUltralight", "MIFARE_ULTRALIGHT").put("nfcA", "NFC_A").put("nfcB", "NFC_B").put("nfcF", "NFC_F").put("nfcV", "NFC_V")))); return
        }
        if (api.startsWith("object:NFCAdapter.get")) {
            val parent = value.getString("receiverId"); require(adapters[parent]?.let { sameOwner(it, call) } == true && children.size < 32)
            val technology = api.substringAfter("object:NFCAdapter.get"); require(technology in technologyNames)
            children[id] = Child(parent, call, technology, if (tag == null) null else tagEpoch.get()); reply.retainUntilCancelled(); call.addCancellationHandler { children.remove(id); if (reader == parent) native?.close() }; ok(reply, api, JSONObject().put("objectId", id)); return
        }
        val child = children[id]; val parent = if (!api.startsWith("object:NFCAdapter.")) checkNotNull(child) { "NDEF object retired" }.parent else id
        require(adapters[parent]?.let { sameOwner(it, call) } == true)
        if (child != null && tag != null && !api.contains(".off") && value.optString("action") != "unsubscribe") { val epoch = tagEpoch.get(); if (child.epoch == null) child.epoch = epoch else require(child.epoch == epoch) { "NFC technology object belongs to a retired tag" } }
        if (api.contains(".on") || api.contains(".off")) {
            val subscriptionId = value.getString("subscriptionId")
            if (api.contains(".off") || value.optString("action") == "unsubscribe") subscriptions.remove(subscriptionId)
            else { require(subscriptions.size < 128); subscriptions[subscriptionId] = Subscription(call, id, api.substringAfterLast('.').removePrefix("on")); reply.retainUntilCancelled(); call.addCancellationHandler { subscriptions.remove(subscriptionId) } }
            ok(reply, api)
            if (api == "object:Ndef.onNdefMessage" && value.optString("action") != "unsubscribe") child?.message?.let { emit(id, "NdefMessage", it, subscriptionId) }
            return
        }
        if (child != null && child.technology != "Ndef") { technology(api, call, reply, child); return }
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
            "object:Ndef.isConnected" -> { require(reader == parent); ok(reply, api, JSONObject().put("isConnected", (native as? Ndef)?.isConnected == true)) }
            "object:Ndef.setTimeout" -> { val requested = value.getDouble("timeout"); require(requested.isFinite() && requested == kotlin.math.floor(requested) && requested in 100.0..30000.0); timeoutMs = requested.toLong(); ok(reply, api) }
            "object:Ndef.close" -> { require(reader == parent); val current = native as? Ndef ?: error("NDEF is not connected"); io(call, reply, { current.close(); Unit }) { ok(reply, api) } }
            "object:Ndef.connect" -> {
                require(reader == parent); val previous = native; val current = Ndef.get(checkNotNull(tag)) ?: error("This tag does not support NDEF")
                synchronized(work) { check(work.isEmpty()) }; native = current
                io(call, reply, { if (previous !== current) previous?.close(); if (!current.isConnected) current.connect(); current.ndefMessage }) { message ->
                    if (message != null) child?.message = messageValue(message)
                    ok(reply, api); child?.message?.let { emit(id, "NdefMessage", it) }
                }
            }
            "object:Ndef.writeNdefMessage" -> {
                require(reader == parent); val current = native as? Ndef ?: error("Connect NDEF first"); val records = mutableListOf<NdefRecord>()
                val texts = value.optJSONArray("texts") ?: JSONArray(); for (i in 0 until texts.length()) records.add(NdefRecord.createTextRecord("en", texts.getString(i)))
                val uris = value.optJSONArray("uris") ?: JSONArray(); for (i in 0 until uris.length()) records.add(NdefRecord.createUri(uris.getString(i)))
                val raw = value.optJSONArray("records") ?: JSONArray(); require(texts.length() + uris.length() + raw.length() in 1..64)
                for (i in 0 until raw.length()) { val record = raw.getJSONObject(i); val tnf = record.getDouble("tnf"); require(tnf.isFinite() && tnf in 0.0..7.0 && tnf == kotlin.math.floor(tnf)); records.add(NdefRecord(tnf.toInt().toShort(), bytes(record.get("type")), bytes(record.get("id")), bytes(record.get("payload")))) }
                val message = NdefMessage(records.toTypedArray()); require(message.byteArrayLength <= 1048576)
                io(call, reply, { check(current.isConnected && current.isWritable && current.maxSize >= message.byteArrayLength); current.writeNdefMessage(message); Unit }) { ok(reply, api) }
            }
        }
    }
    private val technologyNames = mapOf("Ndef" to "NDEF", "IsoDep" to "ISO_DEP", "MifareClassic" to "MIFARE_CLASSIC", "MifareUltralight" to "MIFARE_ULTRALIGHT", "NfcA" to "NFC_A", "NfcB" to "NFC_B", "NfcF" to "NFC_F", "NfcV" to "NFC_V")
    private fun nativeTechnology(technology: String, tag: Tag): TagTechnology? = when (technology) {
        "Ndef" -> Ndef.get(tag)
        "IsoDep" -> IsoDep.get(tag)
        "MifareClassic" -> MifareClassic.get(tag)
        "MifareUltralight" -> MifareUltralight.get(tag)
        "NfcA" -> NfcA.get(tag)
        "NfcB" -> NfcB.get(tag)
        "NfcF" -> NfcF.get(tag)
        "NfcV" -> NfcV.get(tag)
        else -> null
    }
    private fun maximum(current: TagTechnology): Int = when (current) {
        is IsoDep -> current.maxTransceiveLength
        is MifareClassic -> current.maxTransceiveLength
        is MifareUltralight -> current.maxTransceiveLength
        is NfcA -> current.maxTransceiveLength
        is NfcB -> current.maxTransceiveLength
        is NfcF -> current.maxTransceiveLength
        is NfcV -> current.maxTransceiveLength
        else -> error("NDEF has no transceive maximum")
    }
    private fun configureTimeout(current: TagTechnology) {
        val timeout = timeoutMs.toInt()
        when (current) {
            is IsoDep -> current.timeout = timeout
            is MifareClassic -> current.timeout = timeout
            is MifareUltralight -> current.timeout = timeout
            is NfcA -> current.timeout = timeout
            is NfcF -> current.timeout = timeout
            // NfcB/NfcV expose no native setter; io() enforces the same total deadline.
        }
    }
    private fun transceive(current: TagTechnology, data: ByteArray): ByteArray = when (current) {
        is IsoDep -> current.transceive(data)
        is MifareClassic -> current.transceive(data)
        is MifareUltralight -> current.transceive(data)
        is NfcA -> current.transceive(data)
        is NfcB -> current.transceive(data)
        is NfcF -> current.transceive(data)
        is NfcV -> current.transceive(data)
        else -> error("NDEF has no raw transceive")
    }
    private fun technology(api: String, call: HostCall, reply: HostReply, child: Child) {
        require(reader == child.parent) { "Start discovery first" }
        val value = call.payload as JSONObject
        val available = nativeTechnology(child.technology, checkNotNull(tag)) ?: throw UnsupportedOperationException("Actual tag/hardware does not support ${child.technology}")
        val method = api.substringAfterLast('.')
        when (method) {
            "getMaxTransceiveLength" -> ok(reply, api, JSONObject().put("length", maximum(available)))
            "getHistoricalBytes" -> ok(reply, api, JSONObject().put("histBytes", binary(checkNotNull((available as IsoDep).historicalBytes) { "No Type A historical bytes on this tag" })))
            "getAtqa" -> ok(reply, api, JSONObject().put("atqa", binary((available as NfcA).atqa)))
            "getSak" -> ok(reply, api, JSONObject().put("sak", (available as NfcA).sak.toInt() and 65535))
            "isConnected" -> ok(reply, api, JSONObject().put("isConnected", native?.javaClass == available.javaClass && native?.isConnected == true))
            "setTimeout" -> { val requested = value.getDouble("timeout"); require(requested.isFinite() && requested == kotlin.math.floor(requested) && requested in 100.0..30000.0); timeoutMs = requested.toLong(); native?.let(::configureTimeout); ok(reply, api) }
            "connect" -> {
                synchronized(work) { check(work.isEmpty()) }
                val previous = native; native = available
                io(call, reply, { if (previous !== available) previous?.close(); configureTimeout(available); if (!available.isConnected) available.connect(); Unit }) { ok(reply, api) }
            }
            "close" -> { val current = checkNotNull(native); require(current.javaClass == available.javaClass); io(call, reply, { current.close(); Unit }) { ok(reply, api) } }
            "transceive" -> {
                val current = checkNotNull(native); require(current.javaClass == available.javaClass && current.isConnected)
                val data = bytes(value.get("data")); require(data.isNotEmpty() && data.size <= maximum(current)) { "Transceive exceeds actual native maximum" }
                io(call, reply, { configureTimeout(current); transceive(current, data).also { require(it.size <= 1048576) } }) { ok(reply, api, JSONObject().put("data", binary(it))) }
            }
            else -> error("Unsupported NFC technology method")
        }
    }
    private fun scheduleTag() { if (tagScheduled.compareAndSet(false, true)) worker.execute {
        try {
            while (true) {
                val (accepted, tag) = latestTag.getAndSet(null) ?: break
                if (accepted != generation || closed) continue
                val epoch = tagEpoch.incrementAndGet(); this@AndroidNdefApis.tag = null
                native?.close(); native = null
                if (accepted != generation || closed) continue
                this@AndroidNdefApis.tag = tag
                discovery.set(Discovery(accepted, tag, epoch)); scheduleDiscovery()
            }
        } catch (error: Throwable) { android.util.Log.e("WeappContainer", "NFC discovery failed", error) }
        finally { tagScheduled.set(false); if (latestTag.get() != null && !closed) scheduleTag() }
    } }
    private fun scheduleDiscovery() { if (discoveryScheduled.compareAndSet(false, true)) main.post {
        val value = discovery.getAndSet(null); discoveryScheduled.set(false)
        val owner = reader
        if (value != null && value.generation == generation && owner != null && tag === value.tag && tagEpoch.get() == value.epoch) { children.values.filter { it.parent == owner }.forEach { it.message = null }; emit(owner, "Discovered", JSONObject().put("id", binary(value.tag.id)).put("techs", JSONArray(value.tag.techList.mapNotNull { technologyNames[it.substringAfterLast('.')] }))) }
        if (discovery.get() != null && !closed) scheduleDiscovery()
    } }
    fun stop() {
        if (closed) return
        generation++; tagEpoch.incrementAndGet(); tag = null; consent.reset(); latestTag.set(null); discovery.set(null)
        if (reader != null) { binding?.let { adapter?.disableReaderMode(it) }; reader = null }
        native?.close()
        val pending = synchronized(work) { work.toList() }; pending.forEach { it.reply.reject(HostErrorCode.CANCELLED, "NFC discovery stopped"); it.terminal.requestStop() }; check(pending.all { it.terminal.await(5000) }) { "NFC I/O has not drained" }
        val discoveryDrained = CountDownLatch(1); worker.execute { discoveryDrained.countDown() }
        check(discoveryDrained.await(5, TimeUnit.SECONDS)) { "NDEF discovery has not drained" }; native?.close(); native = null
    }
    fun reset() { stop(); adapters.clear(); children.clear(); subscriptions.clear(); timeoutMs = 5000 }
    fun rebindActivity(activity: Activity?) { if (binding !== activity) { reset(); binding = activity } }
    override fun close() { if (closed) return; reset(); closed = true; worker.shutdown(); check(worker.awaitTermination(5, TimeUnit.SECONDS)) { "NDEF worker has not retired" }; binding = null }
}
