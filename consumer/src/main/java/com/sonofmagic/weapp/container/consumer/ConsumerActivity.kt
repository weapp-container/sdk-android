package com.sonofmagic.weapp.container.consumer

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import com.sonofmagic.weapp.container.sdk.*
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class ConsumerActivity : Activity() {
    lateinit var container: WeappContainerSession
    lateinit var containerView: WeappContainerView
    lateinit var root: FrameLayout
    lateinit var loaded: CompletableFuture<Unit>
    val events = CopyOnWriteArrayList<JSONObject>()
    var insetsCount = 0
    var echoCalls = 0
    var cancelledCalls = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setOnApplyWindowInsetsListener { view, insets -> insetsCount++; view.onApplyWindowInsets(insets) }
        root = FrameLayout(this)
        val heading = TextView(this).apply { text = "Native Android host · independent Maven SDK"; textSize = 17f; setPadding(20, 10, 20, 10) }
        root.addView(heading, FrameLayout.LayoutParams(-1, 70))
        containerView = WeappContainerView(this)
        root.addView(containerView, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 80; leftMargin = 24; rightMargin = 24; bottomMargin = 24 })
        setContentView(root)
        newSession()
    }
    fun newSession() {
        container = WeappContainerSession(this, "independent-consumer-smoke")
        container.eventListener = ContainerEventListener { events.add(JSONObject(it.toString())) }
        container.registerHostMethod("host.echo", HostMethod { call, reply -> echoCalls++; containerView.postDelayed({ reply.resolve(call.payload) }, 40) })
        container.registerHostMethod("host.hang", HostMethod { call, _ -> call.onCancel = { cancelledCalls++ } })
        containerView.bind(container)
        loaded = container.load(PackSource.Asset("portable-pack"))
    }
    override fun onResume() { super.onResume(); if (::container.isInitialized) container.setForeground(true) }
    override fun onPause() { if (::container.isInitialized) container.setForeground(false); super.onPause() }
    override fun onDestroy() { if (::containerView.isInitialized) containerView.unbind(); if (::container.isInitialized) container.closeAsync(); super.onDestroy() }
}
