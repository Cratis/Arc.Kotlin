// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Integration SPI for marking coroutine work on which caller-facing blocking facades are unsafe. */
public object BlockingPipelineGuard {
    // Safety state only, never request/identity state. asContextElement follows dispatcher migrations
    // and restores the previous thread value on every suspension, including cancellation cleanup.
    private val restricted = ThreadLocal<Boolean>()
    // Separate from the public safety marker: legacy coroutine work must not acquire facade policy.
    private val invocation = ThreadLocal<AtomicReference<InterruptedException?>>()

    /** Normalize only exceptions observed at callback boundaries of a blocking invocation. */
    @JvmSynthetic
    internal fun rethrowInterruption(exception: Throwable) {
        val observed = invocation.get()
        if (observed != null && exception is InterruptedException) {
            // Completion may already be retaining an earlier ordinary cancellation. Remember the
            // interruption independently so that bookkeeping cannot erase this invocation's policy.
            observed.compareAndSet(null, exception)
            throw InterruptedInvocation(exception)
        }
    }

    private class InterruptedInvocation(val interruption: InterruptedException) :
        CancellationException("Blocking Arc pipeline interrupted.") {
        init { initCause(interruption) }
    }

    /** Kotlin host SPI. Add to a bounded scope's context; inherited children remain restricted. */
    @JvmSynthetic
    public fun contextElement(): CoroutineContext = restricted.asContextElement(true)

    @JvmSynthetic
    internal fun <T> run(block: suspend () -> T): T {
        check(restricted.get() != true) {
            "Blocking Arc pipelines cannot run inside Arc bounded coroutine work or another blocking facade. Use the suspending pipeline or async facade instead."
        }
        val owner = Job()
        val observed = AtomicReference<InterruptedException?>()
        try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Caller already interrupted.")
            val result = runBlocking(owner + contextElement() + invocation.asContextElement(observed)) { block() }
            observed.get()?.let { throw InterruptedInvocation(it) }
            return result
        } catch (exception: Exception) {
            val interrupted = observed.get() ?: when (exception) {
                is InterruptedException -> exception
                is InterruptedInvocation -> exception.interruption
                else -> throw exception
            }
            // runBlocking cancels its coroutine on interrupt but need not wait for dispatched cleanup.
            // Keep a parent job so we can await that cleanup before restoring the caller's flag.
            owner.cancel()
            while (!owner.isCompleted) {
                try {
                    runBlocking { owner.join() }
                } catch (_: InterruptedException) {
                    // Repeated interrupts must not detach cleanup. Restore the flag below.
                }
            }
            Thread.currentThread().interrupt()
            throw CancellationException("Blocking Arc pipeline interrupted.").apply { initCause(interrupted) }
        } finally {
            owner.complete()
        }
    }
}
