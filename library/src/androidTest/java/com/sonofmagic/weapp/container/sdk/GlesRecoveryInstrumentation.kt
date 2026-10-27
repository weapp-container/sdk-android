package com.sonofmagic.weapp.container.sdk

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Bundle
import android.os.SystemClock
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GlesRecoveryActivity : Activity() {
    lateinit var root: FrameLayout
    override fun onCreate(state: Bundle?) { super.onCreate(state); root = FrameLayout(this); setContentView(root) }
}

/** Faults are scoped to an individual presenter; EGL windows and the native renderer are real. */
class GlesRecoveryInstrumentation : Instrumentation() {
    private data class Owned(val session: WeappContainerSession, val view: WeappContainerView,
        val events: CopyOnWriteArrayList<JSONObject>, val presenter: EglPresenter)
    private val owned = mutableListOf<Owned>()
    private val cases = JSONArray()
    private lateinit var activity: GlesRecoveryActivity
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var failure: Throwable? = null
        val cleanup = JSONArray()
        try {
            activity = startActivitySync(Intent(targetContext, GlesRecoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GlesRecoveryActivity
            val healthy = create("healthy")
            healthy.session.load(PackSource.Asset("portable-pack")).await(); presented(healthy)
            val healthyGeneration = healthy.session.state().await().getLong("generation")

            val probes = CopyOnWriteArrayList<Long>()
            val invalidRemaining = AtomicInteger(1)
            val attaches = AtomicInteger()
            val runtime = object : GlesRuntime {
                override fun attach(handle: Long) { attaches.incrementAndGet(); NativeGlesRuntime.attach(handle) }
                override fun detach(handle: Long, abandon: Boolean) = NativeGlesRuntime.detach(handle, abandon)
                override fun present(handle: Long, width: Int, height: Int) = NativeGlesRuntime.present(handle, width, height)
            }
            val recovered = create("recovered", runtime) {
                probes.add(EGL14.eglGetCurrentContext().nativeHandle)
                if (invalidRemaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) invalidateCurrentContext() else GLES20.glGetString(GLES20.GL_VERSION)
            }
            recovered.session.load(PackSource.Asset("portable-pack")).await(); presented(recovered)
            check(probes.size == 2 && attaches.get() == 1) { "Expected two probes / one native attach: $probes / ${attaches.get()}" }
            // EGL handle addresses may be recycled immediately after destruction.
            check(recovered.session.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface"))
            val generation = recovered.session.state().await().getLong("generation")
            recovered.session.sendMessage(JSONObject().put("op", "store").put("value", "recovery-preserved")).await()
            message(recovered, "stored"); recovered.session.flushStorage().await()
            lateinit var retirement: CompletableFuture<Unit>
            runOnMainSync { retirement = recovered.view.unbindAsync() }; retirement.await()
            invalidRemaining.set(1)
            runOnMainSync { recovered.view.bind(recovered.session) }
            recovered.session.setForeground(true).await()
            until { recovered.session.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface") }
            recovered.session.sendMessage(JSONObject().put("op", "read")).await()
            check(message(recovered, "storedValue").getString("value") == "recovery-preserved")
            check(recovered.session.state().await().getLong("generation") == generation)
            check(probes.size == 4 && attaches.get() == 2) { "Expected four probes / two native attaches: $probes / ${attaches.get()}" }
            check(healthy.session.state().await().getLong("generation") == healthyGeneration)
            healthy.session.sendMessage(JSONObject().put("op", "inspect")).await(); message(healthy, "geometry")
            check(healthy.session.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface"))
            check(healthy.session.snapshot().await().rgba.any { it.toInt() != 0 })
            cases.put(JSONObject().put("case", "one-invalid-probe-recreates-own-context-once").put("recoveries", 2)
                .put("contextHandles", JSONArray(probes)).put("nativeAttaches", attaches.get()).put("generation", generation))
            cases.put(JSONObject().put("case", "same-session-storage-and-sibling-renderer-survive"))
            close(recovered)
            val reopened = create("recovered")
            reopened.session.load(PackSource.Asset("portable-pack")).await()
            reopened.session.sendMessage(JSONObject().put("op", "read")).await()
            check(message(reopened, "storedValue").getString("value") == "recovery-preserved")
            cases.put(JSONObject().put("case", "recovered-session-flush-close-reopen")); close(reopened)

            val nativeLossAttempts = AtomicInteger()
            val nativeLoss = create("native-loss", object : GlesRuntime {
                override fun attach(handle: Long) {
                    if (nativeLossAttempts.incrementAndGet() == 1) {
                        invalidateCurrentContext()
                        throw ContainerException("ERR_TEST_CONTEXT_LOST", "native attach observed no current GLES context")
                    }
                    NativeGlesRuntime.attach(handle)
                }
                override fun detach(handle: Long, abandon: Boolean) = NativeGlesRuntime.detach(handle, abandon)
                override fun present(handle: Long, width: Int, height: Int) = NativeGlesRuntime.present(handle, width, height)
            })
            nativeLoss.session.load(PackSource.Asset("portable-pack")).await(); presented(nativeLoss)
            check(nativeLossAttempts.get() == 2)
            cases.put(JSONObject().put("case", "context-invalidated-during-native-attach-recovers-once").put("recoveries", 1).put("nativeAttaches", nativeLossAttempts.get()))
            close(nativeLoss)

            val persistentProbes = CopyOnWriteArrayList<Long>()
            val persistentAttaches = AtomicInteger()
            val allow = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var queuedLoad: CompletableFuture<Unit>
            val blocked = create("persistent", object : GlesRuntime {
                override fun attach(handle: Long) { persistentAttaches.incrementAndGet(); NativeGlesRuntime.attach(handle) }
                override fun detach(handle: Long, abandon: Boolean) = NativeGlesRuntime.detach(handle, abandon)
                override fun present(handle: Long, width: Int, height: Int) = NativeGlesRuntime.present(handle, width, height)
            }, beforeObservation = { queuedLoad = it.load(PackSource.Asset("portable-pack")) }) {
                persistentProbes.add(EGL14.eglGetCurrentContext().nativeHandle)
                if (allow.get()) GLES20.glGetString(GLES20.GL_VERSION) else invalidateCurrentContext()
            }
            val persistentError = rejected(queuedLoad)
            SystemClock.sleep(100) // A later observation must not silently begin another retry cycle.
            check(persistentError.code == "ERR_RENDER")
            check(persistentProbes.size == 2 && persistentAttaches.get() == 0) { "Persistent fault exceeded bound: $persistentProbes / ${persistentAttaches.get()}" }
            check(blocked.presenter.isRetired)
            check(blocked.events.any { it.optJSONObject("message")?.optString("kind") == "initWindow" }) // Must arrive before the rejected future, without relying on a later pump.
            check(blocked.events.none { it.optString("type") == "presented" })
            val committedGeneration = blocked.session.state().await().getLong("generation")
            blocked.session.sendMessage(JSONObject().put("op", "store").put("value", "pending-gpu")).await()
            message(blocked, "stored")
            allow.set(true)
            runOnMainSync { retirement = blocked.view.unbindAsync() }; retirement.await()
            runOnMainSync { blocked.view.bind(blocked.session) }
            blocked.session.setForeground(true).await(); presented(blocked)
            check(blocked.session.state().await().getLong("generation") == committedGeneration)
            blocked.session.sendMessage(JSONObject().put("op", "read")).await()
            check(message(blocked, "storedValue").getString("value") == "pending-gpu")
            cases.put(JSONObject().put("case", "persistent-invalid-context-stops-after-two-and-drains-events").put("recoveries", 1)
                .put("failedAttemptContexts", JSONArray(persistentProbes.take(2))).put("nativeAttachesBeforeExplicitRebind", 0))
            close(blocked)

            val ordinaryAttaches = AtomicInteger()
            val ordinaryContexts = CopyOnWriteArrayList<Long>()
            val ordinary = create("ordinary", object : GlesRuntime {
                override fun attach(handle: Long) { ordinaryAttaches.incrementAndGet(); throw ContainerException("ERR_TEST_RENDERER", "ordinary-valid-context-renderer-error") }
                override fun detach(handle: Long, abandon: Boolean) = NativeGlesRuntime.detach(handle, abandon)
                override fun present(handle: Long, width: Int, height: Int) = NativeGlesRuntime.present(handle, width, height)
            }) { ordinaryContexts.add(EGL14.eglGetCurrentContext().nativeHandle); GLES20.glGetString(GLES20.GL_VERSION) }
            val ordinaryError = rejected(ordinary.session.load(PackSource.Asset("portable-pack")))
            check(ordinaryError.code == "ERR_TEST_RENDERER" && ordinaryAttaches.get() == 1)
            check(ordinaryContexts.size == 2 && ordinaryContexts.distinct().size == 1 && ordinary.presenter.isOpen)
            check(ordinary.events.any { it.optJSONObject("message")?.optString("kind") == "initWindow" })
            check(ordinary.events.none { it.optString("type") == "presented" })
            check(healthy.session.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface"))
            cases.put(JSONObject().put("case", "ordinary-renderer-error-does-not-retry-or-recreate")
                .put("nativeAttaches", ordinaryAttaches.get()).put("contextHandles", JSONArray(ordinaryContexts)))
        } catch (error: Throwable) { failure = error }
        finally {
            for (item in owned.toList().reversed()) try { close(item) } catch (error: Throwable) { cleanup.put(error.stackTraceToString()) }
            if (::activity.isInitialized) runOnMainSync { activity.finish() }
        }
        val result = JSONObject().put("schemaVersion", 1).put("status", if (failure == null && cleanup.length() == 0) "passed" else "failed")
            .put("cases", cases).put("cleanupErrors", cleanup).put("api", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS[0]).put("injectedRecoveryCount", (0 until cases.length()).sumOf { cases.getJSONObject(it).optInt("recoveries") })
            .put("error", failure?.stackTraceToString() ?: JSONObject.NULL)
        finish(if (result.getString("status") == "passed") Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("sdkReport", result.toString()); putString("stream", result.toString(2)) })
    }
    private fun invalidateCurrentContext(): String? {
        check(EGL14.eglMakeCurrent(EGL14.eglGetCurrentDisplay(), EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
        check(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT)
        return GLES20.glGetString(GLES20.GL_VERSION).also { check(it == null) }
    }
    private fun create(key: String, runtime: GlesRuntime = NativeGlesRuntime,
        beforeObservation: ((WeappContainerSession) -> Unit)? = null,
        version: () -> String? = { GLES20.glGetString(GLES20.GL_VERSION) }): Owned {
        lateinit var session: WeappContainerSession; lateinit var view: WeappContainerView; lateinit var presenter: EglPresenter
        val events = CopyOnWriteArrayList<JSONObject>()
        runOnMainSync {
            session = WeappContainerSession(activity, "gles-recovery-$key", null) { handle -> EglPresenter(handle, runtime, version).also { presenter = it } }
            session.eventListener = ContainerEventListener { events.add(JSONObject(it.toString())) }
            view = WeappContainerView(activity)
            activity.root.addView(view, FrameLayout.LayoutParams(400, 360).apply { topMargin = if (key == "healthy") 30 else 420; leftMargin = 20 })
            beforeObservation?.invoke(session)
            view.bind(session)
        }
        val result = Owned(session, view, events, presenter); owned += result
        until { view.isAvailable && view.width > 0 }
        session.setForeground(true).await() // Wait for stable real View metrics before beginning the load fault.
        return result
    }
    private fun close(item: Owned) {
        item.session.closeAsync().await()
        runOnMainSync { item.view.unbind(); activity.root.removeView(item.view) }
        owned.remove(item)
    }
    private fun presented(item: Owned) { until { item.events.any { it.optString("type") == "presented" && it.optLong("revision") > 0 } } }
    private fun message(item: Owned, kind: String): JSONObject {
        var value: JSONObject? = null
        until { value = item.events.asSequence().filter { it.optString("type") == "message" }.mapNotNull { it.optJSONObject("message") }.firstOrNull { it.optString("kind") == kind }; value != null }
        return checkNotNull(value)
    }
    private fun rejected(future: CompletableFuture<Unit>): ContainerException {
        try { future.await(); error("Expected rejected load") } catch (error: java.util.concurrent.ExecutionException) { return error.cause as ContainerException }
    }
    private fun <T> CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)
    private fun until(predicate: () -> Boolean) { val end = SystemClock.uptimeMillis() + 15000; while (!predicate()) { check(SystemClock.uptimeMillis() < end) { "Timed out waiting for SDK state/event" }; SystemClock.sleep(25) } }
}
