package com.sonofmagic.weapp.container.sdk

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Dedicated HTTP and WebSocket clients, with exact native origin policy and no host cookie jar. */
class AndroidNetworkApis(
    private val files: AndroidManagedFiles,
    allowedOrigins: Set<String> = emptySet(),
    private val allowLoopbackForDevelopment: Boolean = false,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(4)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val origins = allowedOrigins.toSet()
    private var session: WeappContainerSession? = null
    @Volatile private var closed = false
    @Volatile private var closing = false
    @Volatile private var resetting = false
    @Volatile private var generation = 0L
    private class Task(val owner: HostCall, val request: Call) { val drained = CountDownLatch(1); @Volatile var retired = false }
    private class Socket(val owner: HostCall) { var native: WebSocket? = null; val drained = CountDownLatch(1); @Volatile var retired = false; var open = false }
    private class Subscription(val call: HostCall, val objectId: String, val event: String, var sequence: Long = 0)
    private val tasks = mutableMapOf<String, Task>()
    private val sockets = java.util.concurrent.ConcurrentHashMap<String, Socket>()
    private val subscriptions = mutableMapOf<String, Subscription>()
    val supportedApis = setOf("wx.request", "wx.downloadFile", "wx.uploadFile", "wx.connectSocket",
        "object:RequestTask.abort", "object:RequestTask.onHeadersReceived", "object:RequestTask.offHeadersReceived", "object:RequestTask.onChunkReceived", "object:RequestTask.offChunkReceived",
        "object:DownloadTask.abort", "object:DownloadTask.onHeadersReceived", "object:DownloadTask.offHeadersReceived", "object:DownloadTask.onProgressUpdate", "object:DownloadTask.offProgressUpdate",
        "object:UploadTask.abort", "object:UploadTask.onHeadersReceived", "object:UploadTask.offHeadersReceived", "object:UploadTask.onProgressUpdate", "object:UploadTask.offProgressUpdate",
        "object:SocketTask.send", "object:SocketTask.close", "object:SocketTask.onOpen", "object:SocketTask.onMessage", "object:SocketTask.onClose", "object:SocketTask.onError")
    init { require(origins.size <= 128); origins.forEach { val uri = target(it); require(origin(uri) == it && uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null) } }
    private fun origin(uri: URI) = "${uri.scheme.lowercase()}://${uri.host.lowercase()}${if (uri.port < 0) "" else ":${uri.port}"}"
    private fun target(raw: String): URI {
        val uri = URI(raw); require(uri.userInfo == null && uri.host != null && uri.fragment == null)
        require(uri.scheme in setOf("https", "wss") || allowLoopbackForDevelopment && uri.scheme in setOf("http", "ws") && uri.host in setOf("127.0.0.1", "localhost", "::1"))
        return uri
    }
    private fun permitted(raw: String, socket: Boolean): URI { val uri = target(raw); if ((uri.scheme in setOf("wss", "ws")) != socket || origin(uri) !in origins) throw SecurityException("Native network origin is not allowed"); return uri }
    fun install(target: WeappContainerSession): java.util.concurrent.CompletableFuture<Void> {
        check(session == null); session = target
        return java.util.concurrent.CompletableFuture.allOf(target.registerHostResourceCleanup("network-${UUID.randomUUID()}") { reset() },
            *supportedApis.map { api -> target.registerApiHandler(api) { call, reply ->
                try { check(!closed && !closing && !resetting && !call.isCancelled); invoke(api, call, reply) } catch (error: Throwable) { fail(api, reply, error) }
            } }.toTypedArray())
    }
    private fun ok(api: String) = JSONObject().put("errMsg", "${api.removePrefix("wx.")}:ok")
    private fun fail(api: String, reply: HostReply, error: Throwable) = reply.reject(when (error) { is java.util.concurrent.CancellationException -> HostErrorCode.CANCELLED; is SecurityException -> HostErrorCode.PERMISSION_DENIED; is java.net.SocketTimeoutException -> HostErrorCode.TIMEOUT; else -> HostErrorCode.INVALID_REQUEST },
        error.message ?: "Native network operation failed", false, JSONObject().put("errMsg", "${api.removePrefix("wx.")}:fail ${error.message}").put("errCode", -1).put("nativeException", error.javaClass.name))
    private fun sameOwner(left: HostCall, right: HostCall): Boolean {
        val a = left.context ?: return false; val b = right.context ?: return false
        return a.getString("sessionId") == b.getString("sessionId") && listOf("application" to listOf("hostAppId", "tenantId", "publisherId", "appId", "environment", "version", "packRoot"), "account" to listOf("principalId", "epoch")).all { (group, names) -> names.all { a.getJSONObject(group).get(it).toString() == b.getJSONObject(group).get(it).toString() } }
    }
    private fun bytes(value: Any?): ByteArray = when (value) {
        null, JSONObject.NULL -> ByteArray(0)
        is String -> value.toByteArray(Charsets.UTF_8)
        is JSONObject -> if (value.has("__weappBinary")) {
            val marker = value.getJSONObject("__weappBinary"); require(marker.getString("type") == "ArrayBuffer")
            val array = marker.getJSONArray("bytes"); require(array.length() <= 1048576)
            ByteArray(array.length()) { index -> val byte = array.getDouble(index); require(byte.isFinite() && byte == kotlin.math.floor(byte) && byte in 0.0..255.0); byte.toInt().toByte() }
        } else value.toString().toByteArray(Charsets.UTF_8)
        else -> error("Text, JSON object or ArrayBuffer required")
    }
    private fun binary(data: ByteArray) = JSONObject().put("__weappBinary", JSONObject().put("type", "ArrayBuffer").put("bytes", JSONArray().apply { data.forEach { put(it.toInt() and 255) } }))
    private data class Emission(val objectId: String, val event: String, val owner: HostCall, val value: JSONObject, val bytes: Int, val generation: Long, val delivered: CompletableFuture<Void>)
    private val ingressLock = Any()
    private val ingress = java.util.ArrayDeque<Emission>()
    private var ingressBytes = 0
    private var ingressScheduled = false
    private fun cancelProducer(id: String, owner: HostCall) {
        synchronized(tasks) { tasks[id]?.takeIf { it.owner.id == owner.id && sameOwner(it.owner, owner) }?.let { it.retired = true; it.request.cancel() } }
        sockets[id]?.takeIf { it.owner.id == owner.id && sameOwner(it.owner, owner) }?.let { it.retired = true; it.native?.cancel() }
    }
    private fun emit(id: String, event: String, owner: HostCall, value: JSONObject): CompletableFuture<Void> {
        val delivered = CompletableFuture<Void>()
        if (owner.isCancelled || closed || closing || resetting) { delivered.completeExceptionally(java.util.concurrent.CancellationException("Network event owner retired")); return delivered }
        val size = value.toString().toByteArray(Charsets.UTF_8).size
        val accepted = synchronized(ingressLock) {
            if (ingress.size >= 64 || size > 8 * 1048576 - ingressBytes) false
            else { ingress.addLast(Emission(id, event, owner, value, size, generation, delivered)); ingressBytes += size
                if (!ingressScheduled) { ingressScheduled = true; main.post { drainIngress() } }; true }
        }
        if (!accepted) { cancelProducer(id, owner); delivered.completeExceptionally(IllegalStateException("Network event queue limit")) }
        return delivered
    }
    private fun drainIngress() {
        val emission = synchronized(ingressLock) {
            if (ingress.isEmpty()) { ingressScheduled = false; null }
            else ingress.removeFirst().also { ingressBytes -= it.bytes }
        } ?: return
        if (emission.generation != generation || emission.owner.isCancelled || closed || closing || resetting) { emission.delivered.completeExceptionally(java.util.concurrent.CancellationException("Network event owner retired")); main.post { drainIngress() }; return }
        val recipients = subscriptions.toMap().filterValues { it.objectId == emission.objectId && it.event == emission.event && !it.call.isCancelled && sameOwner(it.call, emission.owner) }
        var delivery = java.util.concurrent.CompletableFuture.completedFuture<Any?>(null)
        for ((id, subscription) in recipients) delivery = delivery.thenCompose {
            if (emission.generation != generation || closed || closing || resetting || subscription.call.isCancelled) java.util.concurrent.CompletableFuture.completedFuture(null)
            else {
                val context = checkNotNull(subscription.call.context)
                session!!.emitHostApiEvent(JSONObject().put("sessionId", context.getString("sessionId")).put("accountEpoch", context.getJSONObject("account").get("epoch"))
                    .put("subscriptionId", id).put("sequence", (++subscription.sequence).toString()).put("event", emission.event).put("objectId", emission.objectId).put("value", emission.value))
            }
        }
        delivery.whenComplete { _, error ->
            if (error != null) { cancelProducer(emission.objectId, emission.owner); emission.delivered.completeExceptionally(error) }
            else emission.delivered.complete(null)
            main.post { drainIngress() }
        }
    }
    private fun headers(value: JSONObject, request: Request.Builder) {
        val headers = value.optJSONObject("header") ?: return
        require(headers.length() <= 64)
        for (key in headers.keys()) { require(key.lowercase() !in setOf("host", "content-length", "connection", "upgrade", "sec-websocket-key", "sec-websocket-version")); request.header(key, headers.getString(key)) }
    }
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        val value = call.payload as? JSONObject ?: JSONObject(); val id = value.getString("objectId")
        if (api.startsWith("object:")) {
            if (api.contains(".on") || api.contains(".off")) {
                val subscriptionId = value.getString("subscriptionId")
                if (api.contains(".off") || value.optString("action") == "unsubscribe") subscriptions.remove(subscriptionId)
                else { require(subscriptions.size < 256); subscriptions[subscriptionId] = Subscription(call, id, api.substringAfterLast('.').removePrefix("on")); reply.retainUntilCancelled(); call.onCancel = { subscriptions.remove(subscriptionId) } }
            } else if (api.endsWith(".abort")) {
                val task = synchronized(tasks) { tasks[id] }; require(task == null || sameOwner(task.owner, call)); task?.request?.cancel()
            } else {
                val socket = checkNotNull(sockets[id]) { "Socket owner retired" }; require(sameOwner(socket.owner, call) && !socket.retired && !socket.owner.isCancelled)
                if (api.endsWith(".send")) { val data = value.get("data"); val bytes = bytes(data); require(socket.open && bytes.size <= 1048576 && (socket.native?.queueSize() ?: 1048577) + bytes.size <= 1048576); check(if (data is String) socket.native!!.send(data) else socket.native!!.send(bytes.toByteString())) }
                else { val code = value.optInt("code", 1000); val reason = value.optString("reason", ""); require(code == 1000 || code in 3000..4999); require(reason.toByteArray().size <= 123); check(socket.native!!.close(code, reason)) }
            }
            reply.resolve(ok(api)); return
        }
        if (api == "wx.connectSocket") { connect(id, value, call, reply); return }
        val acceptedGeneration = generation
        files.prepareScope(requireNotNull(call.context)).whenComplete { _, error ->
            if (call.isCancelled) return@whenComplete
            if (closed || closing || resetting || generation != acceptedGeneration) { reply.reject(HostErrorCode.CANCELLED, "Network preparation owner retired"); return@whenComplete }
            if (error != null) { fail(api, reply, error); return@whenComplete }
            try { request(api, id, value, call, reply, acceptedGeneration) } catch (failure: Throwable) { fail(api, reply, failure) }
        }
    }
    private fun request(api: String, id: String, value: JSONObject, call: HostCall, reply: HostReply, acceptedGeneration: Long) {
        check(!closed && !closing && !resetting && generation == acceptedGeneration && !call.isCancelled)
        require(synchronized(tasks) { tasks.size } < 64)
        val uri = permitted(value.getString("url"), false); val builder = Request.Builder().url(uri.toString()); headers(value, builder)
        val method = (if (api == "wx.uploadFile") "POST" else value.optString("method", "GET")).uppercase()
        require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
        var body: RequestBody? = null
        if (api == "wx.uploadFile") {
            val name = value.getString("name"); require(name.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val data = files.readForNative(value.getString("filePath"), requireNotNull(call.context))
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            val fields = value.optJSONObject("formData") ?: JSONObject(); require(fields.toString().toByteArray().size <= 65536)
            for (key in fields.keys()) { require(key.matches(Regex("[A-Za-z0-9_-]{1,128}"))); multipart.addFormDataPart(key, fields.get(key).toString()) }
            body = multipart.addFormDataPart(name, "upload.bin", data.toRequestBody("application/octet-stream".toMediaTypeOrNull())).build()
        } else if (method == "GET" || method == "HEAD") {
            val query = value.optJSONObject("data")
            if (query != null) { val url = builder.build().url.newBuilder(); for (key in query.keys()) url.addQueryParameter(key, query.get(key).toString()); builder.url(url.build()) }
            else require(!value.has("data") || value.isNull("data") || value.optString("data").isEmpty()) { "GET/HEAD data must be a query object" }
        } else { val data = bytes(value.opt("data")); require(data.size <= 1048576); val contentType = (value.optJSONObject("header")?.let { headers -> headers.keys().asSequence().firstOrNull { it.equals("content-type", true) }?.let { headers.getString(it) } } ?: "application/json").toMediaTypeOrNull(); body = data.toRequestBody(contentType) }
        if (body != null && api == "wx.uploadFile") {
            val original = body; body = object : RequestBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun writeTo(sink: BufferedSink) { original.writeTo(sink); emit(id, "ProgressUpdate", call, JSONObject().put("progress", 100).put("totalBytesSent", contentLength()).put("totalBytesExpectedToSend", contentLength())) }
            }
        }
        val native = client.newCall(builder.method(method, body).build()); native.timeout().timeout(value.optLong("timeout", 60000).coerceIn(1, 300000), TimeUnit.MILLISECONDS)
        val task = Task(call, native); synchronized(tasks) { check(!closed && !closing && !resetting && generation == acceptedGeneration && !call.isCancelled && !tasks.containsKey(id)); tasks[id] = task }; call.onCancel = { task.retired = true; native.cancel() }
        try { worker.execute {
            var delivered = CompletableFuture.completedFuture<Void>(null)
            val result = runCatching { native.execute().use { response ->
                val header = JSONObject(); for (name in response.headers.names()) header.put(name, response.headers.values(name).joinToString(", "))
                delivered = emit(id, "HeadersReceived", call, JSONObject().put("header", header))
                val limit = if (api == "wx.downloadFile") 8 * 1048576 else 1048576; val output = ByteArrayOutputStream(); val total = response.body?.contentLength() ?: -1
                require(total <= limit); var lastProgress = 0L
                response.body?.byteStream()?.use { input -> val buffer = ByteArray(32768); while (true) {
                    val size = input.read(buffer); if (size < 0) break; check(!task.retired && !call.isCancelled); require(output.size() + size <= limit) { "Native response byte limit" }; output.write(buffer, 0, size)
                    if (api == "wx.request" && value.optBoolean("enableChunked")) delivered = emit(id, "ChunkReceived", call, JSONObject().put("data", binary(buffer.copyOf(size))))
                    val now = android.os.SystemClock.elapsedRealtime(); if (api == "wx.downloadFile" && now - lastProgress >= 100) { lastProgress = now; emit(id, "ProgressUpdate", call, JSONObject().put("totalBytesWritten", output.size()).put("totalBytesExpectedToWrite", total).put("progress", if (total > 0) output.size() * 100L / total else 0)) }
                } }
                check(!task.retired && !call.isCancelled); val data = output.toByteArray(); val result = ok(api).put("statusCode", response.code).put("header", header)
                if (api == "wx.downloadFile") {
                    val path = files.writeFromNative(data, value.optString("filePath").takeIf { it.isNotEmpty() }, "bin", requireNotNull(call.context)); result.put(if (value.has("filePath")) "filePath" else "tempFilePath", path)
                    delivered = emit(id, "ProgressUpdate", call, JSONObject().put("progress", 100).put("totalBytesWritten", data.size).put("totalBytesExpectedToWrite", data.size))
                } else if (value.optString("responseType") == "arraybuffer") result.put("data", binary(data))
                else { val text = String(data, Charsets.UTF_8); val parsed = if (api == "wx.request" && value.optString("dataType") != "text") runCatching { org.json.JSONTokener(text).nextValue() }.getOrNull() ?: text else text; result.put("data", parsed) }; result
            } }
            // Native I/O is already drained; guest completion additionally waits
            // for headers/chunks/progress queued before the final result.
            task.drained.countDown()
            delivered.whenComplete { _, eventError -> main.post {
                synchronized(tasks) { if (tasks[id] === task) tasks.remove(id) }
                if (!call.isCancelled && !task.retired) { if (eventError != null) fail(api, reply, eventError) else result.fold(reply::resolve, { fail(api, reply, it) }) }
            } }
        } } catch (error: Throwable) { native.cancel(); synchronized(tasks) { if (tasks[id] === task) tasks.remove(id) }; task.drained.countDown(); throw error }
    }
    private fun connect(id: String, value: JSONObject, call: HostCall, reply: HostReply) {
        require(sockets.size < 32 && !sockets.containsKey(id)); permitted(value.getString("url"), true)
        val builder = Request.Builder().url(value.getString("url")); headers(value, builder)
        val protocols = value.optJSONArray("protocols") ?: JSONArray(); require(protocols.length() <= 16)
        if (protocols.length() > 0) { val selected = (0 until protocols.length()).map { protocols.getString(it) }; require(selected.all { it.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) }); builder.header("Sec-WebSocket-Protocol", selected.joinToString(", ")) }
        val socket = Socket(call); sockets[id] = socket
        socket.native = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { main.post { if (!socket.retired && !call.isCancelled) { socket.open = true; emit(id, "Open", call, JSONObject().put("header", JSONObject().apply { response.headers.names().forEach { put(it, response.header(it)) } })) } else webSocket.cancel() } }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text.toByteArray().size > 1048576) { webSocket.cancel(); return }; if (!socket.retired && !call.isCancelled) emit(id, "Message", call, JSONObject().put("data", text)) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (bytes.size > 1048576) { webSocket.cancel(); return }; if (!socket.retired && !call.isCancelled) emit(id, "Message", call, JSONObject().put("data", binary(bytes.toByteArray()))) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { socket.drained.countDown(); main.post { if (!socket.retired && !call.isCancelled) emit(id, "Close", call, JSONObject().put("code", code).put("reason", reason)); if (sockets[id] === socket) sockets.remove(id) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { socket.drained.countDown(); main.post { if (!socket.retired && !call.isCancelled) emit(id, "Error", call, JSONObject().put("errMsg", "SocketTask:fail ${t.message}").put("errCode", -1)); if (sockets[id] === socket) sockets.remove(id) } }
        })
        call.onCancel = { socket.retired = true; socket.native?.cancel() }; reply.retainUntilCancelled(); reply.resolve(ok("wx.connectSocket").put("objectId", id))
    }
    private fun reset() {
        resetting = true; generation++
        val abandoned = synchronized(ingressLock) { ingress.toList().also { ingress.clear(); ingressBytes = 0 } }
        abandoned.forEach { it.delivered.completeExceptionally(java.util.concurrent.CancellationException("Network owner reset")) }
        val requests = synchronized(tasks) { tasks.values.toList() }; val connections = sockets.values.toList()
        requests.forEach { it.retired = true; it.request.cancel() }; connections.forEach { it.retired = true; it.native?.cancel() }
        check(requests.all { it.drained.await(5, TimeUnit.SECONDS) } && connections.all { it.drained.await(5, TimeUnit.SECONDS) }) { "Native network cleanup did not drain" }
        synchronized(tasks) { tasks.clear() }; sockets.clear(); subscriptions.clear(); client.connectionPool.evictAll(); resetting = false
    }
    override fun close() { if (closed) return; closing = true; reset(); closed = true; worker.shutdownNow(); client.dispatcher.executorService.shutdown() }
}
