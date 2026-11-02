package com.sonofmagic.weapp.container.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stopping a native tag operation never substitutes for its worker terminal. */
internal class AndroidNfcTerminal(private val stopNative: () -> Unit) {
    private val stopped = AtomicBoolean(false)
    private val terminal = CountDownLatch(1)
    val finished: Boolean get() = terminal.count == 0L
    fun requestStop() {
        if (finished || !stopped.compareAndSet(false, true)) return
        try { stopNative() } catch (error: Throwable) { stopped.set(false); throw error }
    }
    fun finish() { terminal.countDown() }
    fun await(timeoutMs: Long): Boolean = terminal.await(timeoutMs, TimeUnit.MILLISECONDS)
}
