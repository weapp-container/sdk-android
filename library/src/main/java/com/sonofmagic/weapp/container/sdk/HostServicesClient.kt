package com.sonofmagic.weapp.container.sdk

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

/** Native-only transport. The token provider reads the host's existing secure account session. */
class HostServicesClient(
    endpoint: URI,
    private val hostToken: (HostCall) -> String,
    allowLoopbackHttp: Boolean = false,
) : AutoCloseable {
    private val endpoint = endpoint.also {
        require(it.userInfo == null && it.fragment == null && it.query == null && it.path in listOf("", "/"))
        require(it.scheme == "https" || allowLoopbackHttp && it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1")) { "Use HTTPS; loopback HTTP requires explicit development opt-in" }
    }
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false
    fun selection(call: HostCall): JSONObject {
        val application = requireNotNull(call.context).getJSONObject("application")
        val account = call.context.getJSONObject("account")
        return JSONObject().put("miniAppId", application.getString("appId")).put("packRoot", application.getString("packRoot")).put("expectedPrincipalId", account.get("principalId")).put("expectedPrincipalEpoch", account.get("epoch"))
    }
    fun request(call: HostCall, path: String, body: JSONObject, complete: (Result<JSONObject>) -> Unit) {
        require(path.startsWith("/v1/") && !path.contains("..") && !path.contains('?') && !path.contains('#'))
        check(!closed) { "Host services client is closed" }
        val frozen = body.toString(); val token = hostToken(call)
        require(token.isNotBlank() && !token.contains('\n') && !token.contains('\r')) { "Host login required" }
        val connection = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        val previousCancellation = call.onCancel
        call.onCancel = { try { previousCancellation?.invoke() } finally { connection.getAndSet(null)?.let { active.remove(it); it.disconnect() } } }
        executor.execute {
            var ownedConnection: HttpURLConnection? = null
            val result = runCatching {
                check(!call.isCancelled) { "Call cancelled" }
                val http = endpoint.resolve(path).toURL().openConnection() as HttpURLConnection
                ownedConnection = http; connection.set(http); active.add(http)
                check(!closed && !call.isCancelled) { "Call cancelled" }
                http.instanceFollowRedirects = false; http.connectTimeout = 10000; http.readTimeout = 15000
                http.requestMethod = "POST"; http.doOutput = true; http.setRequestProperty("Content-Type", "application/json"); http.setRequestProperty("Authorization", "Bearer $token")
                val bytes = frozen.toByteArray(Charsets.UTF_8); http.setFixedLengthStreamingMode(bytes.size)
                http.outputStream.use { it.write(bytes) }
                val status = http.responseCode
                val stream = if (status in 200..299) http.inputStream else http.errorStream
                val response = stream?.use { input ->
                    val buffer = ByteArray(8192); val output = java.io.ByteArrayOutputStream()
                    while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 262144) { "Backend response exceeds limit" }; output.write(buffer, 0, count) }
                    JSONObject(String(output.toByteArray(), Charsets.UTF_8))
                } ?: JSONObject()
                if (status !in 200..299) throw ContainerException(response.optString("error", "ERR_HOST_SERVICES"), response.optString("message", "Host services request failed"))
                response
            }
            connection.set(null); ownedConnection?.let { active.remove(it); it.disconnect() }
            main.post { if (!call.isCancelled) complete(result) }
        }
    }
    fun launch(call: HostCall, orderId: String, complete: (Result<JSONObject>) -> Unit) {
        require(orderId.matches(Regex("[A-Za-z0-9-]{1,128}")))
        request(call, "/v1/payments/orders/$orderId/launch", selection(call), complete)
    }
    /** Logged-out owners have no host bearer. Signed-in backend failures still propagate. */
    fun identityState(call: HostCall, complete: (Result<JSONObject>) -> Unit) {
        check(!closed && !call.isCancelled) { "Call cancelled" }
        val state = HostIdentitySnapshot.signedOut(requireNotNull(call.context))
        if (state != null) main.post { if (!closed && !call.isCancelled) complete(Result.success(state)) }
        else request(call, "/v1/host/identity-state", selection(call), complete)
    }
    override fun close() { closed = true; for (connection in active.toList()) connection.disconnect(); active.clear(); executor.shutdownNow() }
}

