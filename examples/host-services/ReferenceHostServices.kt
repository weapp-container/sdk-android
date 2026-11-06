package com.sonofmagic.weapp.container.sdk.examples

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.sonofmagic.weapp.container.sdk.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Debug-only environment, supplied to native code by the embedding app; never place it in a pack. */
data class DemoEnvironment(val origin: URI, val controlToken: String) {
    init { require(origin.scheme == "http" && origin.host in setOf("127.0.0.1", "localhost") && origin.path in setOf("", "/") && origin.query == null && origin.userInfo == null && origin.fragment == null); require(controlToken.length >= 32) }
}

/** Reuse one domain across controllers; Rust also shares revocation for its hostAppId/tenantId. */
class DemoHostDomain(val environment: DemoEnvironment) : AutoCloseable {
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val tokens = mutableMapOf<String, String>()
    lateinit var configuration: JSONObject; private set
    private fun key(account: JSONObject) = account.getString("principalId") + ":" + account.getString("epoch")
    fun tokenFor(context: JSONObject): String = checkNotNull(tokens[key(context.getJSONObject("account"))]) { "No native credentials for this account epoch" }
    fun selection(context: JSONObject) = JSONObject().put("miniAppId", context.getJSONObject("application").getString("appId")).put("packRoot", context.getJSONObject("application").getString("packRoot"))
        .put("expectedPrincipalId", context.getJSONObject("account").get("principalId")).put("expectedPrincipalEpoch", context.getJSONObject("account").get("epoch"))
    fun context() = JSONObject(configuration.toString()).put("sessionId", "native-demo")
    fun bootstrap(principal: String = "demo-A", epoch: String = "1"): CompletableFuture<JSONObject> = request("/demo/host", environment.controlToken, JSONObject().put("principalId", principal).put("principalEpoch", epoch)).thenApply { response ->
        val account = response.getJSONObject("account"); tokens[key(account)] = response.getString("hostAccessToken")
        configuration = JSONObject().put("application", response.getJSONObject("application")).put("account", account)
        JSONObject(configuration.toString())
    }
    fun switchAccount(entry: WeappContainerSession, principal: String, epoch: String, launches: JSONObject = JSONObject()): CompletableFuture<Any?> = bootstrap(principal, epoch).thenCompose {
        entry.hostServicesCommand("switchAccount", JSONObject().put("account", it.getJSONObject("account")).put("launches", launches))
    }
    internal fun request(path: String, bearer: String, value: JSONObject): CompletableFuture<JSONObject> {
        val result = CompletableFuture<JSONObject>(); val bytes = value.toString().toByteArray()
        io.execute {
            var connection: HttpURLConnection? = null
            val response = runCatching {
                connection = environment.origin.resolve(path).toURL().openConnection() as HttpURLConnection
                val http = connection!!; http.instanceFollowRedirects = false; http.connectTimeout = 10000; http.readTimeout = 15000
                http.requestMethod = "POST"; http.doOutput = true; http.setRequestProperty("Content-Type", "application/json"); http.setRequestProperty("Authorization", "Bearer $bearer")
                http.setFixedLengthStreamingMode(bytes.size); http.outputStream.use { it.write(bytes) }
                val status = http.responseCode; val stream = if (status in 200..299) http.inputStream else http.errorStream
                val data = requireNotNull(stream).use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 262144); output.write(buffer, 0, count) }; output.toByteArray()
                }
                val parsed = JSONObject(String(data, Charsets.UTF_8)); check(status in 200..299) { parsed.optString("message", "Demo request failed") }; parsed
            }
            connection?.disconnect(); main.post { response.fold(result::complete, result::completeExceptionally) }
        }; return result
    }
    override fun close() { tokens.clear(); io.shutdownNow() }
}

/** Install before load. AndroidManifest must allow cleartext only in this loopback debug target. */
class ReferenceHostServices(private val activity: Activity, private val domain: DemoHostDomain) : AutoCloseable {
    private val backend = HostServicesClient(domain.environment.origin, { domain.tokenFor(requireNotNull(it.context)) }, allowLoopbackHttp = true)
    private val identity = AndroidHostIdentity(activity, backend)
    private val payments = AndroidAppPayments(activity, backend, channels = listOf(object : AndroidNativeAppPayChannel {
        override val channel = "reference"
        override val isAvailable = true
        override fun pay(launch: JSONObject, call: HostCall, complete: (Result<Unit>) -> Unit) {
            require(launch.getString("kind") == "reference")
            val settled = java.util.concurrent.atomic.AtomicBoolean()
            fun finish(result: Result<Unit>) { if (settled.compareAndSet(false, true)) complete(result) }
            val dialog = AlertDialog.Builder(activity).setTitle("本地 reference 支付")
                .setMessage("这会确认开发测试订单，不会调用钱包或转移真实资金。")
                .setPositiveButton("确认测试订单") { _, _ ->
                    val context = requireNotNull(call.context)
                    domain.request("/demo/reference-confirm", domain.environment.controlToken, JSONObject().put("orderId", launch.getString("orderId"))
                        .put("hostAccessToken", domain.tokenFor(context)).put("selection", domain.selection(context)))
                        .whenComplete { _, error -> finish(if (error == null) Result.success(Unit) else Result.failure(error)) }
                }.setNegativeButton("取消") { _, _ -> finish(Result.failure(java.util.concurrent.CancellationException())) }.create()
            call.onCancel = { dialog.dismiss(); finish(Result.failure(java.util.concurrent.CancellationException())) }; dialog.show()
        }
    }))
    fun install(session: WeappContainerSession): CompletableFuture<Void> {
        session.accountCleanupHandler = { operation -> identity.prepareAccountState(session, operation, domain.context()).thenApply { Unit } }
        return identity.prepareConfiguration(domain.configuration).thenCompose { session.configureHostServices(it) }
            .thenCompose { CompletableFuture.allOf(identity.install(session), payments.install(session), session.registerHostMethod("demo.identity.refresh") { call, reply ->
                if (!call.isCancelled) refresh(session).whenComplete { value, error ->
                    if (error == null) reply.resolve(value) else reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Identity refresh failed")
                }
            }) }
    }
    fun installAndLoad(session: WeappContainerSession, pack: PackSource, options: LoadOptions = LoadOptions()): CompletableFuture<Unit> =
        install(session).thenCompose { session.load(pack, options) }
    /** Call after /demo/login redemption and on foreground/token/consent refresh. */
    fun refresh(session: WeappContainerSession) = identity.refreshState(session, domain.context())
    fun logoutApp(session: WeappContainerSession): CompletableFuture<JSONObject> {
        val context = domain.context()
        return domain.request("/v1/host/app-logout", domain.tokenFor(context), domain.selection(context)).thenCompose { refresh(session) }
    }
    override fun close() { payments.close(); backend.close() }
}
