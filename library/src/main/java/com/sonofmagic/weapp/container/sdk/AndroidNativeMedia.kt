package com.sonofmagic.weapp.container.sdk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Real Camera2/MediaPlayer frames are composed in the accepted Skia node, without native overlays. */
class AndroidNativeMedia(activity: Activity, private val files: AndroidManagedFiles, private val permissions: AndroidPermissionBroker? = null) : AutoCloseable {
    private var binding: Activity? = activity
    private val activity: Activity get() = checkNotNull(binding) { "Native Activity is detached" }
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("WeappContainer.media").apply { start() }
    private val decoder = Handler(thread.looper)
    private val consent = AndroidAppPermissionConsent({ this.activity }, { files.auxiliaryRoot(it) })
    private var session: WeappContainerSession? = null
    private var closed = false
    private var polling = false
    private data class Frame(val pixels: ByteArray, val width: Int, val height: Int)
    private class Mount(var snapshot: JSONObject, val context: JSONObject) {
        @Volatile var active = true
        var camera: CameraDevice? = null
        var cameraLifecycle: java.util.concurrent.CountDownLatch? = null
        var capture: CameraCaptureSession? = null
        var request: CaptureRequest.Builder? = null
        var sensor: Rect? = null
        var maxZoom = 1f
        var reader: ImageReader? = null
        var player: MediaPlayer? = null
        var retriever: MediaMetadataRetriever? = null
        var source = ""
        var prepared = false
        @Volatile var photo: ByteArray? = null
        val decoding = AtomicBoolean(false)
        val framePosted = AtomicBoolean(false)
        val frameLock = Any()
        var latest: Frame? = null
        var sequence = 0L
        var eventSequence = 0L
        var lastCameraFrame = 0L
        var frameErrorReported = false
    }
    private val mounts = mutableMapOf<String, Mount>()
    private val objects = mutableMapOf<String, Pair<Mount, JSONObject>>()
    val supportedApis = setOf("component:camera", "component:video", "wx.createCameraContext", "object:CameraContext.takePhoto", "object:CameraContext.setZoom", "wx.createVideoContext", "object:VideoContext.play", "object:VideoContext.pause", "object:VideoContext.stop", "object:VideoContext.seek", "object:VideoContext.playbackRate")
    fun rebindActivity(activity: Activity?) { check(Looper.myLooper() == Looper.getMainLooper()); if (binding !== activity) { try { reset() } finally { binding = activity } } }
    fun install(target: WeappContainerSession): CompletableFuture<Void> {
        check(session == null); session = target
        val registered = CompletableFuture.allOf(target.registerHostResourceCleanup("media-${UUID.randomUUID()}") { reset() }, *supportedApis.map { api -> target.registerApiHandler(api) { call, reply ->
            files.prepareScope(requireNotNull(call.context)).whenComplete { _, error ->
                if (!call.isCancelled) {
                    if (error != null) reply.reject(HostErrorCode.PERMISSION_DENIED, error.message ?: "Managed media scope unavailable")
                    else invoke(api, call, reply)
                }
            }
        } }.toTypedArray())
        registered.thenRun { main.post(pump) }; return registered
    }
    private val pump = object : Runnable { override fun run() { if (closed) return; tick(); main.postDelayed(this, 200) } }
    private fun key(value: JSONObject) = listOf("sessionId", "accountEpoch", "pageId", "nodeId").joinToString(":") { value.get(it).toString() }
    private fun tick() {
        val target = session ?: return
        if (polling || closed) return; polling = true
        target.state().thenCompose { state ->
            if (!state.optBoolean("foreground") || state.optString("status") != "ready" || binding == null) { reset(); CompletableFuture.completedFuture(Pair(state, JSONArray())) }
            else {
                val context = JSONObject(state.getJSONObject("hostServicesState").toString()).put("sessionId", state.getString("sessionId"))
                files.prepareScope(context).thenCompose { target.hostServicesCommand("hostComponents") }.thenApply { Pair(state, it as? JSONArray ?: JSONArray()) }
            }
        }.whenComplete { result, error ->
            polling = false
            if (error != null || closed) return@whenComplete
            try {
                val state = result.first; val snapshots = result.second
                val accepted = (0 until snapshots.length()).map { snapshots.getJSONObject(it) }.filter { it.optString("tag") in setOf("camera", "video") && it.optBoolean("visible") }
                val keys = accepted.map(::key).toSet(); for (id in mounts.keys.toList()) if (id !in keys) unmount(id)
                for (snapshot in accepted) {
                    val id = key(snapshot); val prior = mounts[id]
                    if (prior != null) {
                        val changedCamera = snapshot.getString("tag") == "camera" && listOf("device-position", "flash", "mode", "resolution").any { prior.snapshot.getJSONObject("attributes").optString(it) != snapshot.getJSONObject("attributes").optString(it) }
                        if (changedCamera) unmount(id) else { prior.snapshot = snapshot; if (snapshot.getString("tag") == "video") updateVideo(prior); continue }
                    }
                    val trusted = JSONObject(state.getJSONObject("hostServicesState").toString()).put("sessionId", snapshot.getString("sessionId"))
                    val mount = Mount(snapshot, trusted); mounts[id] = mount
                    if (snapshot.getString("tag") == "camera") startCamera(mount) else updateVideo(mount)
                }
                for (mount in mounts.values.toList()) if (mount.snapshot.getString("tag") == "video") videoFrame(mount)
            } catch (failure: Throwable) { android.util.Log.e("WeappContainer", "Native media binding failed", failure) }
        }
    }
    private fun synthetic(mount: Mount) = HostCall("native-camera-${UUID.randomUUID()}", "component:camera", JSONObject(), null, null, mount.context)
    private fun startCamera(mount: Mount) {
        val call = synthetic(mount)
        try { consent.request("scope.camera", call) { appGranted ->
            if (!appGranted || !mount.active) { event(mount, "error", JSONObject().put("errMsg", "Camera app permission denied")); return@request }
            fun granted(allowed: Boolean) { main.post {
                if (!mount.active || binding == null) return@post
                if (!allowed || activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { event(mount, "error", JSONObject().put("errMsg", "Camera OS permission denied")); return@post }
                decoder.post { try { openCamera(mount) } catch (error: Throwable) { main.post { event(mount, "error", JSONObject().put("errMsg", error.message)) } } }
            } }
            if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) granted(true)
            else if (permissions != null) permissions.request(arrayOf(Manifest.permission.CAMERA), call, ::granted)
            else granted(false)
        } } catch (error: Throwable) { event(mount, "error", JSONObject().put("errMsg", error.message)) }
    }
    private fun openCamera(mount: Mount) {
        if (!mount.active) return
        require(mount.snapshot.getJSONObject("attributes").optString("mode", "normal") == "normal") { "scanCode mode requires a barcode adapter" }
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val front = mount.snapshot.getJSONObject("attributes").optString("device-position") == "front"
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK } ?: error("Camera unavailable")
        val properties = manager.getCameraCharacteristics(id)
        val sizes = properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes.filter { it.width <= 640 && it.height <= 480 }.maxByOrNull { it.width * it.height } ?: sizes.minBy { it.width * it.height }
        mount.sensor = properties.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE); mount.maxZoom = properties.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val orientation = properties.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2); mount.reader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!mount.active || SystemClock.elapsedRealtime() - mount.lastCameraFrame < 200) return@setOnImageAvailableListener
                mount.lastCameraFrame = SystemClock.elapsedRealtime()
                val degrees = when (activity.display?.rotation) { android.view.Surface.ROTATION_90 -> 90; android.view.Surface.ROTATION_180 -> 180; android.view.Surface.ROTATION_270 -> 270; else -> 0 }
                val rotation = if (front) (orientation + degrees) % 360 else (orientation - degrees + 360) % 360
                val original = yuvBitmap(image); val transform = Matrix().apply { postRotate(rotation.toFloat()); if (front) postScale(-1f, 1f) }
                val oriented = Bitmap.createBitmap(original, 0, 0, original.width, original.height, transform, true)
                val output = scaled(oriented); val photo = ByteArrayOutputStream(); output.compress(Bitmap.CompressFormat.JPEG, 90, photo); mount.photo = photo.toByteArray()
                enqueue(mount, rgba(output)); if (output !== oriented) output.recycle(); if (oriented !== original) oriented.recycle(); original.recycle()
            } finally { image.close() }
        }, decoder)
        val lifecycle = java.util.concurrent.CountDownLatch(1); mount.cameraLifecycle = lifecycle
        try { manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!mount.active) { camera.close(); return }; mount.camera = camera
                try { camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(capture: CameraCaptureSession) {
                        try {
                        if (!mount.active) { capture.close(); camera.close(); return }
                        mount.capture = capture; val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); request.addTarget(reader.surface); request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        val flash = mount.snapshot.getJSONObject("attributes").optString("flash", "off")
                        if (flash in setOf("on", "torch")) { require(properties.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) { "Camera has no torch" }; request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH) }
                        else require(flash == "off") { "Camera flash mode is not implemented" }
                        mount.request = request
                        capture.setRepeatingRequest(request.build(), null, decoder); main.post { event(mount, "initdone", JSONObject().put("maxZoom", mount.maxZoom.toDouble())) }
                        } catch (error: Throwable) { capture.close(); camera.close(); main.post { event(mount, "error", JSONObject().put("errMsg", error.message)) } }
                    }
                    override fun onConfigureFailed(capture: CameraCaptureSession) { capture.close(); main.post { event(mount, "error", JSONObject().put("errMsg", "Camera configuration failed")) } }
                }, decoder) } catch (error: Throwable) { camera.close(); main.post { event(mount, "error", JSONObject().put("errMsg", error.message)) } }
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); main.post { event(mount, "stop", JSONObject()) } }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); main.post { event(mount, "error", JSONObject().put("errCode", error).put("errMsg", "Camera error $error")) } }
            override fun onClosed(camera: CameraDevice) { lifecycle.countDown() }
        }, decoder) } catch (error: Throwable) { lifecycle.countDown(); throw error }
    }
    private fun yuvBitmap(image: Image): Bitmap {
        val width = image.width; val height = image.height; val output = IntArray(width * height)
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        for (row in 0 until height) for (column in 0 until width) {
            val luminance = (y.buffer.get(row * y.rowStride + column * y.pixelStride).toInt() and 255) - 16
            val blue = (u.buffer.get(row / 2 * u.rowStride + column / 2 * u.pixelStride).toInt() and 255) - 128
            val red = (v.buffer.get(row / 2 * v.rowStride + column / 2 * v.pixelStride).toInt() and 255) - 128
            val c = maxOf(0, luminance) * 298
            output[row * width + column] = (255 shl 24) or (((c + 409 * red + 128 shr 8).coerceIn(0, 255)) shl 16) or (((c - 100 * blue - 208 * red + 128 shr 8).coerceIn(0, 255)) shl 8) or ((c + 516 * blue + 128 shr 8).coerceIn(0, 255))
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }
    private fun scaled(bitmap: Bitmap): Bitmap { val ratio = minOf(1.0, 320.0 / bitmap.width, 240.0 / bitmap.height); return if (ratio == 1.0) bitmap else Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true) }
    private fun rgba(bitmap: Bitmap): Frame {
        val colors = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArray(colors.size * 4); for ((index, color) in colors.withIndex()) { bytes[index * 4] = (color shr 16).toByte(); bytes[index * 4 + 1] = (color shr 8).toByte(); bytes[index * 4 + 2] = color.toByte(); bytes[index * 4 + 3] = 255.toByte() }; return Frame(bytes, bitmap.width, bitmap.height)
    }
    private fun enqueue(mount: Mount, frame: Frame) {
        if (!mount.active) return
        synchronized(mount.frameLock) { mount.latest = frame }
        if (mount.framePosted.compareAndSet(false, true)) main.post { submit(mount) }
    }
    private fun submit(mount: Mount) {
        if (!mount.active) { mount.framePosted.set(false); return }
        val frame = synchronized(mount.frameLock) { val value = mount.latest; mount.latest = null; value }
        if (frame == null) { mount.framePosted.set(false); return }
        val snapshot = mount.snapshot; mount.sequence++
        session?.hostServicesCommand("hostComponentFrame", JSONObject().put("sessionId", snapshot.get("sessionId")).put("accountEpoch", snapshot.get("accountEpoch")).put("pageId", snapshot.get("pageId")).put("nodeId", snapshot.get("nodeId")).put("sequence", mount.sequence.toString()).put("width", frame.width).put("height", frame.height).put("format", "rgba8").put("pixels", JSONArray().apply { frame.pixels.forEach { put(it.toInt() and 255) } }))?.whenComplete { _, error ->
            mount.framePosted.set(false)
            if (error != null && mount.active && !closed && !mount.frameErrorReported) {
                mount.frameErrorReported = true
                android.util.Log.e("WeappContainer", "Native media frame submission failed", error)
                event(mount, "error", JSONObject().put("errMsg", "Native media frame submission failed: ${error.message}"))
            }
            if (mount.active && synchronized(mount.frameLock) { mount.latest != null } && mount.framePosted.compareAndSet(false, true)) main.post { submit(mount) }
        }
    }
    private fun updateVideo(mount: Mount) {
        val source = mount.snapshot.getJSONObject("attributes").optString("src"); if (mount.source == source) return
        mount.source = source
        decoder.post {
            try {
                stopVideo(mount); if (source.isEmpty() || !mount.active) return@post
                val file = files.resolve(source, mount.context); val retriever = MediaMetadataRetriever(); retriever.setDataSource(file.path); mount.retriever = retriever
                val player = MediaPlayer(); mount.player = player; player.setDataSource(file.path)
                val attributes = mount.snapshot.getJSONObject("attributes")
                player.isLooping = attributes.has("loop") && attributes.optString("loop") != "false"
                if (attributes.has("muted") && attributes.optString("muted") != "false") player.setVolume(0f, 0f)
                player.setOnPreparedListener { if (mount.active && mount.player === player) { mount.prepared = true
                    main.post { event(mount, "loadedmetadata", JSONObject().put("duration", player.duration / 1000.0).put("width", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0).put("height", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0)) }
                    if (attributes.has("autoplay") && attributes.optString("autoplay") != "false") { player.start(); main.post { event(mount, "play", JSONObject()) } }
                } }
                player.setOnCompletionListener { if (mount.player === player) main.post { event(mount, "ended", JSONObject()) } }
                player.setOnErrorListener { _, what, extra -> if (mount.player === player) main.post { event(mount, "error", JSONObject().put("errCode", what).put("errMsg", "MediaPlayer $what/$extra")) }; true }
                player.prepareAsync()
            } catch (error: Throwable) { main.post { event(mount, "error", JSONObject().put("errMsg", error.message)) } }
        }
    }
    private fun videoFrame(mount: Mount) {
        if (!mount.decoding.compareAndSet(false, true)) return
        decoder.post { try {
            if (!mount.active || !mount.prepared) return@post
            val player = mount.player ?: return@post; val retriever = mount.retriever ?: return@post; val position = player.currentPosition
            val original = if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(position * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, 320, 240) else retriever.getFrameAtTime(position * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            if (original != null) { val bitmap = scaled(original); enqueue(mount, rgba(bitmap)); if (bitmap !== original) bitmap.recycle(); original.recycle() }
            val duration = player.duration / 1000.0
            main.post { event(mount, "timeupdate", JSONObject().put("currentTime", position / 1000.0).put("duration", duration)) }
        } catch (error: Throwable) { main.post { event(mount, "error", JSONObject().put("errMsg", error.message)) } } finally { mount.decoding.set(false) } }
    }
    private fun event(mount: Mount, event: String, value: JSONObject) {
        if (!mount.active) return; mount.eventSequence++
        session?.hostServicesCommand("hostComponentEvent", JSONObject().put("sessionId", mount.snapshot.get("sessionId")).put("accountEpoch", mount.snapshot.get("accountEpoch")).put("nodeId", mount.snapshot.get("nodeId")).put("sequence", mount.eventSequence.toString()).put("event", event).put("value", value))
    }
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        try {
            check(!closed && !call.isCancelled && binding != null)
            val payload = call.payload as? JSONObject ?: JSONObject(); val context = requireNotNull(call.context)
            if (api == "wx.createCameraContext" || api == "wx.createVideoContext") {
                val tag = if (api == "wx.createCameraContext") "camera" else "video"
                val trustedTarget = context.getJSONObject("componentTarget")
                val mount = requireNotNull(mounts.values.firstOrNull { it.snapshot.optString("tag") == tag && it.snapshot.optString("sessionId") == context.optString("sessionId") && it.snapshot.get("accountEpoch").toString() == context.getJSONObject("account").get("epoch").toString() && it.snapshot.getString("nodeId") == trustedTarget.getString("nodeId") && it.snapshot.get("pageId").toString() == trustedTarget.get("pageId").toString() && it.snapshot.opt("ownerScopeId") == trustedTarget.opt("ownerScopeId") }) { "Context requires the runtime-resolved accepted component" }
                require(objects.size < 64); val objectId = payload.getString("objectId"); objects[objectId] = Pair(mount, context); reply.retainUntilCancelled(); call.onCancel = { objects.remove(objectId) }; reply.resolve(JSONObject().put("objectId", objectId)); return
            }
            val owned = requireNotNull(objects[payload.getString("objectId")]) { "Native component context retired" }; val mount = owned.first
            require(mount.active && owned.second.getString("sessionId") == context.getString("sessionId") && owned.second.getJSONObject("account").get("epoch").toString() == context.getJSONObject("account").get("epoch").toString())
            decoder.post { try {
                check(mount.active && !call.isCancelled)
                val result = JSONObject().put("errMsg", "$api:ok")
                when (api) {
                    "object:CameraContext.takePhoto" -> {
                        val bytes = requireNotNull(mount.photo) { "Camera has no captured frame" }; val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size); val output = ByteArrayOutputStream()
                        image.compress(Bitmap.CompressFormat.JPEG, when (payload.optString("quality")) { "low" -> 50; "normal" -> 80; else -> 95 }, output)
                        result.put("tempImagePath", files.importTemporary(output.toByteArray(), "jpg", context)).put("width", image.width).put("height", image.height); image.recycle()
                    }
                    "object:CameraContext.setZoom" -> {
                        val zoom = payload.getDouble("zoom"); require(zoom.isFinite() && zoom >= 1 && zoom <= mount.maxZoom)
                        val rect = requireNotNull(mount.sensor); val halfWidth = (rect.width() / zoom / 2).toInt(); val halfHeight = (rect.height() / zoom / 2).toInt()
                        val request = requireNotNull(mount.request); request.set(CaptureRequest.SCALER_CROP_REGION, Rect(rect.centerX() - halfWidth, rect.centerY() - halfHeight, rect.centerX() + halfWidth, rect.centerY() + halfHeight)); requireNotNull(mount.capture).setRepeatingRequest(request.build(), null, decoder); result.put("zoom", zoom)
                    }
                    else -> {
                        val player = requireNotNull(mount.player); check(mount.prepared) { "Video source not ready" }
                        when (api) {
                            "object:VideoContext.play" -> { player.start(); main.post { event(mount, "play", JSONObject()) } }
                            "object:VideoContext.pause" -> { player.pause(); main.post { event(mount, "pause", JSONObject()) } }
                            "object:VideoContext.stop" -> { player.pause(); player.seekTo(0); main.post { event(mount, "pause", JSONObject()) } }
                            "object:VideoContext.seek" -> { val position = payload.optDouble("position", payload.optJSONArray("args")?.optDouble(0) ?: Double.NaN); require(position.isFinite() && position >= 0 && position <= Int.MAX_VALUE / 1000); player.seekTo((position * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) }
                            "object:VideoContext.playbackRate" -> { val rate = payload.optDouble("rate", payload.optJSONArray("args")?.optDouble(0) ?: Double.NaN); require(rate.isFinite() && rate in 0.5..2.0); val playing = player.isPlaying; player.playbackParams = PlaybackParams().setSpeed(rate.toFloat()); if (!playing) player.pause() }
                            else -> error("Native component API not installed")
                        }
                    }
                }
                main.post { if (!call.isCancelled && mount.active) reply.resolve(result) }
            } catch (error: Throwable) { main.post { if (!call.isCancelled) reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Native media failed") } } }
        } catch (error: Throwable) { reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Native media failed") }
    }
    private fun stopVideo(mount: Mount) {
        mount.prepared = false; var failure: Throwable? = null
        try { mount.player?.release(); mount.player = null } catch (error: Throwable) { failure = error }
        try { mount.retriever?.release(); mount.retriever = null } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        if (failure != null) throw failure!!
    }
    private fun unmount(id: String) {
        val mount = mounts[id] ?: return; mount.active = false; objects.entries.removeAll { it.value.first === mount }
        synchronized(mount.frameLock) { mount.latest = null }; mount.photo = null
        val done = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        decoder.post {
            fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) { val previous = failure.get(); if (previous == null) failure.set(error) else if (previous !== error) previous.addSuppressed(error) } }
            try {
                attempt { mount.capture?.close(); mount.capture = null }; attempt { mount.camera?.close(); mount.camera = null }
                attempt { mount.reader?.close(); mount.reader = null }; attempt { stopVideo(mount) }
            } finally { done.countDown() }
        }
        check(done.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Native media cleanup has not drained" }
        failure.get()?.let { throw it }
        check(mount.cameraLifecycle?.await(10, java.util.concurrent.TimeUnit.SECONDS) != false) { "Camera device close has not completed" }
        mounts.remove(id)
    }
    private fun reset() {
        consent.reset(); var failure: Throwable? = null
        for (id in mounts.keys.toList()) try { unmount(id) } catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error) }
        objects.clear(); if (failure != null) throw failure!!
    }
    override fun close() { check(Looper.myLooper() == Looper.getMainLooper()); closed = true; main.removeCallbacks(pump); reset(); session = null; thread.quitSafely() }
}
