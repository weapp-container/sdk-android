package com.sonofmagic.weapp.container.sdk

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface

/** Worker-thread owner of exactly one context/window; the process EGL display is shared. */
internal class EglPresenter(
    private val handle: () -> Long,
    private val runtime: GlesRuntime = NativeGlesRuntime,
    private val glVersion: () -> String? = { GLES20.glGetString(GLES20.GL_VERSION) },
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var target: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var window: Surface? = null
    private var runtimeAttached = false
    val isOpen: Boolean get() = target != EGL14.EGL_NO_SURFACE
    val isRetired: Boolean get() = !runtimeAttached && target == EGL14.EGL_NO_SURFACE && context == EGL14.EGL_NO_CONTEXT

    fun open(surface: Surface) {
        check(!isOpen)
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig failed" }
            config = configs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            window = surface
            openWindow()
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    fun attachRuntime() {
        if (!isOpen || runtimeAttached || handle() == 0L) return
        val ownedWindow = checkNotNull(window)
        var firstFailure: Throwable? = null
        repeat(2) { attempt ->
            try { current() } catch (error: Throwable) { diagnose(error); throw error }
            val failure = if (!usableCurrentContext()) {
                ContainerException("ERR_RENDER", "Current GLES context has no valid GL_VERSION or differs from its owner")
            } else {
                try { runtime.attach(handle()); runtimeAttached = true; return }
                catch (error: Throwable) {
                    // A normal renderer error is not evidence of context loss.
                    // Only an actually invalid current context permits recovery.
                    if (usableCurrentContext()) { diagnose(error); throw error }
                    error
                }
            }
            diagnose(failure)
            firstFailure?.takeIf { it !== failure }?.let { failure.addSuppressed(it) }
            // No successful renderer binding exists. Retire only our context
            // and window; the caller still owns the Surface and live session.
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup); throw failure }
            if (attempt == 1) throw failure
            firstFailure = failure
            Log.w("WeappContainer", "Recreating invalid owned GLES context once: session=${handle()}, thread=${android.os.Process.myTid()}")
            try { open(ownedWindow) } catch (recreate: Throwable) { recreate.addSuppressed(failure); throw recreate }
        }
    }
    private fun usableCurrentContext() = EGL14.eglGetCurrentContext() == context &&
        EGL14.eglGetCurrentDisplay() == display && !glVersion().isNullOrBlank()
    private fun diagnose(error: Throwable) {
        // Capture the driver's actual thread-local state before teardown.
        try {
            val eglError = EGL14.eglGetError()
            val glError = GLES20.glGetError()
            Log.e("WeappContainer", "GLES attach failed: session=${handle()}, thread=${android.os.Process.myTid()}, " +
                    "ownedContext=${context.nativeHandle}, currentContext=${EGL14.eglGetCurrentContext().nativeHandle}, " +
                    "ownedDisplay=${display.nativeHandle}, currentDisplay=${EGL14.eglGetCurrentDisplay().nativeHandle}, " +
                    "ownedSurface=${target.nativeHandle}, drawSurface=${EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW).nativeHandle}, " +
                    "readSurface=${EGL14.eglGetCurrentSurface(EGL14.EGL_READ).nativeHandle}, " +
                    "eglError=$eglError, glError=$glError, version=${GLES20.glGetString(GLES20.GL_VERSION)}, " +
                "vendor=${GLES20.glGetString(GLES20.GL_VENDOR)}, renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}", error)
        } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
    }
    fun detachRuntime() {
        if (!runtimeAttached) return
        val current = tryCurrent()
        try { runtime.detach(handle(), !current) }
        catch (error: Throwable) {
            if (!current) throw error
            // A failed current-context teardown must still retire native GPU
            // ownership before this presenter destroys its own EGL context.
            try { runtime.detach(handle(), true) }
            catch (abandon: Throwable) { error.addSuppressed(abandon); throw error }
        }
        runtimeAttached = false
    }
    fun present(width: Int, height: Int): Boolean {
        if (!runtimeAttached || width <= 0 || height <= 0) return false
        current()
        fun actualExtent(): IntArray {
            val actual = IntArray(2)
            check(EGL14.eglQuerySurface(display, target, EGL14.EGL_WIDTH, actual, 0) &&
                EGL14.eglQuerySurface(display, target, EGL14.EGL_HEIGHT, actual, 1)) { "eglQuerySurface failed" }
            return actual
        }
        var actual = actualExtent()
        // Some BufferQueues keep an old allocation until a swap. Reacquire
        // only the EGL window target; preserve the context and live JS session.
        if (actual[0] != width || actual[1] != height) {
            check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) { "EGL resize unbind failed" }
            check(EGL14.eglDestroySurface(display, target)) { "EGL resize retirement failed" }
            target = EGL14.EGL_NO_SURFACE
            openWindow(); actual = actualExtent()
        }
        check(actual[0] == width && actual[1] == height) { "Acquired EGL drawable differs from observed viewport: ${actual[0]}x${actual[1]} vs ${width}x${height}" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        runtime.present(handle(), width, height)
        check(EGL14.eglSwapBuffers(display, target)) { "eglSwapBuffers failed: ${EGL14.eglGetError()}" }
        return true
    }
    fun close() {
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) } }
        cleanup { detachRuntime() }
        if (runtimeAttached) throw checkNotNull(failure)
        cleanup {
            if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT)
                check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) { "EGL unbind failed" }
        }
        cleanup { if (target != EGL14.EGL_NO_SURFACE) {
            check(EGL14.eglDestroySurface(display, target)) { "EGL surface cleanup failed" }
            target = EGL14.EGL_NO_SURFACE
        } }
        cleanup { if (context != EGL14.EGL_NO_CONTEXT) {
            check(EGL14.eglDestroyContext(display, context)) { "EGL context cleanup failed" }
            context = EGL14.EGL_NO_CONTEXT
        } }
        // Never eglTerminate: EGL_DEFAULT_DISPLAY is also used by the embedding
        // Activity, Flutter/RN and other sessions. No host EGL ownership is taken.
        if (isRetired) { display = EGL14.EGL_NO_DISPLAY; config = null; window = null }
        failure?.let { throw it }
    }
    private fun openWindow() {
        val owned = checkNotNull(window); check(owned.isValid) { "Window Surface is invalid" }
        target = EGL14.eglCreateWindowSurface(display, config, owned, intArrayOf(EGL14.EGL_NONE), 0)
        check(target != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        current()
    }
    private fun tryCurrent() = display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT &&
        target != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, target, target, context)
    private fun current() { check(tryCurrent()) { "eglMakeCurrent failed: ${EGL14.eglGetError()}" } }
}

/** Renderer binding is separate from ownership of EGL context/window handles. */
internal interface GlesRuntime {
    fun attach(handle: Long)
    fun detach(handle: Long, abandon: Boolean)
    fun present(handle: Long, width: Int, height: Int)
}
internal object NativeGlesRuntime : GlesRuntime {
    override fun attach(handle: Long) { unwrap(NativeBridge.glesAttach(handle)) }
    override fun detach(handle: Long, abandon: Boolean) { unwrap(NativeBridge.glesDetach(handle, abandon)) }
    override fun present(handle: Long, width: Int, height: Int) { unwrap(NativeBridge.glesPresent(handle, width, height)) }
}
