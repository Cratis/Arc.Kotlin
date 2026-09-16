// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:JvmName("CoroutineCompletionStages")

package io.cratis.arc.java

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Launches a child operation with bidirectional stage cancellation, including when the child never starts.
 *
 * Ordinary [Exception] failures complete the stage exceptionally without failing the parent job. Other
 * throwables retain the scope's coroutine failure policy. Execution follows the supplied dispatcher;
 * an inline or blocking executor does not guarantee prompt return or off-thread execution.
 * This supported Kotlin/JVM SPI is hidden from Java source, not from reflection.
 */
@JvmSynthetic
public fun <T> CoroutineScope.launchStage(operation: suspend () -> T): CompletionStage<T> {
    val future = CompletableFuture<T>()
    val job = launch {
        try {
            future.complete(operation())
        } catch (exception: CancellationException) {
            future.cancel(false)
            throw exception
        } catch (exception: Exception) {
            future.completeExceptionally(exception)
        }
    }
    future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
    job.invokeOnCompletion { cause ->
        if (cause != null && !future.isDone) {
            if (cause is CancellationException) future.cancel(false) else future.completeExceptionally(cause)
        }
    }
    return future
}
