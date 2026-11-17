package com.sonofmagic.weapp.container.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** An actual MediaRecorder per session, restricted to bounded AAC recordings in managed storage. */
class AndroidRecorderApis(
    activity: Activity,
    private val files: AndroidManagedFiles,
    private val permissions: AndroidPermissionBroker? = null,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var binding: Activity? = activity
    private val activity: Activity get() = checkNotNull(binding) { "Recorder Activity detached" }
    private val application = activity.application
    private val consent = AndroidAppPermissionConsent({ activity }, { files.auxiliaryRoot(it) })
    private var session: WeappContainerSession? = null
    private var closed = false
    private var foreground = true
    private var sessionVisible = true
    private var generation = 0L
    private class PendingStart(val call: HostCall, val reply: HostReply, val objectId: String, val generation: Long)
    private var pendingStart: PendingStart? = null
    private val objects = mutableMapOf<String, HostCall>()
    private data class Subscription(val call: HostCall, val objectId: String, val event: String, var sequence: Long = 0)
    private val subscriptions = mutableMapOf<String, Subscription>()
    private data class Recording(val objectId: String, val owner: HostCall, val recorder: MediaRecorder, val file: File, val started: Long, val sampleRate: Int, val channels: Int, var paused: Long? = null, var pausedMs: Long = 0,
        var stopped: Boolean = false, var released: Boolean = false, var duration: Long = 0, var importedPath: String? = null, var published: Boolean = false, var stopFailure: RuntimeException? = null)
    private var recording: Recording? = null
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStopped(owner: Activity) { if (owner === binding) { foreground = false; cancelPending(); if (recording != null) runCatching { stop(true) }.onFailure { emitError(it) } } }
        override fun onActivityDestroyed(owner: Activity) { if (owner === binding) runCatching { reset() }.onFailure { android.util.Log.e("WeappContainer", "Recorder cleanup remains pending after Activity destruction", it) } }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) { if (a === binding) foreground = true }
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    }
    val supportedApis = setOf("wx.getRecorderManager", "object:RecorderManager.start", "object:RecorderManager.stop", "object:RecorderManager.pause", "object:RecorderManager.resume",
        "object:RecorderManager.onStart", "object:RecorderManager.onStop", "object:RecorderManager.onPause", "object:RecorderManager.onResume", "object:RecorderManager.onError", "object:RecorderManager.onInterruptionBegin")
    fun install(target: WeappContainerSession): CompletableFuture<Void> {
        check(session == null); session = target; application.registerActivityLifecycleCallbacks(lifecycle)
        return CompletableFuture.allOf(target.registerHostResourceCleanup("recorder-${UUID.randomUUID()}") { reset() }, target.registerHostVisibilityHandler("recorder-${UUID.randomUUID()}") { visible -> sessionVisible = visible; if (!visible) { cancelPending(); if (recording != null) runCatching { stop(true) }.onFailure(::emitError) } }, *supportedApis.map { api -> target.registerApiHandler(api) { call, reply ->
            try { check(!closed && !call.isCancelled); invoke(api, call, reply) } catch (error: Throwable) { reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Recorder failed", false, JSONObject().put("errMsg", "${api.substringAfterLast('.')}:fail ${error.message}").put("errCode", -1)) }
        } }.toTypedArray())
    }
    fun rebindActivity(activity: Activity?) { if (binding !== activity) { reset(); binding = activity; foreground = activity != null } }
    private fun cancelPending() { generation++; val pending = pendingStart; pendingStart = null; pending?.reply?.reject(HostErrorCode.CANCELLED, "Recorder start owner left the foreground"); consent.reset() }
    private fun sameOwner(a: HostCall, b: HostCall): Boolean = a.context?.let { x -> b.context?.let { y -> x.getString("sessionId") == y.getString("sessionId") && x.getJSONObject("application").toString() == y.getJSONObject("application").toString() && x.getJSONObject("account").toString() == y.getJSONObject("account").toString() } } == true
    private fun ok(api: String) = JSONObject().put("errMsg", "${api.substringAfterLast('.')}:ok")
    private fun emit(objectId: String, event: String, value: JSONObject = JSONObject()) {
        for ((id, subscription) in subscriptions.toMap()) if (subscription.objectId == objectId && subscription.event == event && !subscription.call.isCancelled) {
            val context = requireNotNull(subscription.call.context)
            session?.emitHostApiEvent(JSONObject().put("sessionId", context.getString("sessionId")).put("accountEpoch", context.getJSONObject("account").get("epoch")).put("subscriptionId", id).put("objectId", objectId).put("sequence", (++subscription.sequence).toString()).put("event", event).put("value", value))
        }
    }
    private fun emitError(error: Throwable) { recording?.let { emit(it.objectId, "Error", JSONObject().put("errMsg", "RecorderManager:fail ${error.message}").put("errCode", -1)) }; runCatching { stop(false) } }
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        val value = call.payload as? JSONObject ?: JSONObject(); val id = value.getString("objectId")
        if (api == "wx.getRecorderManager") { require(objects.size < 16); objects[id] = call; reply.retainUntilCancelled(); call.onCancel = { if (recording?.objectId == id) stop(false); objects.remove(id) }; reply.resolve(ok(api).put("objectId", id)); return }
        require(objects[id]?.let { sameOwner(it, call) } == true) { "Recorder belongs to a retired owner" }
        if (api.contains(".on")) {
            val subscription = value.getString("subscriptionId")
            if (value.optString("action") == "unsubscribe") subscriptions.remove(subscription)
            else { require(subscriptions.size < 128); subscriptions[subscription] = Subscription(call, id, api.substringAfterLast('.').removePrefix("on")); reply.retainUntilCancelled(); call.onCancel = { subscriptions.remove(subscription) } }
            reply.resolve(ok(api)); return
        }
        when {
            api.endsWith(".start") -> {
                require(recording == null && pendingStart == null && foreground && sessionVisible && binding != null) { "Recorder is active, starting or outside the foreground" }
                val pending = PendingStart(call, reply, id, generation); pendingStart = pending
                call.addCancellationHandler { if (pendingStart === pending) pendingStart = null }
                files.prepareScope(requireNotNull(call.context)).whenComplete { _, error ->
                    if (call.isCancelled || pendingStart !== pending || pending.generation != generation) return@whenComplete
                    if (error != null) { pendingStart = null; reply.reject(HostErrorCode.PERMISSION_DENIED, error.message ?: "Recorder scope unavailable"); return@whenComplete }
                    consent.request("scope.record", call) { granted ->
                        fun begin(allowed: Boolean) { main.post { if (!call.isCancelled && pendingStart === pending && pending.generation == generation) {
                            pendingStart = null
                            if (!foreground || !sessionVisible || binding == null) { reply.reject(HostErrorCode.CANCELLED, "Recorder start owner left foreground"); return@post }
                            if (!granted || !allowed || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) reply.reject(HostErrorCode.PERMISSION_DENIED, "Microphone permission denied")
                            else try { start(id, objects.getValue(id), value); reply.resolve(ok(api)) } catch (failure: Throwable) { reply.reject(HostErrorCode.INVALID_REQUEST, failure.message ?: "Recorder start failed"); emit(id, "Error", JSONObject().put("errMsg", "RecorderManager:fail ${failure.message}").put("errCode", -1)) }
                        } } }
                        if (!granted || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin(granted)
                        else permissions?.request(arrayOf(Manifest.permission.RECORD_AUDIO), call, ::begin) ?: begin(false)
                    }
                    call.addCancellationHandler { if (pendingStart === pending) pendingStart = null }
                }; return
            }
            api.endsWith(".stop") -> { require(recording?.objectId == id); stop(true) }
            api.endsWith(".pause") -> { val active = requireNotNull(recording); require(active.objectId == id && active.paused == null); active.recorder.pause(); active.paused = SystemClock.elapsedRealtime(); emit(id, "Pause") }
            api.endsWith(".resume") -> { val active = requireNotNull(recording); require(active.objectId == id); val since = requireNotNull(active.paused); active.recorder.resume(); active.pausedMs += SystemClock.elapsedRealtime() - since; active.paused = null; emit(id, "Resume") }
        }
        reply.resolve(ok(api))
    }
    @Suppress("DEPRECATION") private fun start(id: String, owner: HostCall, value: JSONObject) {
        require(recording == null && !closed && !owner.isCancelled && foreground && sessionVisible && binding != null)
        require(value.optString("format", "aac") == "aac") { "This recorder supports AAC only" }
        require(value.optString("audioSource", "auto") in setOf("auto", "mic")) { "This recorder supports auto/mic audioSource only" }
        require(value.optString("audioMode", "default") == "default") { "This recorder supports default audioMode only" }
        require(!value.has("frameSize")) { "AAC frame callbacks are not supported" }
        fun integer(key: String, fallback: Int): Int { val raw = value.opt(key) ?: return fallback; require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toInt().toDouble()) { "$key must be an integer" }; return raw.toInt() }
        val rate = integer("sampleRate", 8000)
        val channels = integer("numberOfChannels", 2); require(channels in 1..2)
        val bitrate = integer("encodeBitRate", 48000)
        val bitrateRange = when (rate) { 8000, 11025 -> 16000..48000; 12000 -> 24000..64000; 16000 -> 24000..96000; 22050, 24000 -> 32000..128000; 32000 -> 48000..192000; 44100, 48000 -> 64000..320000; else -> error("Unsupported recorder sampleRate") }
        require(bitrate in bitrateRange) { "encodeBitRate is outside the range for sampleRate" }
        val duration = integer("duration", 60000); require(duration in 100..60000)
        val file = File(files.auxiliaryRoot(requireNotNull(owner.context)), "recording-${UUID.randomUUID()}.aac")
        val recorder = MediaRecorder()
        recording = Recording(id, owner, recorder, file, SystemClock.elapsedRealtime(), rate, channels)
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC); recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS); recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(rate); recorder.setAudioChannels(channels); recorder.setAudioEncodingBitRate(bitrate); recorder.setMaxDuration(duration); recorder.setMaxFileSize(4L * 1048576); recorder.setOutputFile(file.path)
            recorder.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) main.post { if (recording?.recorder === recorder) runCatching { stop(true) }.onFailure(::emitError) } }
            recorder.setOnErrorListener { _, what, extra -> main.post { if (recording?.recorder === recorder) { emit(id, "InterruptionBegin"); emitError(IllegalStateException("MediaRecorder error $what/$extra")) } } }
            recorder.prepare(); recorder.start(); emit(id, "Start")
        } catch (error: Throwable) { try { stop(false) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }; throw error }
    }
    private fun stop(publish: Boolean) {
        val active = recording ?: return
        if (!active.stopped) {
            active.duration = (active.paused ?: SystemClock.elapsedRealtime()) - active.started - active.pausedMs
            try { active.recorder.stop() } catch (error: RuntimeException) { active.stopFailure = error } finally { active.stopped = true }
        }
        if (!active.released) { active.recorder.release(); active.released = true }
        if (publish && active.stopFailure != null) {
            val error = checkNotNull(active.stopFailure)
            check(!active.file.exists() || active.file.delete()) { "Cannot remove failed recorder staging file" }; recording = null
            emit(active.objectId, "Error", JSONObject().put("errMsg", "RecorderManager:fail ${error.message}").put("errCode", -1)); throw error
        }
        if (publish && !active.published && !active.owner.isCancelled && !closed) {
            require(active.file.length() in 1..(4L * 1048576))
            val extractor = android.media.MediaExtractor()
            val mismatch = try {
                extractor.setDataSource(active.file.path)
                check(extractor.trackCount > 0) { "Recorder produced no audio track" }
                val track = extractor.getTrackFormat(0)
                val rate = track.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                val channels = track.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                if (rate != active.sampleRate || channels != active.channels) IllegalStateException("Native encoder produced $rate Hz / $channels channels; requested ${active.sampleRate} Hz / ${active.channels} channels") else null
            } finally { extractor.release() }
            if (mismatch != null) {
                active.stopFailure = mismatch
                check(!active.file.exists() || active.file.delete()) { "Cannot remove rejected recorder staging file" }; recording = null
                emit(active.objectId, "Error", JSONObject().put("errMsg", "RecorderManager:fail ${mismatch.message}").put("errCode", -1)); throw mismatch
            }
            val path = active.importedPath ?: files.importTemporary(active.file.readBytes(), "aac", requireNotNull(active.owner.context)).also { active.importedPath = it }
            emit(active.objectId, "Stop", JSONObject().put("tempFilePath", path).put("duration", active.duration).put("fileSize", active.file.length())); active.published = true
        }
        check(!active.file.exists() || active.file.delete()) { "Cannot remove private recorder staging file" }; recording = null
    }
    private fun reset() { cancelPending(); stop(false); objects.clear(); subscriptions.clear() }
    override fun close() { if (closed) return; reset(); closed = true; application.unregisterActivityLifecycleCallbacks(lifecycle); binding = null }
}
