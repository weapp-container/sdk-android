package com.sonofmagic.weapp.container.sdk

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Finite source-wrapper regression against actual JNI, QuickJS and HTTP. */
class HostIdentityRefreshProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private lateinit var origin: URI
    private lateinit var controlToken: String
    private lateinit var session: WeappContainerSession
    private lateinit var backend: HostServicesClient
    private lateinit var identity: AndroidHostIdentity
    private lateinit var configuration: JSONObject
    private val tokens = mutableMapOf<String, String>()
    private val bobVerification = CompletableFuture<JSONObject>()
    private var pendingRefresh: CompletableFuture<JSONObject>? = null
    private var oldContext: JSONObject? = null
    private var oldSessionId: String? = null
    private var switching = false
    private var verifying = false
    private var finished = false
    private val report = JSONObject().put("classification", "finite-source-wrapper-real-android-jni-quickjs-http-regression")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        origin = URI(requireNotNull(intent.getStringExtra("origin")))
        require(origin.scheme == "http" && origin.host == "127.0.0.1" && origin.path in listOf("", "/"))
        controlToken = requireNotNull(intent.getStringExtra("control"))
        session = WeappContainerSession(this, "identity-refresh-probe", gpuRequired = true)
        val view = WeappContainerView(this); setContentView(view); view.bind(session)
        backend = HostServicesClient(origin, { call -> checkNotNull(tokens[key(requireNotNull(call.context).getJSONObject("account"))]) }, true)
        identity = AndroidHostIdentity(this, backend)
        session.accountCleanupHandler = { operation -> identity.prepareAccountState(session, operation, configuration).thenApply { Unit } }
        session.eventListener = ContainerEventListener { event ->
            try {
                when (event.optString("type")) {
                    "error" -> fail(IllegalStateException(event.toString()))
                    "accountSwitch" -> if (switching && event.getJSONObject("receipt").optString("phase") == "complete") verifyLateRefresh()
                    "message" -> when (val message = event.optJSONObject("message")) {
                        null -> Unit
                        else -> when (message.optString("kind")) {
                            "failed" -> fail(IllegalStateException(message.toString()))
                            "scenarios-complete" -> { report.put("scenarios", message.getJSONArray("results")); beginLateRefresh() }
                            "passed" -> { report.put("late", message.getJSONObject("late")).put("finalGuestState", message.getJSONObject("state")); finish(null) }
                        }
                    }
                }
            } catch (error: Throwable) { fail(error) }
        }
        val pack = copyAsset("identity-pack", File(filesDir, "identity-pack"))
        bootstrap("alice", "9007199254740993")
            .thenCompose { identity.prepareConfiguration(configuration) }
            .thenCompose { session.configureHostServices(it) }
            .thenCompose { CompletableFuture.allOf(identity.install(session), registerMethods()) }
            .thenCompose { session.load(PackSource.Directory(pack)) }
            .thenCompose { session.capabilities() }
            .whenComplete { capabilities, error ->
                if (error != null) fail(error)
                else { report.put("capabilities", capabilities); if (!HostIdentitySnapshot.supportsOwnerContext(capabilities)) fail(IllegalStateException("Native snapshotOwnerContext 1.0 is required")) }
            }
        main.postDelayed({ if (!finished) fail(IllegalStateException("Identity refresh regression timed out")) }, 90000)
    }

    private fun key(account: JSONObject) = account.getString("principalId") + ":" + account.getString("epoch")
    private fun bootstrap(principal: String, epoch: String) = request("/fixture/host", JSONObject().put("principalId", principal).put("principalEpoch", epoch)).thenApply { response ->
        val account = response.getJSONObject("account")
        tokens[key(account)] = response.getString("hostAccessToken")
        configuration = JSONObject().put("application", response.getJSONObject("application")).put("account", account)
        configuration
    }
    private fun registerMethods(): CompletableFuture<Void> {
        fun method(name: String, action: (HostCall) -> CompletableFuture<JSONObject>) = session.registerHostMethod("probe.$name") { call, reply ->
            try { action(call).whenComplete { value, error ->
                if (error == null) reply.resolve(value) else reply.reject(HostErrorCode.INVALID_REQUEST, cause(error).message ?: "Probe command failed")
            } } catch (error: Throwable) { reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Probe command failed") }
        }
        return CompletableFuture.allOf(
            method("exchange") { request("/business/exchange", it.payload as JSONObject, false) },
            method("businessLogout") { request("/business/logout", it.payload as JSONObject, false) },
            method("mode") { request("/fixture/mode", it.payload as JSONObject) },
            method("stats") { request("/fixture/stats", JSONObject()) },
            method("refresh") { identity.refreshState(session, configuration) },
            method("bobVerified") { bobVerification },
        )
    }

    private fun beginLateRefresh() {
        check(pendingRefresh == null)
        session.state().thenCompose { state ->
            oldSessionId = state.getString("sessionId")
            oldContext = JSONObject(state.getJSONObject("hostServicesState").toString()).put("sessionId", oldSessionId)
            request("/fixture/hold", JSONObject())
        }.thenCompose {
            pendingRefresh = identity.refreshState(session, configuration)
            awaitHeldSnapshot(System.currentTimeMillis() + 10000)
        }.thenCompose { bootstrap("bob", "9007199254740994") }
            .thenCompose {
                switching = true
                session.hostServicesCommand("switchAccount", JSONObject().put("account", configuration.getJSONObject("account"))
                    .put("launches", JSONObject().put("identity-refresh-probe", JSONObject().put("options", JSONObject().put("query", JSONObject().put("phase", "bob"))))))
            }.exceptionally { fail(it); null }
    }
    private fun awaitHeldSnapshot(deadline: Long): CompletableFuture<JSONObject> = request("/fixture/stats", JSONObject()).thenCompose { stats ->
        if (stats.getBoolean("held")) CompletableFuture.completedFuture(stats)
        else {
            check(System.currentTimeMillis() < deadline) { "Actual reference snapshot was not held" }
            val retry = CompletableFuture<JSONObject>()
            main.postDelayed({ awaitHeldSnapshot(deadline).whenComplete { value, error -> if (error == null) retry.complete(value) else retry.completeExceptionally(error) } }, 20)
            retry
        }
    }
    private fun verifyLateRefresh() {
        if (verifying) return
        verifying = true
        session.state().thenCompose { state ->
            check(state.getString("sessionId") != oldSessionId) { "Account replacement reused the old native incarnation" }
            check(state.getJSONObject("hostServicesState").getJSONObject("account").getString("epoch") == "9007199254740994")
            request("/fixture/release", JSONObject())
        }.thenCompose {
            checkNotNull(pendingRefresh).handle { _, error ->
                check(error != null && (cause(error) as? ContainerException)?.code == "ERR_CANCELLED") { "Late automatic refresh did not reject its old native owner: $error" }
                JSONObject().put("refreshError", (cause(error) as ContainerException).code)
            }
        }.thenCompose { result ->
            val attempted = JSONObject().put("hostLoggedIn", true).put("appLoggedIn", true).put("profileAuthorized", false).put("phoneAuthorized", false)
            session.hostServicesCommand("updateHostServiceSnapshots", JSONObject().put("context", oldContext)
                .put("values", JSONObject().put("weappContainer.identity.getState", attempted))).handle { _, error ->
                check(error != null && (cause(error) as? ContainerException)?.code == "ERR_CANCELLED") { "Explicit stale native snapshot was accepted: $error" }
                result.put("manualUpdateError", (cause(error) as ContainerException).code)
            }
        }.whenComplete { value, error ->
            if (error == null) bobVerification.complete(value)
            else { bobVerification.completeExceptionally(error); fail(error) }
        }
    }

    private fun request(path: String, body: JSONObject, control: Boolean = true): CompletableFuture<JSONObject> {
        val result = CompletableFuture<JSONObject>(); val bytes = body.toString().toByteArray(Charsets.UTF_8)
        io.execute {
            var connection: HttpURLConnection? = null
            val outcome = runCatching {
                val http = origin.resolve(path).toURL().openConnection() as HttpURLConnection; connection = http
                http.instanceFollowRedirects = false; http.connectTimeout = 5000; http.readTimeout = 10000
                http.requestMethod = "POST"; http.doOutput = true; http.setRequestProperty("Content-Type", "application/json")
                if (control) http.setRequestProperty("Authorization", "Bearer $controlToken")
                http.setFixedLengthStreamingMode(bytes.size); http.outputStream.use { it.write(bytes) }
                check(http.responseCode in 200..299) { "Native fixture request rejected: $path ${http.responseCode}" }
                http.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; check(output.size() + count <= 262144); output.write(buffer, 0, count) }
                    JSONObject(output.toString("UTF-8"))
                }
            }
            connection?.disconnect(); main.post { outcome.fold(result::complete, result::completeExceptionally) }
        }
        return result
    }
    private fun cause(error: Throwable): Throwable {
        var current = error
        while ((current is java.util.concurrent.CompletionException || current is java.util.concurrent.ExecutionException) && current.cause != null) current = current.cause!!
        return current
    }
    private fun fail(error: Throwable) { if (!finished) finish(cause(error)) }
    private fun finish(error: Throwable?) {
        if (finished) return
        finished = true
        session.closeAsync().whenComplete { _, closeError ->
            backend.close(); io.shutdownNow(); tokens.clear()
            report.put("status", if (error == null && closeError == null) "passed" else "failed")
                .put("nativeOwnerClosed", closeError == null)
                .put("limitations", JSONArray(listOf("Current source wrapper with an explicitly selected historical runtime; not a fresh SDK archive or release acceptance", "Authored finite pack, not compiler output or compiler-delivery acceptance", "One arm64 Android emulator, QuickJS, actual reference HTTP/SQLite; no production identity, hardware, wallet or merchant claim")))
            error?.let { report.put("error", it.stackTraceToString()) }; closeError?.let { report.put("closeError", it.stackTraceToString()) }
            File(filesDir, "identity-refresh-report.json").writeText(report.toString(2))
        }
    }
    private fun copyAsset(asset: String, output: File): File {
        val children = assets.list(asset) ?: emptyArray()
        if (children.isEmpty()) { output.parentFile!!.mkdirs(); assets.open(asset).use { source -> output.outputStream().use { source.copyTo(it) } } }
        else { output.mkdirs(); for (child in children) copyAsset("$asset/$child", File(output, child)) }
        return output
    }
}
