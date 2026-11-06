package com.sonofmagic.weapp.container.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ContainerException(
    val code: String,
    message: String,
    cause: Throwable? = null,
    /** Raw backend/native extension fields, kept separate from the container error class. */
    val details: JSONObject = JSONObject(),
) : RuntimeException(message, cause)

sealed class PackSource {
    data class Asset(val path: String) : PackSource() {
        init { require(path.isNotBlank() && !path.startsWith("/") && path.split('/').none { it == ".." || it == "." }) }
    }
    data class Directory(val directory: File) : PackSource()
    internal fun <T> withDirectory(context: Context, block: (File) -> T): T = when (this) {
        is Directory -> block(directory.canonicalFile)
        is Asset -> {
            val root = File(context.cacheDir, "weapp-container-packs/${UUID.randomUUID()}")
            try {
                fun copy(asset: String, destination: File) {
                    val children = context.assets.list(asset) ?: emptyArray()
                    if (children.isNotEmpty()) {
                        check(destination.mkdirs() || destination.isDirectory)
                        for (child in children) { require(!child.contains('/') && child != ".." && child != "."); copy("$asset/$child", File(destination, child)) }
                    } else {
                        destination.parentFile?.mkdirs()
                        context.assets.open(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
                    }
                }
                copy(path, root)
                block(root)
            } finally { root.deleteRecursively() }
        }
    }
}

data class LoadOptions(val route: String? = null, val options: JSONObject = JSONObject(), val initialHostChrome: JSONObject? = null, val initialHostLayout: JSONObject? = null)

/** Observed logical viewport and physical display. Safe-area mapping is deliberately optional. */
data class WindowMetrics(
    val windowWidth: Int, val windowHeight: Int, val pixelRatio: Double,
    val screenWidth: Int, val screenHeight: Int, val statusBarHeight: Double,
    val screenTop: Double, val deviceOrientation: String,
) {
    init {
        require(windowWidth > 0 && windowHeight > 0 && screenWidth > 0 && screenHeight > 0)
        require(pixelRatio.isFinite() && pixelRatio > 0 && statusBarHeight.isFinite() && statusBarHeight >= 0)
        require(statusBarHeight <= screenHeight + 0.5 && screenTop.isFinite())
        require(deviceOrientation == "portrait" || deviceOrientation == "landscape")
    }
    internal fun displayJson() = JSONObject().put("pixelRatio", pixelRatio).put("screenWidth", screenWidth)
        .put("screenHeight", screenHeight).put("statusBarHeight", statusBarHeight).put("screenTop", screenTop)
        .put("deviceOrientation", deviceOrientation)
    internal fun json() = displayJson().put("windowWidth", windowWidth).put("windowHeight", windowHeight)
}

/** All event callbacks run on Android's main thread, after the native operation returns. */
fun interface ContainerEventListener { fun onEvent(event: JSONObject) }
fun interface HostMethod { fun invoke(call: HostCall, reply: HostReply) }

class HostCall internal constructor(
    val id: String, val method: String, val payload: Any?, val deadlineNs: Long?, val cancelToken: String?,
    /** Frozen native identity; never reconstructed from payload or app options. */
    val context: JSONObject? = null,
    /** Full u64 value, including values outside Java's signed Long range. */
    val deadlineNsDecimal: String? = deadlineNs?.toString(),
    val keepsResourceAlive: Boolean = false,
) {
    private val cancelled = AtomicBoolean(false)
    private val cancellationDelivered = AtomicBoolean(false)
    @Volatile var onCancel: (() -> Unit)? = null
        set(value) { field = value; deliverCancellation() }
    val isCancelled: Boolean get() = cancelled.get()
    /** Compose adapter cleanup with an existing permission or OS-operation cancellation hook. */
    fun addCancellationHandler(handler: () -> Unit) {
        if (isCancelled) { handler(); return }
        val previous = onCancel
        onCancel = {
            var failure: Throwable? = null
            try { previous?.invoke() } catch (error: Throwable) { failure = error }
            try { handler() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
            if (failure != null) throw failure
        }
    }
    internal fun cancel() { cancelled.set(true); deliverCancellation() }
    private fun deliverCancellation() {
        val callback = onCancel
        if (cancelled.get() && callback != null && cancellationDelivered.compareAndSet(false, true)) {
            try { callback() } catch (error: Throwable) { cancellationDelivered.set(false); throw error }
        }
    }
}

enum class HostErrorCode(internal val wire: String) {
    INVALID_REQUEST("invalid_request"), PERMISSION_DENIED("permission_denied"), TIMEOUT("timeout"),
    CANCELLED("cancelled"), UNSUPPORTED("unsupported"),
}

class HostReply internal constructor(private val retain: () -> Unit = {}, private val complete: (JSONObject) -> Unit) {
    private val settled = AtomicBoolean(false)
    /** Keep the creator's cancellation hook alive after a subscription/object is created. */
    fun retainUntilCancelled() { retain() }
    fun resolve(value: Any? = null) {
        val payload = JSONObject().put("result", jsonCopy(value))
        if (settled.compareAndSet(false, true)) complete(payload)
    }
    @JvmOverloads fun reject(code: HostErrorCode, message: String, retryable: Boolean = false, details: JSONObject = JSONObject()) {
        val payload = JSONObject().put("error", JSONObject().put("code", code.wire).put("message", message).put("retryable", retryable).put("details", jsonCopy(details)))
        if (settled.compareAndSet(false, true)) complete(payload)
    }
}

/** Explicit diagnostic readback; normal presentation does not read pixels back to Java. */
data class ContainerSnapshot(val width: Int, val height: Int, val stride: Int, val revision: Long, val rgba: ByteArray) {
    internal companion object {
        fun decode(bytes: ByteArray): ContainerSnapshot {
            require(bytes.size >= 24)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val width = buffer.int; val height = buffer.int; val stride = buffer.int; buffer.int
            val revision = buffer.long
            require(width > 0 && height > 0 && stride.toLong() >= width.toLong() * 4 && bytes.size.toLong() - 24 == stride.toLong() * height)
            return ContainerSnapshot(width, height, stride, revision, bytes.copyOfRange(24, bytes.size))
        }
    }
}

internal fun jsonCopy(value: Any?): Any = when (value) {
    null, JSONObject.NULL -> JSONObject.NULL
    is JSONObject -> JSONObject(value.toString())
    is JSONArray -> JSONArray(value.toString())
    is String, is Boolean, is Int, is Long, is Short, is Byte -> value
    is Number -> { require(value.toDouble().isFinite()); value }
    else -> throw IllegalArgumentException("Only JSON values are supported")
}
internal fun unwrap(text: String): Any? {
    val result = JSONObject(text)
    if (!result.getBoolean("ok")) {
        val error = result.getJSONObject("error")
        throw ContainerException(
            error.optString("code", "ERR_NATIVE"),
            error.optString("message", "Native operation failed"),
            details = parseHostBackendError(error).details,
        )
    }
    return result.opt("value").takeUnless { it == JSONObject.NULL }
}
internal fun Throwable.asContainerException(): ContainerException {
    if (this is ContainerException) return this
    try { message?.let { unwrap(it) } } catch (error: ContainerException) { return error } catch (_: Exception) { }
    return ContainerException("ERR_ANDROID", message ?: javaClass.simpleName, this)
}