/** Host login is reused silently; profile and phone each require their own native prompt. */
class AndroidHostIdentity(private val activity: Activity, private val backend: HostServicesClient) {
    private val subscriptions = mutableMapOf<String, HostCall>()
    private val sequences = mutableMapOf<String, Long>()
    /** Fetch a real state before configure/load so the synchronous guest snapshot is accurate. */
    fun prepareConfiguration(configuration: JSONObject): java.util.concurrent.CompletableFuture<JSONObject> {
        val copied = JSONObject(configuration.toString())
        val context = JSONObject().put("application", copied.getJSONObject("application")).put("account", copied.getJSONObject("account")).put("sessionId", "native-preflight")
        val call = HostCall("native-preflight-${java.util.UUID.randomUUID()}", "weappContainer.identity.getState", JSONObject(), null, null, context)
        val future = java.util.concurrent.CompletableFuture<JSONObject>()
        try { backend.identityState(call) { result -> result.fold({ state ->
            try {
            copied.put("syncValues", (copied.optJSONObject("syncValues") ?: JSONObject()).put("weappContainer.identity.getState", state))
            val apis = copied.optJSONArray("apis") ?: org.json.JSONArray()
            if ((0 until apis.length()).none { apis.optString(it) == "weappContainer.identity.getState" }) apis.put("weappContainer.identity.getState")
            copied.put("apis", apis); future.complete(copied)
            } catch (error: Throwable) { future.completeExceptionally(error) }
        }, future::completeExceptionally) } } catch (error: Throwable) { future.completeExceptionally(error) }
        return future
    }
    /** Call after backend code redemption, consent revocation, token refresh, or foreground resume. */
    fun refreshState(session: WeappContainerSession, trustedContext: JSONObject): java.util.concurrent.CompletableFuture<JSONObject> {
        val expected = JSONObject(trustedContext.toString())
        return session.state().thenCompose { state ->
            val context = HostIdentitySnapshot.refreshContext(expected, state)
            val call = HostCall("native-refresh-${java.util.UUID.randomUUID()}", "weappContainer.identity.getState", JSONObject(), null, null, context)
            val future = java.util.concurrent.CompletableFuture<JSONObject>()
            try { refresh(session, call) { it.fold(future::complete, future::completeExceptionally) } } catch (error: Throwable) { future.completeExceptionally(error) }
            future
        }
    }
    /** Called by accountCleanupHandler after new host credentials are installed, before cleanup ACK. */
    fun prepareAccountState(session: WeappContainerSession, operationId: String, trustedContext: JSONObject): java.util.concurrent.CompletableFuture<JSONObject> {
        val call = HostCall("native-account-${java.util.UUID.randomUUID()}", "weappContainer.identity.getState", JSONObject(), null, null, JSONObject(trustedContext.toString()))
        val future = java.util.concurrent.CompletableFuture<JSONObject>()
        try { backend.identityState(call) { result -> result.fold({ state ->
            try { session.hostServicesCommand("prepareAccountHostServiceSnapshots", JSONObject().put("operationId", operationId).put("values", JSONObject().put("weappContainer.identity.getState", state))).whenComplete { _, error -> if (error == null) future.complete(state) else future.completeExceptionally(error) } } catch (error: Throwable) { future.completeExceptionally(error) }
        }, future::completeExceptionally) } } catch (error: Throwable) { future.completeExceptionally(error) }; return future
    }
    private fun refresh(session: WeappContainerSession, call: HostCall, complete: (Result<JSONObject>) -> Unit) {
        backend.identityState(call) { result -> result.fold({ state ->
            try { session.hostServicesCommand("updateHostServiceSnapshots", JSONObject().put("context", call.context).put("values", JSONObject().put("weappContainer.identity.getState", state))).whenComplete { _, error ->
                if (error != null) complete(Result.failure(error))
                else try {
                    for ((id, subscription) in subscriptions.toMap()) if (!subscription.isCancelled && sameOwner(subscription, call)) {
                        val next = (sequences[id] ?: 0) + 1; sequences[id] = next
                        val context = requireNotNull(subscription.context)
                        session.emitHostApiEvent(JSONObject().put("sessionId", context.getString("sessionId")).put("accountEpoch", context.getJSONObject("account").get("epoch")).put("subscriptionId", id).put("sequence", next.toString()).put("value", state))
                    }
                    complete(Result.success(state))
                } catch (error: Throwable) { complete(Result.failure(error)) }
            } } catch (error: Throwable) { complete(Result.failure(error)) }
        }, { complete(Result.failure(it)) }) }
    }
    private fun sameOwner(left: HostCall, right: HostCall): Boolean {
        val a = left.context ?: return false; val b = right.context ?: return false
        return listOf("application" to listOf("hostAppId", "tenantId", "publisherId", "appId", "environment", "version", "packRoot"), "account" to listOf("principalId", "epoch")).all { (group, fields) -> fields.all { a.getJSONObject(group).get(it).toString() == b.getJSONObject(group).get(it).toString() } }
    }
    fun install(session: WeappContainerSession) = java.util.concurrent.CompletableFuture.allOf(
        session.registerHostResourceCleanup("identity-${java.util.UUID.randomUUID()}") { subscriptions.clear(); sequences.clear() },
        *arrayOf("wx.login", "wx.checkSession", "wx.getUserInfo", "wx.getUserProfile", "component:button.open-type.getPhoneNumber", "weappContainer.identity.authorizePhone", "weappContainer.identity.logout", "weappContainer.identity.getState", "weappContainer.identity.onChange", "weappContainer.identity.offChange").map { api ->
            session.registerApiHandler(api) { call, reply ->
                fun fail(error: Throwable) {
                    val permission = (error as? ContainerException)?.code in setOf("consent_required", "invalid_host_session", "invalid_app_session", "host_identity_mismatch", "stale_principal")
                    reply.reject(if (permission) HostErrorCode.PERMISSION_DENIED else HostErrorCode.INVALID_REQUEST, error.message ?: "Host identity request failed", false,
                        JSONObject().put("errMsg", "$api:fail ${error.message ?: "host identity request failed"}").put("errCode", if (permission) 2001 else -1))
                }
                fun request(path: String, body: JSONObject, transform: (JSONObject) -> JSONObject) {
                    try { backend.request(call, path, body) { result -> result.fold({ value ->
                        try { refresh(session, call) { refreshed -> refreshed.fold({
                            try { reply.resolve(transform(value).put("errMsg", "$api:ok")) } catch (error: Throwable) { fail(error) }
                        }, ::fail) } } catch (error: Throwable) { fail(error) }
                    }, { error ->
                        if ((error as? ContainerException)?.code == "invalid_app_session") try { refresh(session, call) { fail(error) } } catch (_: Throwable) { fail(error) }
                        else fail(error)
                    }) } } catch (error: Throwable) { fail(error) }
                }
                fun fetchProfile() = request("/v1/host/profile", backend.selection(call)) { JSONObject().put("userInfo", it) }
                when (api) {
                    "wx.login" -> request("/v1/host/login-codes", backend.selection(call)) { it }
                    "wx.checkSession" -> request("/v1/host/check-session", backend.selection(call)) { it }
                    "weappContainer.identity.onChange", "weappContainer.identity.offChange" -> {
                        val id = (call.payload as? JSONObject)?.getString("subscriptionId") ?: throw IllegalArgumentException("Subscription identity required")
                        if (api.endsWith(".offChange")) { subscriptions.remove(id); sequences.remove(id) }
                        else { subscriptions[id] = call; call.onCancel = { subscriptions.remove(id); sequences.remove(id) }; reply.retainUntilCancelled() }
                        reply.resolve(JSONObject().put("errMsg", "$api:ok"))
                    }
                    "weappContainer.identity.getState" -> reply.reject(HostErrorCode.UNSUPPORTED, "Identity state is read synchronously from the prepared trusted snapshot")
                    "component:button.open-type.getPhoneNumber" -> reply.reject(HostErrorCode.UNSUPPORTED, "Phone authorization is dispatched from the bound native button gesture")
                    "wx.getUserInfo" -> fetchProfile()
                    "weappContainer.identity.logout" -> request("/v1/host/app-logout", backend.selection(call)) { it }
                    else -> {
                        val phone = api == "weappContainer.identity.authorizePhone"
                        val kind = if (phone) "phone" else "profile"
                        try { backend.request(call, "/v1/host/authorization-policy", backend.selection(call).put("kind", kind)) { policyResult ->
                            policyResult.fold({ policy -> try {
                                if (call.isCancelled) return@fold
                                check(!activity.isFinishing && !activity.isDestroyed) { "Visible activity required for consent" }
                                val purpose = policy.getString("purpose"); val version = policy.getString("permissionVersion")
                                require(policy.getString("kind") == kind && purpose.isNotBlank() && purpose.length <= 512 && version.isNotBlank() && version.length <= 64) { "Invalid registered authorization policy" }
                                fun record(granted: Boolean) {
                                    val consent = backend.selection(call).put("kind", kind).put("granted", granted).put("purpose", purpose).put("permissionVersion", version)
                                    try { backend.request(call, "/v1/host/consents", consent) { result -> result.fold({
                                        if (granted) { if (phone) request("/v1/host/phone-codes", backend.selection(call)) { it } else fetchProfile() }
                                        else try { refresh(session, call) { refreshed -> refreshed.fold({
                                            reply.reject(HostErrorCode.PERMISSION_DENIED, "User denied consent", false, JSONObject().put("errMsg", "$api:fail auth deny").put("errCode", 2001))
                                        }, ::fail) } } catch (error: Throwable) { fail(error) }
                                    }, ::fail) } } catch (error: Throwable) { fail(error) }
                                }
                                val dialog = AlertDialog.Builder(activity).setTitle(if (phone) "授权手机号" else "授权昵称和头像")
                                    .setMessage((if (phone) "允许此小程序获取宿主账号已验证的手机号？" else "允许此小程序获取宿主账号的昵称和头像？") + "\n\n用途：$purpose")
                                    .setPositiveButton("允许") { _, _ -> record(true) }
                                    .setNegativeButton("拒绝") { _, _ -> record(false) }
                                    .setOnCancelListener { reply.reject(HostErrorCode.CANCELLED, "Consent cancelled", false, JSONObject().put("errMsg", "$api:fail cancel")) }.show()
                                val previousCancellation = call.onCancel
                                call.onCancel = { try { previousCancellation?.invoke() } finally { dialog.dismiss() } }
                                if (call.isCancelled) dialog.dismiss()
                            } catch (error: Throwable) { fail(error) } }, ::fail)
                        } } catch (error: Throwable) { fail(error) }
                    }
                }
            }
        }.toTypedArray()
    )
}
