package com.sonofmagic.weapp.container.sdk

import java.util.concurrent.CompletableFuture

/** The tail follows real hook terminals; cancelling a caller's result cannot release it. */
internal class AccountCleanupQueue(private val dispatch: (Runnable) -> Unit) {
    private var tail = CompletableFuture.completedFuture(Unit)

    @Synchronized fun enqueue(cleanup: () -> CompletableFuture<Unit>): CompletableFuture<Unit> {
        val previous = tail
        val terminal = CompletableFuture<Unit>()
        val result = CompletableFuture<Unit>()
        tail = terminal
        previous.whenComplete { _, _ ->
            try {
                dispatch(Runnable {
                    try {
                        cleanup().whenComplete { _, error ->
                            if (error == null) result.complete(Unit) else result.completeExceptionally(error)
                            terminal.complete(Unit)
                        }
                    } catch (error: Throwable) {
                        result.completeExceptionally(error); terminal.complete(Unit)
                    }
                })
            } catch (error: Throwable) {
                result.completeExceptionally(error); terminal.complete(Unit)
            }
        }
        return result
    }

    /** Includes superseded hooks, and completes only after their actual work returns. */
    @Synchronized fun drained(): CompletableFuture<Unit> = tail.thenApply { Unit }
}
