package com.sonofmagic.weapp.container.sdk

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Camera2 luma frames decoded by ZXing. No vendor scanner application or fake result. */
internal class AndroidCodeScanner(private val activity: Activity, types: Set<String>, private val completion: (Result<JSONObject>) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("weapp-code-scanner").apply { start() }
    private val cameraQueue = Handler(thread.looper)
    private val closing = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)
    private val drained = CountDownLatch(1)
    private var dialog: Dialog? = null
    private var opening = false
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var images: ImageReader? = null
    private var preview: Surface? = null
    private val decoder = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to buildList {
        if ("qrCode" in types) add(BarcodeFormat.QR_CODE)
        if ("pdf417" in types) add(BarcodeFormat.PDF_417)
        if ("datamatrix" in types) add(BarcodeFormat.DATA_MATRIX)
        if ("barCode" in types) addAll(listOf(BarcodeFormat.AZTEC, BarcodeFormat.CODABAR, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.CODE_128, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13, BarcodeFormat.ITF, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.RSS_14, BarcodeFormat.RSS_EXPANDED))
    }, DecodeHintType.TRY_HARDER to true)) }
    private val timeout = Runnable { finish(Result.failure(IllegalStateException("Camera scan timed out"))) }
    fun show() {
        check(Looper.myLooper() == Looper.getMainLooper() && !closing.get())
        val texture = TextureView(activity)
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(texture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)); addView(Button(activity).apply { text = "取消扫码"; setOnClickListener { finish(Result.failure(CancellationException("User cancelled camera scan"))) } }) }
        dialog = Dialog(activity).apply { setTitle("扫描二维码或条形码"); setContentView(layout); setOnCancelListener { finish(Result.failure(CancellationException("User cancelled camera scan"))) }; show(); window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { cameraQueue.post { runCatching { open(surface) }.onFailure { finish(Result.failure(it)) } } }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { if (!closing.get()) finish(Result.failure(CancellationException("Camera preview detached"))); return true }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        main.postDelayed(timeout, 120000)
    }
    @Suppress("MissingPermission", "DEPRECATION") private fun open(texture: SurfaceTexture) {
        if (closing.get()) { finishRetirement(); return }
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: manager.cameraIdList.firstOrNull() ?: error("Camera unavailable")
        val map = checkNotNull(manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java).toSet()
        val size = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width * it.height in 76800..921600 && it in previewSizes }.maxByOrNull { it.width * it.height } ?: error("No bounded camera preview and YUV frame size")
        texture.setDefaultBufferSize(size.width, size.height); preview = Surface(texture)
        images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { reader -> reader.setOnImageAvailableListener({ source ->
            if (closing.get()) return@setOnImageAvailableListener
            val captured = try { source.acquireLatestImage() } catch (error: IllegalStateException) { if (!closing.get()) finish(Result.failure(error)); null }
            captured?.use { image ->
                if (closing.get() || delivered.get()) return@use
                try {
                    val plane = image.planes[0]; val sourceBytes = plane.buffer; val base = sourceBytes.position(); val luma = ByteArray(image.width * image.height)
                    for (y in 0 until image.height) for (x in 0 until image.width) luma[y * image.width + x] = sourceBytes.get(base + y * plane.rowStride + x * plane.pixelStride)
                    val luminance = PlanarYUVLuminanceSource(luma, image.width, image.height, 0, 0, image.width, image.height, false)
                    val result = decoder.decodeWithState(BinaryBitmap(HybridBinarizer(luminance)))
                    require(result.text.toByteArray().size <= 65536) { "Decoded result exceeds 64 KiB" }
                    val value = JSONObject().put("result", result.text).put("scanType", result.barcodeFormat.name).put("charSet", "").put("path", "").put("rawData", android.util.Base64.encodeToString(result.rawBytes ?: result.text.toByteArray(), android.util.Base64.NO_WRAP))
                    finish(Result.success(value))
                } catch (_: NotFoundException) {} catch (_: com.google.zxing.ChecksumException) {} catch (_: com.google.zxing.FormatException) {} catch (error: Throwable) { finish(Result.failure(error)) } finally { decoder.reset() }
            }
        }, cameraQueue) }
        opening = true
        try { manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                opening = false; camera = device
                if (closing.get()) { device.close(); return }
                val surfaces = listOf(checkNotNull(preview), checkNotNull(images).surface)
                runCatching { device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (closing.get()) { session.close(); return }; capture = session
                        runCatching { val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { surfaces.forEach { addTarget(it) }; set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) }.build(); session.setRepeatingRequest(request, null, cameraQueue) }.onFailure { finish(Result.failure(it)) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { session.close(); finish(Result.failure(IllegalStateException("Camera scan configuration failed"))) }
                }, cameraQueue) }.onFailure { finish(Result.failure(it)) }
            }
            override fun onDisconnected(device: CameraDevice) { opening = false; camera = device; device.close(); finish(Result.failure(IllegalStateException("Camera disconnected"))) }
            override fun onError(device: CameraDevice, error: Int) { opening = false; camera = device; device.close(); finish(Result.failure(IllegalStateException("Camera scan error $error"))) }
            override fun onClosed(device: CameraDevice) { if (camera === device) camera = null; if (closing.get()) finishRetirement() }
        }, cameraQueue) } catch (error: Throwable) { opening = false; throw error }
    }
    private fun finish(result: Result<JSONObject>) { if (delivered.compareAndSet(false, true)) main.post { if (!closing.get()) completion(result) } }
    private fun finishRetirement() {
        if (!closing.get() || opening || camera != null) return
        capture?.close(); capture = null; images?.close(); images = null; preview?.release(); preview = null
        drained.countDown(); thread.quitSafely()
    }
    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        main.removeCallbacks(timeout)
        if (closing.compareAndSet(false, true)) {
            cameraQueue.post { runCatching { capture?.stopRepeating() }; capture?.close(); capture = null; camera?.close(); finishRetirement() }
        }
        check(drained.await(5, TimeUnit.SECONDS)) { "Camera scanner has not completed native close" }
        thread.join(1000); check(!thread.isAlive) { "Camera scanner thread has not retired" }
        dialog?.dismiss(); dialog = null
    }
}
