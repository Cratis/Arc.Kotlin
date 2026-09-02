// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:JvmName("CompletionStages")

package io.cratis.arc.commands

import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Awaits this stage without blocking a thread and propagates coroutine cancellation when possible. */
public suspend fun <T> CompletionStage<T>.await(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, exception ->
        if (exception == null) {
            continuation.resume(value)
        } else {
            val cause = if (exception is CompletionException && exception.cause != null) {
                exception.cause!!
            } else {
                exception
            }
            continuation.resumeWithException(cause)
        }
    }
    continuation.invokeOnCancellation {
        (this as? Future<*>)?.cancel(true)
    }
}
