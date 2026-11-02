package com.sonofmagic.weapp.container.sdk

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.net.URI
import com.sonofmagic.weapp.container.sdk.examples.DemoEnvironment
import com.sonofmagic.weapp.container.sdk.examples.DemoHostDomain
import com.sonofmagic.weapp.container.sdk.examples.ReferenceHostServices

/** Authored development probe. It exercises public SDK + actual JS/OS resources, never a merchant. */
class HostServicesProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var session: WeappContainerSession
    private lateinit var files: AndroidManagedFiles
    private lateinit var device: AndroidDeviceApis
    private lateinit var media: AndroidNativeMedia
    private lateinit var network: AndroidNetworkApis
    private lateinit var recorder: AndroidRecorderApis
    private lateinit var containerView: WeappContainerView
    private lateinit var application: JSONObject
    private lateinit var domain: DemoHostDomain
    private lateinit var reference: ReferenceHostServices
    private val events = JSONArray()
    private val checks = JSONArray()
    private var oldRoot: File? = null
    private var oldNative: File? = null
    private var switched = false
    private var filesB = false
    private var photo = false
    private var finishing = false
    private var brightness = -1f
    private val nativeTransportVerification = CompletableFuture<Unit>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        brightness = window.attributes.screenBrightness
        session = WeappContainerSession(this, "host-services-probe", gpuRequired = true)
        files = AndroidManagedFiles(this)
        device = AndroidDeviceApis(this)
        media = AndroidNativeMedia(this, files)
        containerView = WeappContainerView(this); setContentView(containerView); containerView.bind(session)
        val pack = copyPack("probe-pack", File(filesDir, "probe-pack"))
        val root = JSONObject(File(pack, "manifest.json").readText()).getJSONObject("integrity").getString("root")
        val origin = requireNotNull(intent.getStringExtra("demoOrigin"))
        val networkOrigin = requireNotNull(intent.getStringExtra("networkOrigin"))
        network = AndroidNetworkApis(files, setOf(origin, networkOrigin, networkOrigin.replace("http://", "ws://")), true)
        recorder = AndroidRecorderApis(this, files)
        domain = DemoHostDomain(DemoEnvironment(URI(origin), requireNotNull(intent.getStringExtra("demoControl"))))
        reference = ReferenceHostServices(this, domain)
        session.eventListener = ContainerEventListener { event ->
            events.put(event)
            if (event.optString("type") == "message") onMessage(event.optJSONObject("message") ?: JSONObject())
            if (event.optString("type") == "error") fail(IllegalStateException(event.toString()))
        }
        domain.bootstrap().thenCompose { configuration ->
            application = configuration.getJSONObject("application"); check(application.getString("packRoot") == root)
            reference.install(session)
        }.thenCompose {
            files.install(session).thenCompose { CompletableFuture.allOf(device.install(session), media.install(session), network.install(session), recorder.install(session)) }
        }.thenCompose { session.load(PackSource.Directory(pack), LoadOptions(options = launchOptions("A"))) }
            .exceptionally { fail(it); null }
        main.postDelayed({ if (!finishing) fail(IllegalStateException("Native host-services probe timed out")) }, 120000)
    }
    private fun account(principal: String, epoch: String) = JSONObject().put("principalId", principal).put("epoch", epoch)
    private fun launchOptions(phase: String) = JSONObject().put("query", JSONObject().put("phase", phase).put("businessOrigin", domain.environment.origin.toString()).put("networkOrigin", intent.getStringExtra("networkOrigin")))
    private fun onMessage(message: JSONObject) {
        android.util.Log.i("WeappHostProbe", "guest: $message")
        when (message.optString("kind")) {
            "probe-failure" -> fail(IllegalStateException(message.toString()))
            "identity-payment" -> {
                check(message.getString("orderStatus") == "paid" && message.getString("phoneSuffix") == "0000")
                checks.put("real-login-redemption-and-native-identity-refresh")
                checks.put("separate-native-profile-and-phone-consents-via-real-phone-button")
                checks.put("native-reference-payment-waits-for-server-paid-confirmation")
            }
            "phone-ready" -> preparePhoneTap()
            "transport-recording" -> {
                session.state().thenCompose { state ->
                    val context = JSONObject(state.getJSONObject("hostServicesState").toString()).put("sessionId", state.getString("sessionId"))
                    files.prepareScope(context).thenApply {
                        val audio = files.readForNative(message.getString("path"), context)
                        check(audio.size > 128 && audio[0].toInt() and 255 == 255 && audio[1].toInt() and 240 == 240) { "No actual ADTS AAC frame" }
                        checks.put("real-http-managed-upload-download-and-stream-events")
                        checks.put("real-native-websocket-peer-echo-and-close")
                        checks.put("real-mediarecorder-aac-imported-into-managed-files")
                        nativeTransportVerification.complete(Unit)
                    }
                }.exceptionally { nativeTransportVerification.completeExceptionally(it); fail(it); null }
            }
            "files-A" -> if (!switched) {
                switched = true; checks.put("guest-file-write-read-and-traversal-rejection")
                nativeTransportVerification.thenCompose { session.hostServicesCommand("managedFileScope") }.thenCompose { value ->
                    val scope = value as JSONObject; oldRoot = File(scope.getString("root")); oldNative = File(scope.getString("auxiliaryRoot"))
                    check(File(oldRoot, "alpha.txt").readText() == "account-A-sync")
                    // Native metadata lives beside user files and must be deleted by the same account operation.
                    File(oldNative, "probe-native-record.json").writeText("{\"probe\":true}")
                    domain.switchAccount(session, "demo-B", "2", JSONObject().put(application.getString("appId"), JSONObject().put("options", launchOptions("B"))))
                }.exceptionally { fail(it); null }
            }
            "files-B" -> { filesB = true; checks.put("new-guest-file-namespace-rejects-account-A"); checks.put("account-retirement-closes-real-http-and-websocket-peers"); maybeFinish() }
            "photo" -> {
                val path = message.getString("path")
                session.state().thenCompose { state ->
                    val context = JSONObject(state.getJSONObject("hostServicesState").toString()).put("sessionId", state.getString("sessionId"))
                    files.prepareScope(context).thenApply {
                        val image = files.resolve(path, context); check(image.length() > 128)
                        val dimensions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(image.path, dimensions)
                        check(dimensions.outWidth > 0 && dimensions.outHeight > 0)
                        checks.put("real-camera2-frame-imported-as-managed-jpeg")
                        val frame = checkNotNull(containerView.bitmap) { "No actual TextureView GPU frame" }
                        File(filesDir, "host-services-frame.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; frame.recycle()
                        checks.put("actual-gles-texture-frame-captured"); photo = true; maybeFinish()
                    }
                }.exceptionally { fail(it); null }
            }
        }
    }
    private fun preparePhoneTap() {
        session.hostServicesCommand("hostComponents").thenCompose { value ->
            val nodes = value as JSONArray
            val button = (0 until nodes.length()).map { nodes.getJSONObject(it) }.firstOrNull { it.optString("tag") == "button" && it.optBoolean("visible") }
            if (button == null) { main.postDelayed(::preparePhoneTap, 100); CompletableFuture.completedFuture(Unit) }
            else session.state().thenApply { state ->
                val bounds = button.getJSONObject("bounds"); val viewport = state.getJSONObject("observedViewport")
                val location = IntArray(2); containerView.getLocationOnScreen(location)
                val x = location[0] + (bounds.getDouble("x") + bounds.getDouble("width") / 2) * containerView.width / viewport.getDouble("width")
                val y = location[1] + (bounds.getDouble("y") + bounds.getDouble("height") / 2) * containerView.height / viewport.getDouble("height")
                File(filesDir, "phone-tap.json").writeText(JSONObject().put("x", x.toInt()).put("y", y.toInt()).toString()); Unit
            }
        }.exceptionally { fail(it); null }
    }
    private fun maybeFinish() {
        if (!filesB || !photo || finishing) return
        session.hostServicesCommand("queryAccountSwitch").whenComplete { value, error ->
            if (error != null) { fail(error); return@whenComplete }
            if ((value as? JSONObject)?.optString("phase") != "complete") { main.postDelayed(::maybeFinish, 100); return@whenComplete }
            finishing = true
            session.hostServicesCommand("deleteAccountData", JSONObject().put("principalId", "demo-A")).thenCompose {
                check(oldRoot?.exists() == false && oldNative?.exists() == false)
                checks.put("account-delete-removes-user-files-and-native-metadata")
                val attempts = java.util.concurrent.atomic.AtomicInteger()
                session.registerHostResourceCleanup("probe-failed-cleanup-retry") {
                    if (attempts.incrementAndGet() == 1) throw IllegalStateException("Authored one-time cleanup failure")
                }.thenCompose { session.closeAsync() }.handle { _, error ->
                    check(error != null && attempts.get() == 1) { "First native cleanup failure was not reported" }
                    check((0 until events.length()).none { events.getJSONObject(it).optString("type") == "closed" }) { "Core closed before native cleanup succeeded" }
                    Unit
                }.thenCompose { session.closeAsync() }.thenApply {
                    check(attempts.get() == 2)
                    checks.put("failed-native-cleanup-retains-core-until-close-retry-succeeds")
                    Unit
                }
            }.whenComplete { _, closeError ->
                if (closeError != null) { finishReport(closeError); return@whenComplete }
                try {
                    recorder.close(); network.close(); media.close(); files.close(); device.close(); reference.close(); domain.close()
                    check(window.attributes.screenBrightness == brightness)
                    check(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0)
                    checks.put("close-drains-camera-and-restores-window-resources")
                    finishReport(null)
                } catch (failure: Throwable) { finishReport(failure) }
            }
        }
    }
    private fun fail(error: Throwable) {
        if (finishing) return
        finishing = true
        session.closeAsync().whenComplete { _, cleanupError ->
            if (cleanupError != null) error.addSuppressed(cleanupError)
            runCatching { recorder.close() }; runCatching { network.close() }; runCatching { media.close() }; runCatching { device.close() }; runCatching { files.close() }; runCatching { reference.close() }; runCatching { domain.close() }
            finishReport(error)
        }
    }
    private fun finishReport(error: Throwable?) {
        val report = JSONObject().put("status", if (error == null) "passed" else "failed").put("checks", checks).put("events", events)
            .put("scope", "Authored Android emulator development fixture; no physical camera, external BLE peripheral, merchant, parity or release claim")
        if (error != null) report.put("error", android.util.Log.getStackTraceString(error))
        File(filesDir, "host-services-probe.json").writeText(report.toString(2))
        android.util.Log.i("WeappHostProbe", report.toString())
    }
    private fun copyPack(asset: String, output: File): File {
        val names = assets.list(asset) ?: emptyArray()
        if (names.isEmpty()) { output.parentFile!!.mkdirs(); assets.open(asset).use { input -> output.outputStream().use { input.copyTo(it) } } }
        else { output.mkdirs(); for (name in names) copyPack("$asset/$name", File(output, name)) }
        return output
    }
}
