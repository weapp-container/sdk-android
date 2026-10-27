package com.sonofmagic.weapp.container.sdk

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowInsets
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A normal composited Android View; supports subrectangles, clipping and multiple independent sessions. */
class WeappContainerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, style: Int = 0) :
    TextureView(context, attrs, style), TextureView.SurfaceTextureListener {
    private var session: WeappContainerSession? = null
    private var token: Any? = null
    private var textureWidth = 0
    private var textureHeight = 0
    private var observation: WindowMetrics? = null
    private var lastVisible: Boolean? = null
    private var tap: Tap? = null
    private val retirements = mutableListOf<CompletableFuture<Unit>>()
    private val awaitingObservation = mutableListOf<(WindowMetrics?) -> Unit>()
    private data class Tap(val id: Int, val x: Float, val y: Float, val time: Long, var moved: Boolean = false)
    private val preDraw = ViewTreeObserver.OnPreDrawListener { observe(); true }

    init { surfaceTextureListener = this; isOpaque = false; isFocusable = true; isFocusableInTouchMode = true; isClickable = true }

    fun bind(value: WeappContainerSession) {
        checkMain()
        if (session === value) return
        value.claim(this) // Validate exclusivity before releasing the previous session.
        unbind()
        session = value
        observe()
    }
    fun unbind() {
        checkMain()
        val previous = session ?: return
        token?.let { retire(previous, it) }
        previous.release(this)
        session = null; token = null; observation = null; lastVisible = null; tap = null
        resolveObservations(null)
    }
    /** Unbind now, then complete after all worker-owned surface resources retire. */
    fun unbindAsync(): CompletableFuture<Unit> {
        checkMain()
        unbind()
        val result = CompletableFuture<Unit>()
        CompletableFuture.allOf(*retirements.toTypedArray()).whenComplete { _, error ->
            val complete = Runnable { if (error == null) result.complete(Unit) else result.completeExceptionally(error) }
            if (Looper.myLooper() == Looper.getMainLooper()) complete.run() else Handler(Looper.getMainLooper()).post(complete)
        }
        return result
    }
    internal fun awaitObservation(callback: (WindowMetrics?) -> Unit) {
        if (session == null) { callback(null); return }
        awaitingObservation.add(callback)
        postInvalidateOnAnimation()
    }
    private fun resolveObservations(value: WindowMetrics?) {
        val callbacks = awaitingObservation.toList(); awaitingObservation.clear()
        callbacks.forEach { it(value) }
    }
    internal fun onSessionClosed(value: WeappContainerSession) {
        if (session === value) { session = null; token = null; observation = null; lastVisible = null; tap = null; resolveObservations(null) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(preDraw); post { observe() } }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
        tap = null
        // TextureView calls onSurfaceTextureDestroyed during its own detach.
        // Keep the token until then so it cannot release a worker-owned target.
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); post { observe() } }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); post { observe() } }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { textureWidth = width; textureHeight = height; observe() }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { textureWidth = width; textureHeight = height; observe() }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val owned = token; val current = session
        token = null; observation = null; lastVisible = null; textureWidth = 0; textureHeight = 0; tap = null
        if (owned != null && current != null) retire(current, owned)
        val pending = retirements.filter { !it.isDone }.toTypedArray()
        retirements.clear()
        if (pending.isNotEmpty()) { CompletableFuture.allOf(*pending).whenComplete { _, _ -> texture.release() }; return false }
        return true
    }
    private fun retire(current: WeappContainerSession, owned: Any) {
        retirements.removeAll { it.isDone }
        val completed = CompletableFuture<Unit>(); retirements.add(completed)
        current.detachSurface(owned) { completed.complete(Unit) }
    }
    @Suppress("DEPRECATION")
    private fun observe() {
        val current = session ?: return
        val texture = surfaceTexture ?: return
        if (!isAttachedToWindow || !isAvailable || textureWidth <= 0 || textureHeight <= 0 || width != textureWidth || height != textureHeight) return
        // Read, never replace, the embedding app's WindowInsets listener. Root
        // insets expose the full window even if a parent consumes child insets.
        val insets = rootWindowInsets ?: return
        val display = display ?: return
        val extent = Point(); display.getRealSize(extent)
        val density = resources.displayMetrics.density.toDouble()
        if (density <= 0 || extent.x <= 0 || extent.y <= 0) return
        val position = IntArray(2); rootView.getLocationOnScreen(position)
        val status = if (Build.VERSION.SDK_INT >= 30) insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top else insets.stableInsetTop
        val logicalWidth = (textureWidth / density).roundToInt(); val logicalHeight = (textureHeight / density).roundToInt()
        val screenWidth = (extent.x / density).roundToInt(); val screenHeight = (extent.y / density).roundToInt()
        if (logicalWidth <= 0 || logicalHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return
        val next = WindowMetrics(logicalWidth, logicalHeight,
            density, screenWidth, screenHeight, status / density,
            position[1] / density, if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
        val visible = isShown && windowVisibility == VISIBLE
        if (token == null) {
            val nextToken = Any(); token = nextToken
            current.attachSurface(nextToken, Surface(texture), textureWidth, textureHeight, next, visible)
        } else if (next != observation || visible != lastVisible) {
            current.observeSurface(checkNotNull(token), textureWidth, textureHeight, next, visible)
        }
        observation = next; lastVisible = visible
        resolveObservations(next)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = session ?: return false
        val metrics = observation ?: return false
        if (!isEnabled || token == null) return false
        val scaleX = metrics.windowWidth.toDouble() / textureWidth
        val scaleY = metrics.windowHeight.toDouble() / textureHeight
        val action = event.actionMasked; val index = event.actionIndex
        val type = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> "touchstart"
            MotionEvent.ACTION_MOVE -> "touchmove"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> "touchend"
            MotionEvent.ACTION_CANCEL -> "touchcancel"
            else -> return true
        }
        if (action == MotionEvent.ACTION_DOWN) { requestFocus(); parent?.requestDisallowInterceptTouchEvent(true); tap = Tap(event.getPointerId(0), event.x, event.y, event.eventTime) }
        if (event.pointerCount > 1) tap = null
        tap?.let { it.moved = it.moved || hypot((event.x - it.x).toDouble(), (event.y - it.y).toDouble()) > ViewConfiguration.get(context).scaledTouchSlop }
        fun touch(i: Int) = JSONObject().put("identifier", event.getPointerId(i)).put("clientX", event.getX(i) * scaleX)
            .put("clientY", event.getY(i) * scaleY).put("pageX", event.getX(i) * scaleX).put("pageY", event.getY(i) * scaleY)
        val touches = JSONArray(); val changed = JSONArray()
        for (i in 0 until event.pointerCount) {
            if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_UP && !(action == MotionEvent.ACTION_POINTER_UP && i == index)) touches.put(touch(i))
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL || i == index) changed.put(touch(i))
        }
        val x = event.getX(index) * scaleX; val y = event.getY(index) * scaleY
        fun input(kind: String) = JSONObject().put("type", kind).put("x", x).put("y", y).put("timeStamp", event.eventTime)
            .put("touches", touches).put("changedTouches", changed).put("detail", if (kind == "tap") JSONObject().put("x", x).put("y", y) else JSONObject())
        val events = JSONArray().put(input(type))
        if (action == MotionEvent.ACTION_UP) {
            tap?.let { if (!it.moved && event.eventTime - it.time in 0..500) { events.put(input("tap")); performClick() } }
            tap = null; parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (action == MotionEvent.ACTION_CANCEL) { tap = null; parent?.requestDisallowInterceptTouchEvent(false) }
        current.input(events)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = sendKey("keydown", keyCode, event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = sendKey("keyup", keyCode, event) || super.onKeyUp(keyCode, event)
    private fun sendKey(type: String, keyCode: Int, event: KeyEvent): Boolean {
        val current = session ?: return false
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val key = if (event.unicodeChar != 0) String(Character.toChars(event.unicodeChar)) else KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        current.input(JSONArray().put(JSONObject().put("type", type).put("timeStamp", event.eventTime)
            .put("detail", JSONObject().put("key", key).put("code", KeyEvent.keyCodeToString(keyCode)).put("repeat", event.repeatCount > 0)
                .put("altKey", event.isAltPressed).put("ctrlKey", event.isCtrlPressed).put("shiftKey", event.isShiftPressed).put("metaKey", event.isMetaPressed))))
        return true
    }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Views must be bound on Android's main thread" } }
}
