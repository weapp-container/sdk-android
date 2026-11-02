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
    enableNfc: Boolean = false,
    soterAttestationProvider: AndroidSoterAttestationProvider? = null,
    enableWifi: Boolean = false,
) : AutoCloseable {
    private val files = AndroidManagedFiles(activity)
    private val device = AndroidDeviceApis(activity, permissions)
    private val bluetooth = if (enableBluetooth) AndroidBluetoothApis(activity, permissions) else null
    private val beacon = if (enableBluetooth) AndroidBeaconApis(activity, permissions) else null
    private val wifi = if (enableWifi) AndroidWifiApis(activity, permissions) else null
    private val media = AndroidNativeMedia(activity, files, permissions)
    private val network = AndroidNetworkApis(files, networkOrigins, allowLoopbackForDevelopment)
    private val recorder = AndroidRecorderApis(activity, files, permissions)
    private val system = AndroidSystemApis(activity, files, permissions, enableNfc, soterAttestationProvider)
    private var installed = false
    private var closed = false
    fun install(session: WeappContainerSession): CompletableFuture<Void> {
        check(!installed && !closed); installed = true
        return files.install(session).thenCompose {
            CompletableFuture.allOf(device.install(session), media.install(session), network.install(session), recorder.install(session), system.install(session), bluetooth?.install(session) ?: CompletableFuture.completedFuture(null), beacon?.install(session) ?: CompletableFuture.completedFuture(null), wifi?.install(session) ?: CompletableFuture.completedFuture(null))
        }
    }
    fun rebindActivity(activity: Activity?) { check(!closed); system.rebindActivity(activity); device.rebindActivity(activity); bluetooth?.rebindActivity(activity); beacon?.rebindActivity(activity); wifi?.rebindActivity(activity); media.rebindActivity(activity); recorder.rebindActivity(activity) }
    override fun close() {
        if (closed) return
        var failure: Throwable? = null
        for (adapter in listOfNotNull<AutoCloseable>(system, recorder, media, bluetooth, beacon, wifi, device, network)) try { adapter.close() } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        if (failure != null) throw failure
        files.close(); closed = true
    }
}
