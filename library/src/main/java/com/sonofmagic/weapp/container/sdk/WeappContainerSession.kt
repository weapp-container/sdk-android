package com.sonofmagic.weapp.container.sdk

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture

/**
 * A persistent mini-program instance. Native creation, JavaScript and EGL all
 * belong to a dedicated HandlerThread. View detach preserves this instance.
 * Call closeAsync when the owning feature ends; never block Android's main thread.
 */
class WeappContainerSession internal constructor(
    context: Context,
    val storageKey: String?,
    initialMetrics: WindowMetrics?,
    atomicHostLayout: Boolean = false,
    presenterFactory: (() -> Long) -> EglPresenter,
) {
    @JvmOverloads constructor(context: Context, storageKey: String?, initialMetrics: WindowMetrics? = null, gpuRequired: Boolean? = null, devDiagnostics: Boolean = false, atomicHostLayout: Boolean = false) :
        this(context, storageKey, initialMetrics.also { require(gpuRequired != false) { "Android native preview requires GPU" } }, atomicHostLayout, { EglPresenter(handle = it) }) {
        requiredGpu = gpuRequired
        diagnosticsEnabled = devDiagnostics
    }
    private var requiredGpu: Boolean? = null
    private var diagnosticsEnabled = false
    private val atomicHostLayout = atomicHostLayout
    private var hostSurfaceIdentity: JSONObject? = null
    // Validation must precede allocating a thread or any native resources.
    private val app = context.applicationContext.also {
        require(storageKey == null || storageKey.isNotBlank()) { "storageKey must be stable and nonblank; null explicitly selects memory storage" }
    }
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("WeappContainer").apply { start() }
    private val worker = Handler(thread.looper)
    private var handle = 0L
    private val presenter = presenterFactory { handle }
    private var presenterAttachmentFailed = false
    private var metrics = initialMetrics
    private var physicalWidth = 0
    private var physicalHeight = 0
    private var surface: Surface? = null
    private var surfaceToken: Any? = null
    private val retirementCallbacks = mutableMapOf<Any, MutableList<() -> Unit>>()
    private var retiringToken: Any? = null
    private var retirementAttempts = 0
    private var loaded = false
    private var desiredForeground = true
    private var viewVisible = true
    private var foreground = true
    private var previewOccluded = false
    private var hostChrome: JSONObject? = null
    private var frameRevision = 0L
    private var presentedRevision = -1L
    private var closing = false
    private var cleanupNeedsRetry = false
    @Volatile private var closed = false
    private var closeFuture: CompletableFuture<Unit>? = null
    private val flushes = mutableMapOf<Long, CompletableFuture<Unit>>()
    private val methods = linkedMapOf<String, HostMethod>()
    private val calls = mutableMapOf<String, HostCall>()
    private val pendingLoads = ArrayDeque<PendingLoad>()
    private val pendingPrepares = ArrayDeque<CompletableFuture<JSONObject>>()
    private var boundView: WeakReference<WeappContainerView>? = null // main thread only
    @Volatile var eventListener: ContainerEventListener? = null

    private data class PendingLoad(val source: PackSource, val route: String?, val options: JSONObject, val initialHostChrome: JSONObject?, val initialHostLayout: JSONObject?, val result: CompletableFuture<Unit>)

    /** May be queued before View layout; no module executes until real metrics exist. */
    @JvmOverloads fun load(source: PackSource, options: LoadOptions = LoadOptions()): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>(); val copied = JSONObject(options.options.toString())
        enqueue(result) {
            ensureOpen()
            pendingLoads.add(PendingLoad(source, options.route, copied, options.initialHostChrome?.let { JSONObject(it.toString()) }, options.initialHostLayout?.let { JSONObject(it.toString()) }, result))
            drainLoads()
        }
        return result
    }
    /** Verify and classify before releasing GPU ownership. The runtime repeats
     * validation when applying, so classification is never an authorization. */
    @JvmOverloads fun applyDevPack(source: PackSource, options: LoadOptions = LoadOptions()): CompletableFuture<JSONObject> {
        val copied = JSONObject(options.options.toString())
        return task {
            ensureLoaded()
            source.withDirectory(app) { directory ->
                val payload = JSONObject().put("packPath", directory.absolutePath).put("options", copied)
                options.initialHostChrome?.let { payload.put("initialHostChrome", JSONObject(it.toString())) }
                options.initialHostLayout?.let { payload.put("initialHostLayout", JSONObject(it.toString())) }
                options.route?.let { payload.put("route", it) }
                val classification = command("classifyDevPack", payload) as JSONObject
                val kind = classification.getString("reloadKind")
                require(kind == "inPlace" || kind == "restart") { "Invalid development reload classification" }
                val restart = kind == "restart"
                if (restart) {
                    presenterAttachmentFailed = false
                    presenter.detachRuntime()
                    presentedRevision = -1
                }
                try {
                    val result = command("applyDevPack", payload) as JSONObject
                    if(restart) retainAcceptedChrome()
                    presentedRevision = -1
                    result
                } finally {
                    try {
                        if (restart && loaded) { applyForeground(); ensurePresenter() }
                    } finally { drainEvents() }
                    present()
                }
            }
        }
    }
    /** Versioned SDK transport. IDs stay decimal strings; SDK journal receipts,
     * not frame changes, decide whether a throwing GPU wrapper committed. */
    @JvmOverloads fun executeCommand(operation: String, payload: JSONObject = JSONObject()): CompletableFuture<Any?> {
        val copied = JSONObject(payload.toString())
        return task {
            ensureOpen()
            if (operation == "queryDevPackAttempt") {
                // Durable journal observation must not create a runtime, pump
                // JS, drain/replay events, change loaded or touch presentation.
                if (handle == 0L) throw ContainerException("ERR_NOT_READY", "A native owner is required for journal observation")
                return@task command(operation, copied)
            }
            if (handle == 0L) createNative(metrics ?: throw ContainerException("ERR_NOT_READY", "A measured native view is required"))
            if (operation == "applyDevPackAttempt") {
                val identity = JSONObject().put("ownerId", copied.getString("ownerId")).put("attemptId", copied.getString("attemptId"))
                val receipt = command("queryDevPackAttempt", identity) as JSONObject
                val restart = receipt.optString("status") == "prepared" && receipt.optString("reloadKind") == "restart"
                if (restart) {
                    presenterAttachmentFailed = false
                    presenter.detachRuntime(); presentedRevision = -1
                }
                try {
                    val result = command(operation, copied) as JSONObject
                    if (result.optString("status") == "accepted") { loaded = true; presenterAttachmentFailed = false; presentedRevision = -1 }
                    result
                } catch (error: Throwable) {
                    val retained = try { command("queryDevPackAttempt", identity) as? JSONObject } catch (_: Throwable) { null }
                    if (retained?.optString("status") == "accepted") { loaded = true; presenterAttachmentFailed = false; presentedRevision = -1 }
                    throw error
                } finally {
                    try { if (loaded) { applyForeground(); ensurePresenter() } } finally { drainEvents() }
                    present()
                }
            } else {
                try {
                    command(operation, copied)
                } finally { drainEvents(); presentedRevision = -1; present() }
            }
        }
    }
    /** One passive owner-thread metadata snapshot for prepare-only geometry.
     * The caller validates the journal receipt against the same native state;
     * querying cannot run a damaged View, grant input or acknowledge a frame. */
    @JvmOverloads fun hostLayoutPreparationState(restartAttempt: JSONObject? = null): CompletableFuture<JSONObject> {
        val copied = restartAttempt?.let { JSONObject(it.toString()) }
        return task {
            ensureOpen()
            if (handle == 0L) throw ContainerException("ERR_NOT_READY", "A native owner is required for layout preparation")
            val receipt = copied?.let {
                val identity = JSONObject().put("ownerId", it.getString("ownerId")).put("attemptId", it.getString("attemptId"))
                command("queryDevPackAttempt", identity)
            }
            JSONObject().put("state", command("state")).put("journalReceipt", receipt ?: JSONObject.NULL)
        }
    }
    /** Same-size native observations also require a complete shell response. */
    fun requestHostLayoutObservation(): CompletableFuture<JSONObject> = task {
        ensureOpen()
        if (handle == 0L) createNative(metrics ?: throw ContainerException("ERR_NOT_READY", "A measured native view is required"))
        observeAtomicLayout()
        (command("state") as JSONObject).getJSONObject("pendingHostLayout")
    }

    fun devState(): CompletableFuture<JSONObject> = task { ensureLoaded(); command("devState") as JSONObject }
    fun clearDevStorage(): CompletableFuture<Unit> = task { ensureLoaded(); command("clearDevStorage"); Unit }.thenCompose { flushStorage() }

    fun registerHostMethod(name: String, method: HostMethod): CompletableFuture<Unit> = task {
        ensureOpen(); check(!loaded && pendingLoads.isEmpty()) { "Register host methods before load" }
        require(name.isNotBlank() && !name.startsWith("wx."))
        check(!methods.containsKey(name)) { "Host method already registered: $name" }
        if (handle != 0L) command("registerHostMethod", JSONObject().put("name", name))
        methods[name] = method
    }
    @JvmOverloads fun navigate(route: String, query: JSONObject = JSONObject(), replace: Boolean = false): CompletableFuture<Unit> {
        val payload = JSONObject().put("route", route).put("query", JSONObject(query.toString())).put("replace", replace)
        return operation("navigate", payload)
    }
    fun back(): CompletableFuture<Unit> = operation("back")
    fun switchTab(route: String): CompletableFuture<Unit> = operation("switchTab", JSONObject().put("route", route))
    @JvmOverloads fun inspectPackChrome(source: PackSource, route: String? = null): CompletableFuture<JSONObject> = task {
        ensureOpen()
        source.withDirectory(app) { directory ->
            val temporary = NativeBridge.create(JSONObject().put("engine", "quickjs").toString())
            try {
                val payload = JSONObject().put("packPath", directory.absolutePath)
                route?.let { payload.put("route", it) }
                unwrap(NativeBridge.command(temporary, "inspectPackChrome", payload.toString())) as JSONObject
            } finally { unwrap(NativeBridge.destroy(temporary)) }
        }
    }
    /** Store a validated measured layout before load; guest code sees it from launch. */
    fun updateHostChrome(value: JSONObject): CompletableFuture<Unit> {
        val copied = JSONObject(value.toString())
        return task {
            ensureOpen()
            if (atomicHostLayout) throw ContainerException("ERR_UNSUPPORTED_CAPABILITY", "Atomic host layout requires a complete hostLayout")
            if (handle != 0L) command("hostChrome", copied)
            else {
                val temporary = NativeBridge.create(JSONObject().put("engine", "quickjs").toString())
                try { unwrap(NativeBridge.command(temporary, "hostChrome", copied.toString())) }
                finally { unwrap(NativeBridge.destroy(temporary)) }
            }
            hostChrome = copied
            drainEvents(); presentedRevision = -1; present()
        }
    }
    /** Developer UI coverage never synthesizes App.onHide or stops timers. */
    fun setPreviewOccluded(value: Boolean): CompletableFuture<Unit> = task {
        ensureOpen()
        if (handle != 0L) command("setPreviewOccluded", JSONObject().put("occluded", value))
        val wasOccluded = previewOccluded
        previewOccluded = value
        if (!value && wasOccluded && loaded) { presenterAttachmentFailed = false; ensurePresenter() }
        presentedRevision = -1
        drainEvents(); present()
    }
    fun sendMessage(message: Any?): CompletableFuture<Unit> = operation("sendMessage", JSONObject().put("message", jsonCopy(message)))
    fun state(): CompletableFuture<JSONObject> = task { ensureOpen(); command("state") as JSONObject }
    fun capabilities(): CompletableFuture<JSONObject> = task { ensureOpen(); command("capabilities") as JSONObject }
    /** Measured-view preflight creates an empty native runtime, never App/Page JS. */
    fun prepareView(): CompletableFuture<JSONObject> {
        val result = CompletableFuture<JSONObject>()
        enqueue(result) {
            ensureOpen()
            if (pendingPrepares.size >= 8) throw ContainerException("ERR_BUSY", "Too many measured-view preparations")
            pendingPrepares.add(result)
            drainLoads()
        }
        return result
    }
    fun snapshot(): CompletableFuture<ContainerSnapshot> = task { ensureLoaded(); ContainerSnapshot.decode(NativeBridge.snapshot(handle)) }
    fun setForeground(visible: Boolean): CompletableFuture<Unit> {
        if (!visible) return task { ensureOpen(); desiredForeground = false; applyForeground() }
        val result = CompletableFuture<Unit>()
        main.post {
            val view = boundView?.get()
            if (view == null) enqueue(result) { ensureOpen(); desiredForeground = true; applyForeground(); finish(result, Unit) }
            else view.awaitObservation { observation -> enqueue(result) {
                ensureOpen()
                if (observation == null) throw ContainerException("ERR_VIEW_DETACHED", "View detached before foreground observation")
                acceptMetrics(observation); desiredForeground = true; applyForeground(); finish(result, Unit)
            } }
        }
        return result
    }
    /** Required for a headless session; a bound View supplies its own observations. */
    fun setWindowMetrics(observation: WindowMetrics): CompletableFuture<Unit> = task { ensureOpen(); acceptMetrics(observation); drainLoads() }
    fun flushStorage(): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        enqueue(result) {
            ensureOpen(); ensureLoaded()
            val value = command("flushStorage") as JSONObject
            if (value.optBoolean("pending")) flushes[value.getLong("ticket")] = result else finish(result, Unit)
            drainEvents()
        }
        return result
    }
    fun closeAsync(): CompletableFuture<Unit> {
        synchronized(this) {
            closeFuture?.let { return it }
            val result = CompletableFuture<Unit>(); closeFuture = result
            // Resolve UI-side metric waiters before waiting on worker shutdown.
            main.post { boundView?.get()?.cancelObservationWaits() }
            enqueue(result) {
                if (closed) { finish(result, Unit); return@enqueue }
                closing = true; cleanupNeedsRetry = false
                while (pendingPrepares.isNotEmpty()) fail(pendingPrepares.removeFirst(), ContainerException("ERR_CLOSED", "Session closed before measured-view preparation"))
                while (pendingLoads.isNotEmpty()) fail(pendingLoads.removeFirst().result, ContainerException("ERR_CLOSED", "Session closed before layout/load"))
                try {
                    retireSurface()
                    if (handle == 0L) { completeClose(null); return@enqueue }
                    val state = command("close") as JSONObject
                    drainEvents()
                    // Retried final cleanup may find an already-closed native
                    // session; that response does not enqueue a second event.
                    if (!closed && !state.optBoolean("pending") && state.optString("status") == "closed") completeClose(null)
                    schedulePump()
                } catch (error: Throwable) { completeClose(error) }
            }
            return result
        }
    }

    internal fun claim(view: WeappContainerView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && closeFuture == null) { "Session is closing or closed" }
        val current = boundView?.get()
        check(current == null || current === view) { "A session can be bound to only one WeappContainerView" }
        boundView = WeakReference(view)
    }
    internal fun release(view: WeappContainerView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (boundView?.get() === view) boundView = null
    }
    internal fun attachSurface(token: Any, ownedSurface: Surface, width: Int, height: Int, observation: WindowMetrics, visible: Boolean) {
        if (closed || !worker.post {
            if (closing || closed) { ownedSurface.release(); return@post }
            try {
                retireSurface()
                surface = ownedSurface; surfaceToken = token; physicalWidth = width; physicalHeight = height; viewVisible = visible
                acceptMetrics(observation); drainLoads()
                applyForeground(); ensurePresenter(); present()
            } catch (error: Throwable) { if (surface !== ownedSurface) ownedSurface.release(); emitError(error) }
        }) ownedSurface.release()
    }
    internal fun observeSurface(token: Any, width: Int, height: Int, observation: WindowMetrics, visible: Boolean) {
        if (closed) return
        worker.post {
            if (surfaceToken !== token || closing) return@post
            try {
                physicalWidth = width; physicalHeight = height; viewVisible = visible
                acceptMetrics(observation); drainLoads(); applyForeground(); ensurePresenter(); present()
            } catch (error: Throwable) { emitError(error) }
        }
    }
    internal fun detachSurface(token: Any, after: (() -> Unit)? = null) {
        if (closed) { after?.invoke(); return }
        if (!worker.post {
            if (surfaceToken !== token) { after?.invoke(); return@post }
            after?.let { retirementCallbacks.getOrPut(token) { mutableListOf() }.add(it) }
            retiringToken = token; retirementAttempts = 0
            attemptRetirement()
        }) emitError(ContainerException("ERR_THREAD", "Owner thread unavailable; Surface retirement remains unconfirmed"))
    }
    private fun retireSurface() {
        try { presenter.close() } finally {
            // Never return a SurfaceTexture while native/EGL ownership remains.
            // Failed deletion keeps its handles in EglPresenter for a retry.
            if (presenter.isRetired) {
                presenterAttachmentFailed = false
                val retired = surfaceToken
                if (hostSurfaceIdentity != null && handle != 0L && !closing) {
                    command("hostSurface", JSONObject(hostSurfaceIdentity.toString()).put("action", "detach"))
                }
                hostSurfaceIdentity = null
                surface?.release(); surface = null; surfaceToken = null; presentedRevision = -1
                retiringToken = null
                retired?.let { retirementCallbacks.remove(it)?.forEach { callback -> callback() } }
            }
        }
    }
    private fun attemptRetirement() {
        if (retiringToken == null) return
        try {
            retireSurface(); viewVisible = false; applyForeground()
        } catch (error: Throwable) {
            emitError(error)
            // Bounded automatic recovery; retained resources are also retried
            // by closeAsync or the next View attachment after these attempts.
            if (retiringToken != null && ++retirementAttempts < 3) worker.postDelayed({ attemptRetirement() }, 32)
        }
    }
    internal fun input(events: JSONArray) {
        if (closed) return
        worker.post {
            if (!loaded || closing || !foreground || previewOccluded) return@post
            try { command("input", JSONObject().put("events", events)); drainEvents(); present() } catch (error: Throwable) { emitError(error) }
        }
    }

    private fun acceptMetrics(observation: WindowMetrics) {
        if (!atomicHostLayout && metrics == observation) return
        metrics = observation
        if (atomicHostLayout) {
            if (handle == 0L) createNative(observation) else observeAtomicLayout()
            drainEvents(); presentedRevision = -1
        } else if (loaded) {
            // Native metrics commit precedes resize hooks; keep the observation
            // even if a user resize hook throws after that commit.
            try { command("windowMetrics", observation.json()) } finally { drainEvents(); presentedRevision = -1 }
        }
    }
    private fun createNative(observation: WindowMetrics) {
        val host = JSONObject().put("kind", "android").put("platform", "android").put("version", Build.VERSION.RELEASE)
            .put("user_agent", "WeappContainerAndroid/0.1.0-preview.5.5").put("windowWidth", observation.windowWidth)
            .put("windowHeight", observation.windowHeight).put("displayMetrics", observation.displayJson())
        val config = JSONObject().put("engine", "quickjs").put("host", host)
        requiredGpu?.let { config.put("gpuRequired", it) }
        if (diagnosticsEnabled) config.put("devDiagnostics", true)
        if (atomicHostLayout) config.put("hostLayout", JSONObject().put("protocolVersion", "1.0").put("atomic", true))
        if (storageKey != null) config.put("storageKey", storageKey).put("storageRoot", File(app.filesDir, "weapp-container/storage").absolutePath)
        handle = NativeBridge.create(config.toString())
        if (atomicHostLayout) observeAtomicLayout() else hostChrome?.let { command("hostChrome", it) }
        if (previewOccluded) command("setPreviewOccluded", JSONObject().put("occluded", true))
        for (name in methods.keys) command("registerHostMethod", JSONObject().put("name", name))
        schedulePump()
    }
    private fun observeAtomicLayout() {
        if (!atomicHostLayout || handle == 0L) return
        val observation = metrics ?: return
        if (hostSurfaceIdentity == null) {
            hostSurfaceIdentity = command("hostSurface", JSONObject().put("action", "attach")) as JSONObject
        }
        val identity = checkNotNull(hostSurfaceIdentity)
        command("observeHostLayout", JSONObject(identity.toString()).put("schemaVersion", 1).put("windowMetrics", observation.json()))
    }
    private fun drainLoads() {
        val observation = metrics ?: return
        while (pendingPrepares.isNotEmpty() && !closing) {
            val pending = pendingPrepares.removeFirst()
            try { if (handle == 0L) createNative(observation); finish(pending, command("capabilities") as JSONObject) }
            catch (error: Throwable) { fail(pending, error) }
        }
        while (pendingLoads.isNotEmpty() && !closing) {
            val pending = pendingLoads.removeFirst()
            try {
                // A staged view may have resized since capability preflight.
                // Initialize guest JS with the latest measured metrics, never
                // with the empty preflight runtime's earlier configuration.
                if (!loaded && handle != 0L && !atomicHostLayout) { unwrap(NativeBridge.destroy(handle)); handle = 0L }
                if (handle == 0L) createNative(observation)
                presenterAttachmentFailed = false // Explicit load begins a new attachment attempt.
                presenter.detachRuntime()
                presentedRevision = -1 // Replaying the preserved frame is required even when replacement fails.
                try {
                    pending.source.withDirectory(app) { directory ->
                        val payload = JSONObject().put("packPath", directory.absolutePath).put("options", pending.options)
                        pending.initialHostChrome?.let { payload.put("initialHostChrome", it) }
                        pending.initialHostLayout?.let { payload.put("initialHostLayout", it) }
                        pending.route?.let { payload.put("route", it) }
                        command("load", payload)
                        retainAcceptedChrome()
                    }
                    loaded = true; presentedRevision = -1
                } finally {
                    // Failed candidates preserve the old active session and
                    // must regain its GPU target just like successful reloads.
                    try {
                        if (loaded) { applyForeground(); ensurePresenter() }
                    } finally {
                        // A committed runtime may emit load/frame/storage events
                        // even when GPU attachment fails. Keep its revision and
                        // lifecycle observable before returning the load error.
                        drainEvents()
                    }
                    present()
                }
                finish(pending.result, Unit)
            } catch (error: Throwable) {
                if (!loaded && handle != 0L && !atomicHostLayout) {
                    try { unwrap(NativeBridge.destroy(handle)); handle = 0L } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                }
                fail(pending.result, error)
            }
        }
    }
    private fun ensurePresenter() {
        // Atomic preflight owns a logical hostSurface for measured observations;
        // real GLES attach is valid only after the SDK accepted a pack. Treating
        // an empty owner as loaded causes ERR_NOT_LOADED and poisons the retry
        // latch before the first App/Page module has even executed.
        if (!loaded || presenterAttachmentFailed || retiringToken != null || surface?.isValid != true) return
        try {
            if (!presenter.isOpen) presenter.open(checkNotNull(surface))
            presenter.attachRuntime()
        } catch (error: Throwable) {
            // Layout/visibility observations are not a new user request. In
            // particular, a queued load can fail inside attachSurface before
            // that callback reaches this method a second time.
            presenterAttachmentFailed = true
            throw error
        }
    }
    private fun retainAcceptedChrome() {
        val state=command("state") as JSONObject
        val geometry=state.optJSONObject("hostChrome") ?: return
        hostChrome=JSONObject(geometry.toString()).apply { remove("sessionId"); remove("pageId") }
    }
    private fun applyForeground() {
        if (!loaded || closing) return
        val next = desiredForeground && viewVisible
        val changed = next != foreground
        foreground = next
        // A newly accepted SDK session starts foreground even if its host was
        // suspended during an asynchronous GPU drain. Compare authoritative
        // state instead of resetting the host's actual visibility latch.
        val nativeForeground = (command("state") as JSONObject).optBoolean("foreground", !next)
        if (nativeForeground != next) {
            try { command("lifecycle", JSONObject().put("event", if (next) "show" else "hide")) } finally { drainEvents() }
        }
        if (next && (changed || nativeForeground != next)) presentedRevision = -1
    }
    private fun present() {
        if (!loaded || closing || retiringToken != null || !foreground || previewOccluded || !presenter.isOpen || frameRevision == presentedRevision) return
        // Capture the accepted frame after attachment and before drawing. The
        // acknowledgement can run onReady and create another frame for swap.
        val state = try { command("state") as JSONObject } catch (error: Throwable) { emitError(error); return }
        if(!state.optBoolean("chromeGeometryReady",true))return
        val generation = state.getLong("generation")
        val revision = state.getLong("frameRevision")
        frameRevision = revision
        val swapped = try { presenter.present(physicalWidth, physicalHeight) } catch (error: Throwable) {
            try { presenter.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            emitError(error)
            return
        }
        if (!swapped) return
        try {
            val receipt = JSONObject().put("generation", generation).put("revision", revision)
            if (atomicHostLayout) {
                val layout = state.getJSONObject("acceptedLayout")
                for (field in listOf("ownerId", "surfaceGeneration", "observationId", "layoutRevision")) receipt.put(field, layout.getString(field))
            }
            command("presented", receipt)
            presentedRevision = revision
            emit(JSONObject().put("type", "presented").put("generation", generation).put("revision", revision).put("width", physicalWidth).put("height", physicalHeight).put("backend", "skia-gpu-gles"))
        } catch (error: Throwable) {
            // A superseded receipt is not EGL context loss. Never tear down
            // the current surface or acknowledge a newer frame instead.
            if (error !is ContainerException || error.code != "ERR_CANCELLED") emitError(error)
        } finally { drainEvents() }
    }
    private val pump = object : Runnable {
        override fun run() {
            if (closed || cleanupNeedsRetry || handle == 0L) return
            try { command("pump"); drainEvents(); present() } catch (error: Throwable) { emitError(error) }
            schedulePump()
        }
    }
    private fun schedulePump() { worker.removeCallbacks(pump); if (!closed && !cleanupNeedsRetry && handle != 0L) worker.postDelayed(pump, if (foreground && !closing) 16L else 32L) }
    private fun command(operation: String, payload: JSONObject = JSONObject()): Any? = unwrap(NativeBridge.command(handle, operation, payload.toString()))
    private fun drainEvents() {
        if (handle == 0L) return
        val events = command("takeEvents") as JSONArray
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            when (event.getString("type")) {
                "frame" -> frameRevision = event.getLong("revision")
                "hostCall" -> dispatchHostCall(event)
                "hostCallCancelled" -> calls.remove(event.getString("callId"))?.let { call -> main.post { cancelCall(call) } }
                "storageFlushed" -> flushes.remove(event.getLong("ticket"))?.let { future -> if (event.optBoolean("ok")) finish(future, Unit) else fail(future, eventError(event)) }
                "closed" -> { emit(event); completeClose(if (event.optBoolean("ok", true)) null else eventError(event)) }
                else -> emit(event)
            }
        }
    }
    private fun dispatchHostCall(event: JSONObject) {
        val id = event.getString("callId"); val name = event.getString("method")
        val call = HostCall(id, name, event.opt("payload").takeUnless { it == JSONObject.NULL },
            if (event.isNull("deadlineNs")) null else event.getLong("deadlineNs"), event.optString("cancelToken").takeIf { !event.isNull("cancelToken") })
        calls[id] = call
        val method = methods[name]
        val reply = HostReply { payload ->
            worker.post {
                if (calls.remove(id) == null || closing || closed) return@post
                try { command("completeHostCall", payload.put("callId", id)); drainEvents(); present() } catch (error: Throwable) { emitError(error) }
            }
        }
        main.post {
            if (!call.isCancelled) try {
                if (method == null) reply.reject(HostErrorCode.UNSUPPORTED, "Unknown host method: $name") else method.invoke(call, reply)
            } catch (error: Throwable) { reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Host method failed") }
        }
    }
    private fun completeClose(error: Throwable?) {
        if (closed) return
        var failure = error
        try { retireSurface() } catch (cleanup: Throwable) { if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup) }
        if (!presenter.isRetired) { failCloseAttempt(failure ?: ContainerException("ERR_RENDER", "GPU resources are still owned")); return }
        try { if (handle != 0L) { unwrap(NativeBridge.destroy(handle)); handle = 0L } } catch (cleanup: Throwable) { if (failure == null) failure = cleanup else failure.addSuppressed(cleanup) }
        if (handle != 0L) { failCloseAttempt(checkNotNull(failure)); return }
        closed = true; loaded = false; worker.removeCallbacks(pump)
        for (call in calls.values) main.post { cancelCall(call) }; calls.clear()
        for (future in flushes.values) fail(future, failure ?: ContainerException("ERR_CLOSED", "Session closed before storage flush completed")); flushes.clear()
        closeFuture?.let { if (failure == null) finish(it, Unit) else fail(it, failure) }
        main.post { boundView?.get()?.onSessionClosed(this); boundView = null }
        thread.quitSafely()
    }
    private fun failCloseAttempt(error: Throwable) {
        val attempted = synchronized(this) { val future = closeFuture; closeFuture = null; future }
        attempted?.let { fail(it, error) }
        cleanupNeedsRetry = true
        emitError(error)
        // Keep the owner thread and retained handles alive for a subsequent
        // closeAsync. The session remains closing and rejects new app work.
        schedulePump()
    }
    private fun cancelCall(call: HostCall) {
        try { call.cancel() } catch (error: Throwable) { emitError(error) }
    }
    private fun eventError(event: JSONObject): ContainerException {
        val error = event.optJSONObject("error") ?: JSONObject()
        return ContainerException(error.optString("code", "ERR_STORAGE"), error.optString("message", "Persistence failed"))
    }
    private fun emit(event: JSONObject) { main.post { try { eventListener?.onEvent(event) } catch (error: Throwable) { android.util.Log.e("WeappContainer", "Event listener failed", error) } } }
    private fun emitError(error: Throwable) { val e = error.asContainerException(); emit(JSONObject().put("type", "error").put("error", JSONObject().put("code", e.code).put("message", e.message))) }
    private fun ensureOpen() { if (closed || closing) throw ContainerException("ERR_CLOSED", "Session is closing or closed") }
    private fun ensureLoaded() { ensureOpen(); if (!loaded) throw ContainerException("ERR_NOT_LOADED", "Load a pack after supplying View/window metrics") }
    private fun operation(name: String, payload: JSONObject = JSONObject()): CompletableFuture<Unit> = task { ensureLoaded(); try { command(name, payload) } finally { drainEvents(); present() }; Unit }
    private fun <T> task(action: () -> T): CompletableFuture<T> { val future = CompletableFuture<T>(); enqueue(future) { finish(future, action()) }; return future }
    private fun <T> enqueue(future: CompletableFuture<T>, action: () -> Unit) {
        if (closed || !worker.post { try { action() } catch (error: Throwable) { fail(future, error) } }) fail(future, ContainerException("ERR_CLOSED", "Session is closed"))
    }
    private fun <T> finish(future: CompletableFuture<T>, value: T) { main.post { future.complete(value) } }
    private fun fail(future: CompletableFuture<*>, error: Throwable) { main.post { future.completeExceptionally(error.asContainerException()) } }
}
