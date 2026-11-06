package com.sonofmagic.weapp.container.sdk

import java.util.concurrent.CompletableFuture

fun main() {
    val scheduled = ArrayDeque<Runnable>()
    val queue = AccountCleanupQueue { scheduled.addLast(it) }
    fun pump() { while (scheduled.isNotEmpty()) scheduled.removeFirst().run() }
    val first = CompletableFuture<Unit>(); val second = CompletableFuture<Unit>()
    val started = mutableListOf<String>()
    val cancelledResult = queue.enqueue { started.add("old"); first }; pump()
    val latest = queue.enqueue { started.add("latest"); second }
    val closing = queue.drained()
    cancelledResult.cancel(true); second.complete(Unit); pump()
    check(started == listOf("old") && !latest.isDone && !closing.isDone) {
        "Cancellation/late latest success must not release an old native resource"
    }
    first.complete(Unit); pump()
    check(started == listOf("old", "latest") && latest.isDone && closing.isDone)

    val failedNative = CompletableFuture<Unit>()
    val failed = queue.enqueue { failedNative }; pump()
    val retry = queue.enqueue { started.add("retry"); CompletableFuture.completedFuture(Unit) }
    pump(); check(!retry.isDone)
    failedNative.completeExceptionally(IllegalStateException("exact owner cleanup failed")); pump()
    check(failed.isCompletedExceptionally && retry.isDone && started.last() == "retry")

    val failedDispatch = AccountCleanupQueue { error("owner dispatch unavailable") }
    check(failedDispatch.enqueue { error("must not start") }.isCompletedExceptionally)
    check(failedDispatch.drained().isDone)
    println("Account cleanup preserves actual terminals across cancellation, rapid replacement, failure retry and close")
}
