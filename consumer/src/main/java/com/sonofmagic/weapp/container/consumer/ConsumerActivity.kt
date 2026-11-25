package com.sonofmagic.weapp.container.consumer

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.HorizontalScrollView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sonofmagic.weapp.container.sdk.ContainerEventListener
import com.sonofmagic.weapp.container.sdk.HostMethod
import com.sonofmagic.weapp.container.sdk.PackSource
import com.sonofmagic.weapp.container.sdk.WeappContainerSession
import com.sonofmagic.weapp.container.sdk.WeappContainerView
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A small, runnable host that exposes the integration points from the README.
 * The instrumentation suite drives the same session through its public API.
 */
class ConsumerActivity : Activity() {
    lateinit var container: WeappContainerSession
    lateinit var containerView: WeappContainerView
    lateinit var root: FrameLayout
    lateinit var loaded: CompletableFuture<Unit>
    val events = CopyOnWriteArrayList<JSONObject>()
    var insetsCount = 0
    var echoCalls = 0
    var cancelledCalls = 0
    val actionButtons = mutableMapOf<String, Button>()
    private data class PendingMessage(
        val response: CompletableFuture<JSONObject>,
        val timeout: Runnable,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingMessages = ConcurrentHashMap<String, PendingMessage>()

    private lateinit var status: TextView
    private var foreground = true
    @Volatile var sessionClosed = false

    private fun Int.dp(): Int = (this * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            insetsCount++
            view.onApplyWindowInsets(insets)
        }

        root = FrameLayout(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
        }
        val title = TextView(this).apply {
            text = "Weapp Container Android SDK reference app"
            textSize = 16f
        }
        status = TextView(this).apply {
            text = "Creating session..."
            textSize = 12f
            tag = "sdk-status"
        }
        header.addView(title, LinearLayout.LayoutParams(-1, 30.dp()))
        header.addView(status, LinearLayout.LayoutParams(-1, 28.dp()))
        header.addView(actionBar(listOf("Reload", "Detail", "Back")), LinearLayout.LayoutParams(-1, 44.dp()))
        header.addView(actionBar(listOf("Echo", "Store", "Flush", "Read")), LinearLayout.LayoutParams(-1, 44.dp()))
        header.addView(actionBar(listOf("Pause", "Resume", "Close", "Reopen")), LinearLayout.LayoutParams(-1, 44.dp()))
        val headerHeight = 30 + 28 + 44 * 3 + 16
        root.addView(header, FrameLayout.LayoutParams(-1, headerHeight.dp()))

        containerView = WeappContainerView(this)
        root.addView(containerView, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = headerHeight.dp()
            leftMargin = 16.dp()
            rightMargin = 16.dp()
            bottomMargin = 16.dp()
        })
        setContentView(root)
        newSession()
    }

    private fun actionBar(labels: List<String>): View {
        val scroll = HorizontalScrollView(this)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, command: () -> Unit) {
            val button = Button(this).apply {
                text = label
                setOnClickListener { command() }
                tag = "sdk-action-$label"
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
            }
            actionButtons[label] = button
            actions.addView(button, LinearLayout.LayoutParams(-2, 40.dp()).apply { marginEnd = 6.dp() })
        }
        labels.forEach { label ->
            when (label) {
                "Reload" -> action(label) { runAction("reloading") { container.load(PackSource.Asset("portable-pack")) } }
                "Detail" -> action(label) { runAction("navigating") { container.navigate("pages/detail/index", JSONObject().put("source", "reference")) } }
                "Back" -> action(label) { runAction("going back") { container.back() } }
                "Echo" -> action(label) {
                    runAction("sending message") {
                        sendAndAwait(JSONObject().put("op", "echo").put("value", "hello from Android"), "echo")
                    }
                }
                "Store" -> action(label) {
                    runAction("storing value") {
                        sendAndAwait(JSONObject().put("op", "store").put("value", "android-reference"), "stored")
                    }
                }
                "Flush" -> action(label) { runAction("flushing storage") { container.flushStorage() } }
                "Read" -> action(label) {
                    runAction("reading value") {
                        sendAndAwait(JSONObject().put("op", "read"), "storedValue")
                    }
                }
                "Pause" -> action(label) { foreground = false; runAction("pausing") { container.setForeground(false) } }
                "Resume" -> action(label) { foreground = true; runAction("resuming") { container.setForeground(true) } }
                "Close" -> action(label) {
                    val current = container
                    runAction("closing") {
                        containerView.unbindAsync().thenCompose { current.closeAsync() }.thenApply {
                            sessionClosed = true
                        }
                    }
                }
                "Reopen" -> action(label) {
                    if (sessionClosed) newSession()
                    else updateStatus("Close the current session before reopening")
                }
            }
        }
        scroll.addView(actions)
        return scroll
    }

    private fun sendAndAwait(payload: JSONObject, responseKind: String): CompletableFuture<JSONObject> {
        val response = CompletableFuture<JSONObject>()
        lateinit var pending: PendingMessage
        val timeout = Runnable {
            failMessage(responseKind, pending, TimeoutException("Timed out waiting for $responseKind"))
        }
        pending = PendingMessage(response, timeout)
        if (pendingMessages.putIfAbsent(responseKind, pending) != null) {
            return CompletableFuture.failedFuture(IllegalStateException("Already waiting for $responseKind"))
        }
        mainHandler.postDelayed(timeout, TimeUnit.SECONDS.toMillis(15))
        try {
            container.sendMessage(payload).whenComplete { _, error ->
                if (error != null) failMessage(responseKind, pending, error)
            }
        } catch (error: Throwable) {
            failMessage(responseKind, pending, error)
        }
        return response
    }

    private fun failMessage(kind: String, pending: PendingMessage, error: Throwable) {
        if (pendingMessages.remove(kind, pending)) {
            mainHandler.removeCallbacks(pending.timeout)
            pending.response.completeExceptionally(error)
        }
    }

    private fun runAction(label: String, action: () -> CompletableFuture<*>) {
        updateStatus(label)
        runCatching { action() }
            .onFailure { updateStatus("$label failed: ${it.message}") }
            .onSuccess { future ->
                future.whenComplete { _, error ->
                    runOnUiThread {
                        updateStatus(if (error == null) "$label complete" else "$label failed: ${error.message}")
                    }
                }
            }
    }

    private fun updateStatus(value: String) {
        if (::status.isInitialized) runOnUiThread { status.text = value }
    }

    fun newSession() {
        sessionClosed = false
        container = WeappContainerSession(this, "independent-consumer-smoke")
        container.eventListener = ContainerEventListener { event ->
            val json = JSONObject(event.toString())
            events.add(json)
            json.optJSONObject("message")?.optString("kind")?.let { kind ->
                pendingMessages.remove(kind)?.let { pending ->
                    mainHandler.removeCallbacks(pending.timeout)
                    pending.response.complete(json.optJSONObject("message")!!)
                }
            }
            runOnUiThread {
                val type = json.optString("type", "event")
                val message = json.optJSONObject("message")?.optString("kind")
                updateStatus(if (message.isNullOrBlank()) type else "$type: $message")
            }
        }
        container.registerHostMethod("host.echo", HostMethod { call, reply ->
            echoCalls++
            containerView.postDelayed({ reply.resolve(call.payload) }, 40)
        })
        container.registerHostMethod("host.hang", HostMethod { call, _ ->
            call.onCancel = { cancelledCalls++ }
        })
        container.registerHostMethod("native.echo", HostMethod { call, reply ->
            reply.resolve(JSONObject().put("native", true).put("payload", call.payload))
        })
        containerView.bind(container)
        loaded = container.load(PackSource.Asset("portable-pack"))
        loaded.whenComplete { _, error ->
            runOnUiThread { updateStatus(if (error == null) "Ready" else "Load failed: ${error.message}") }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized && foreground) container.setForeground(true)
    }

    override fun onPause() {
        if (::container.isInitialized) container.setForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        pendingMessages.entries.toList().forEach { (kind, pending) ->
            failMessage(kind, pending, IllegalStateException("Activity destroyed while waiting for $kind"))
        }
        if (::containerView.isInitialized) containerView.unbind()
        if (::container.isInitialized) container.closeAsync()
        super.onDestroy()
    }
}
