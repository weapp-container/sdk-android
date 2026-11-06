package com.sonofmagic.weapp.container.sdk

fun main() {
    var stops = 0
    val pending = AndroidNfcTerminal { stops++ }
    pending.requestStop(); pending.requestStop()
    check(stops == 1 && !pending.finished && !pending.await(5))
    val native = Thread { pending.finish() }; native.start(); native.join()
    check(pending.finished && pending.await(5)); pending.requestStop(); check(stops == 1)
    var attempts = 0
    val retry = AndroidNfcTerminal { attempts++; if (attempts == 1) error("owned tag close failed") }
    check(runCatching { retry.requestStop() }.isFailure); check(!retry.finished)
    retry.requestStop(); check(attempts == 2 && !retry.await(5))
    retry.finish(); check(retry.await(5))
    println("NFC cancellation waits for the native worker terminal and failed exact tag close remains retryable")
}
