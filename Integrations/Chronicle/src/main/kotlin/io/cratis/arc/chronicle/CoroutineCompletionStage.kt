// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun <T> CoroutineScope.asCompletionStage(block: suspend () -> T): CompletionStage<T> {
    val future = CompletableFuture<T>()
    val job = try {
        launch {
            try {
                future.complete(block())
            } catch (exception: CancellationException) {
                future.cancel(false)
                throw exception
            } catch (exception: Throwable) {
                future.completeExceptionally(exception)
            }
        }
    } catch (exception: Throwable) {
        future.completeExceptionally(exception)
        return future
    }
    future.whenComplete { _, _ ->
        if (future.isCancelled) job.cancel(CancellationException("CompletionStage was cancelled."))
    }
    return future
}
