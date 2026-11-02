package com.sonofmagic.weapp.container.sdk

import android.app.Activity
import java.util.concurrent.CompletableFuture

/** One native owner for the device adapters used by Android, Flutter and React Native embeddings. */
class AndroidNativeDeviceServices(
    activity: Activity,
    networkOrigins: Set<String> = emptySet(),
    allowLoopbackForDevelopment: Boolean = false,
    enableBluetooth: Boolean = true,
    permissions: AndroidPermissionBroker? = null,
) : AutoCloseable {
    private val files = AndroidManagedFiles(activity)
    private val device = AndroidDeviceApis(activity, permissions)
    private val bluetooth = if (enableBluetooth) AndroidBluetoothApis(activity, permissions) else null
    private val media = AndroidNativeMedia(activity, files, permissions)
    private val network = AndroidNetworkApis(files, networkOrigins, allowLoopbackForDevelopment)
    private val recorder = AndroidRecorderApis(activity, files, permissions)
    private var installed = false
    private var closed = false
    fun install(session: WeappContainerSession): CompletableFuture<Void> {
        check(!installed && !closed); installed = true
        return files.install(session).thenCompose {
            CompletableFuture.allOf(device.install(session), media.install(session), network.install(session), recorder.install(session), bluetooth?.install(session) ?: CompletableFuture.completedFuture(null))
        }
    }
    fun rebindActivity(activity: Activity?) { check(!closed); device.rebindActivity(activity); bluetooth?.rebindActivity(activity); media.rebindActivity(activity); recorder.rebindActivity(activity) }
    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        for (adapter in listOfNotNull<AutoCloseable>(recorder, media, bluetooth, device, network)) try { adapter.close() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        if (failure != null) throw failure
        files.close(); closed = true
    }
}
