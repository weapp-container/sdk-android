package com.sonofmagic.weapp.container.consumer

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.sonofmagic.weapp.container.sdk.*
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SdkInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val checks = mutableListOf<String>()
        var activity: ConsumerActivity? = null
        var failure: Throwable? = null
        try {
            activity = startActivitySync(Intent(targetContext, ConsumerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConsumerActivity
            val a = activity
            a.loaded.await(); until { a.events.any { it.optString("type") == "presented" } }
            until { a.container.state().await().getBoolean("inputEnabled") }
            val ownersBefore = Thread.getAllStackTraces().keys.count { it.name == "WeappContainer" }
            repeat(3) { try { WeappContainerSession(a, " "); error("Blank storage key accepted") } catch (_: IllegalArgumentException) { } }
            check(Thread.getAllStackTraces().keys.count { it.name == "WeappContainer" } == ownersBefore)
            checks += "invalid-key-does-not-create-owner-thread"
            val first = a.container.state().await()
            check(first.getBoolean("presentationRequired"))
            checks += "actual-EGL-swap-acknowledges-current-frame-and-enables-input"
            val init = untilMessage(a, "initWindow").getJSONObject("window")
            check(init.getInt("windowWidth") == first.getJSONObject("viewport").getInt("width"))
            check(init.getInt("windowHeight") == first.getJSONObject("viewport").getInt("height"))
            check(init.getDouble("pixelRatio") > 0 && a.insetsCount > 0)
            val capability = a.container.capabilities().await()
            check(capability.getJSONObject("build").getBoolean("gles")); check(capability.getJSONObject("runtime").getBoolean("gpuSurface"))
            checks += "real-initial-viewport-and-display; untouched-host-insets; direct-GLES"
            sendStatus(0, Bundle().apply { putString("sdkStage", "first-frame") })
            SystemClock.sleep(500)
            var exclusive = false
            runOnMainSync { try { WeappContainerView(a).bind(a.container) } catch (_: IllegalStateException) { exclusive = true } }
            check(exclusive); checks += "single-view-per-session"
            a.container.sendMessage(JSONObject().put("op", "inspect")).await()
            val geometry = untilMessage(a, "geometry")
            pointerGesture(a.containerView, first.getJSONObject("viewport"), listOf(
                Pair(geometry.getJSONObject("tap").getDouble("x"), geometry.getJSONObject("tap").getDouble("y"))))
            val tapCount = untilMessage(a, "tap").getInt("count")
            check(untilMessage(a, "timer").getInt("count") == tapCount)
            checks += "real-MotionEvent-tap-and-JavaScript-timer"
            val scroll = geometry.getJSONObject("scroll")
            val scrollX = scroll.getDouble("left") + scroll.getDouble("width") / 2
            val scrollStart = scroll.getDouble("top") + scroll.getDouble("height") * .8
            val scrollEnd = scroll.getDouble("top") + scroll.getDouble("height") * .2
            pointerGesture(a.containerView, first.getJSONObject("viewport"), (0..10).map { Pair(scrollX, scrollStart + (scrollEnd-scrollStart)*it/10) })
            check(untilMessage(a, "scroll").getDouble("scrollTop") > 0)
            checks += "real-MotionEvent-scroll-view-drag"
            a.container.navigate("pages/detail/index").await()
            check(a.container.state().await().getJSONObject("page").getString("route") == "pages/detail/index")
            a.container.back().await()
            check(a.container.state().await().getJSONObject("page").getString("route") == "pages/home/index")
            checks += "native-navigate-and-back"
            val emoji = "中文 🧊 \u0000 emoji"
            a.container.sendMessage(JSONObject().put("op", "echo").put("value", emoji)).await()
            check(untilMessage(a, "echo").getString("value") == emoji && a.echoCalls == 1)
            checks += "async-business-call-and-UTF8-message"
            a.container.sendMessage(JSONObject().put("op", "hang")).await()
            untilMessage(a, "timeout"); until { a.cancelledCalls == 1 }
            checks += "timeout-cancellation"
            val before = a.container.state().await()
            try { a.container.load(PackSource.Directory(File(a.filesDir, "missing-pack"))).await(); error("bad pack accepted") } catch (_: java.util.concurrent.ExecutionException) { }
            val after = a.container.state().await()
            check(before.getLong("generation") == after.getLong("generation"))
            check(a.container.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface")) { "GPU missing after failed replacement; events=${a.events}" }
            checks += "failed-reload-preserves-and-reattaches-old-runtime"
            a.container.sendMessage(JSONObject().put("op", "store").put("value", "persistent-value")).await()
            untilMessage(a, "stored"); a.container.flushStorage().await()
            a.container.closeAsync().await()
            runOnMainSync { a.containerView.unbind(); a.events.clear(); a.newSession() }
            a.loaded.await(); a.container.sendMessage(JSONObject().put("op", "read")).await()
            check(untilMessage(a, "storedValue").getString("value") == "persistent-value")
            checks += "flush-close-reopen-stable-key"
            val generation = a.container.state().await().getLong("generation")
            runOnMainSync { a.root.removeView(a.containerView) }
            SystemClock.sleep(100)
            runOnMainSync { a.root.addView(a.containerView, FrameLayout.LayoutParams(-1, 440).apply { topMargin=90; leftMargin=40; rightMargin=40 }) }
            until { a.container.state().await().getJSONObject("viewport").getInt("height") < first.getJSONObject("viewport").getInt("height") }
            check(a.container.state().await().getLong("generation") == generation)
            until { a.container.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface") }
            until { a.container.state().await().getBoolean("inputEnabled") }
            checks += "detach-reattach-resize-preserves-generation"
            lateinit var retired: CompletableFuture<Unit>
            runOnMainSync { retired = a.containerView.unbindAsync() }
            retired.await()
            runOnMainSync { a.containerView.bind(a.container) }
            until { a.container.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface") }
            until { a.container.state().await().getBoolean("inputEnabled") }
            check(a.container.state().await().getLong("generation") == generation)
            checks += "explicit-view-retirement-before-rebind"
            ServerSocket(0).use { server ->
                val responder = Thread {
                    server.accept().use { socket ->
                        socket.getInputStream().bufferedReader().readLine()
                        Thread.sleep(180)
                        val body = "{\"value\":\"network-ok\"}"
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                    }
                }.apply { start() }
                a.container.sendMessage(JSONObject().put("op", "request").put("url", "http://127.0.0.1:${server.localPort}/fixture")).await()
                a.events.removeAll { it.optString("type") == "message" && it.optJSONObject("message")?.optString("kind") in listOf("hide", "show") }
                runOnMainSync { callActivityOnPause(a) }
                untilMessage(a, "hide")
                check(untilMessage(a, "network").getJSONObject("value").getString("value") == "network-ok")
                responder.join(3000)
                runOnMainSync { callActivityOnResume(a) }
                untilMessage(a, "show")
            }
            checks += "HTTP-completes-with-independent-hidden-pump-and-Activity-resume"
            val snapshot = a.container.snapshot().await()
            check(snapshot.width > 0 && snapshot.height > 0 && snapshot.rgba.any { it.toInt() != 0 })
            checks += "explicit-snapshot-readback"
            a.container.load(PackSource.Asset("portable-pack")).await()
            check(a.container.state().await().getLong("generation") == generation + 1)
            checks += "successful-reload"
            lateinit var second: WeappContainerSession
            lateinit var secondView: WeappContainerView
            runOnMainSync {
                second = WeappContainerSession(a, "independent-consumer-second")
                secondView = WeappContainerView(a)
                a.root.addView(secondView, FrameLayout.LayoutParams(320, 240).apply { topMargin = 550; leftMargin = 40 })
                secondView.bind(second)
            }
            second.load(PackSource.Asset("portable-pack")).await()
            until { second.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface") }
            a.container.closeAsync().await()
            second.sendMessage(JSONObject().put("op", "store").put("value", "independent")).await()
            check(second.snapshot().await().rgba.isNotEmpty())
            check(second.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface"))
            second.closeAsync().await(); second.closeAsync().await()
            runOnMainSync { secondView.unbind(); a.root.removeView(secondView) }
            checks += "multiple-sessions-share-display-and-close-independently"
            checks += "async-final-close-is-idempotent"
            // Replacing both views/owner threads together exercises the embedding
            // lifecycle used by Flutter hot restart and React Native Fast Refresh.
            repeat(12) { round ->
                val pair = mutableListOf<Pair<WeappContainerSession, WeappContainerView>>()
                try {
                    runOnMainSync { repeat(2) { index ->
                        val session = WeappContainerSession(a, "pair-replacement-$index")
                        val view = WeappContainerView(a)
                        a.root.addView(view, FrameLayout.LayoutParams(360, 320).apply { topMargin = 100 + index * 350; leftMargin = 20 })
                        view.bind(session); pair += session to view
                    } }
                    CompletableFuture.allOf(*pair.map { it.first.load(PackSource.Asset("portable-pack")) }.toTypedArray()).await()
                    for ((session, _) in pair) {
                        check(session.capabilities().await().getJSONObject("runtime").getBoolean("gpuSurface")) { "Pair round $round has no GPU surface" }
                        check(session.snapshot().await().revision > 0)
                        session.sendMessage(JSONObject().put("op", "store").put("value", "round-$round")).await()
                    }
                    CompletableFuture.allOf(*pair.map { it.first.closeAsync() }.toTypedArray()).await()
                } finally {
                    for ((session, _) in pair) session.closeAsync().await()
                    runOnMainSync { for ((_, view) in pair) { view.unbind(); a.root.removeView(view) } }
                }
            }
            checks += "12-round-concurrent-pair-load-close-replacement"
        } catch (error: Throwable) { failure = error } finally {
            activity?.let { a -> runOnMainSync { a.finish() } }
        }
        val report = JSONObject().put("schemaVersion", 1).put("status", if (failure == null) "passed" else "failed")
            .put("checks", org.json.JSONArray(checks)).put("abi", android.os.Build.SUPPORTED_ABIS[0]).put("api", android.os.Build.VERSION.SDK_INT)
            .put("error", failure?.stackTraceToString() ?: JSONObject.NULL)
        val bundle = Bundle().apply { putString("sdkReport", report.toString()); putString("stream", report.toString(2)) }
        finish(if (failure == null) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED, bundle)
    }
    private fun pointerGesture(view: View, viewport: JSONObject, positions: List<Pair<Double, Double>>) {
        val location = IntArray(2)
        var width = 0; var height = 0
        runOnMainSync { view.getLocationOnScreen(location); width=view.width; height=view.height }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, point: Pair<Double, Double>) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                (location[0] + point.first * width / viewport.getDouble("width")).toFloat(),
                (location[1] + point.second * height / viewport.getDouble("height")).toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, positions.first())
        positions.drop(1).forEach { SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, it) }
        SystemClock.sleep(35); send(MotionEvent.ACTION_UP, positions.last())
        waitForIdleSync()
    }
    private fun <T> CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)
    private fun until(predicate: () -> Boolean) { val end = SystemClock.uptimeMillis() + 15000; while (!predicate()) { check(SystemClock.uptimeMillis() < end) { "Timed out waiting for SDK state/event" }; SystemClock.sleep(25) } }
    private fun untilMessage(activity: ConsumerActivity, kind: String): JSONObject {
        var value: JSONObject? = null
        until { value = activity.events.asSequence().filter { it.optString("type") == "message" }.mapNotNull { it.optJSONObject("message") }.firstOrNull { it.optString("kind") == kind }; value != null }
        return checkNotNull(value)
    }
}
