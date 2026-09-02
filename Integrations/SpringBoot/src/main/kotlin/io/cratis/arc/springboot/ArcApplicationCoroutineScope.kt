// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Bounded, application-owned coroutine scope used by Arc host integration work. */
public class ArcApplicationCoroutineScope @JvmOverloads constructor(
    parallelism: Int,
    queueCapacity: Int = 256
) : CoroutineScope, AutoCloseable {
    private val dispatcher: CoroutineDispatcher
    private val job = SupervisorJob()
    private val admission: Semaphore

    init {
        require(parallelism > 0) { "parallelism must be greater than zero." }
        require(queueCapacity >= 0) { "queueCapacity cannot be negative." }
        val sequence = AtomicInteger()
        val queue: BlockingQueue<Runnable> = if (queueCapacity == 0) {
            SynchronousQueue<Runnable>()
        } else {
            ArrayBlockingQueue<Runnable>(queueCapacity)
        }
        dispatcher = ThreadPoolExecutor(
            parallelism,
            parallelism,
            0L,
            TimeUnit.MILLISECONDS,
            queue,
            { operation -> Thread(operation, "arc-work-${sequence.incrementAndGet()}").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        ).asCoroutineDispatcher()
        admission = Semaphore(Math.addExact(parallelism, queueCapacity))
    }

    override val coroutineContext: CoroutineContext = job + dispatcher

    /**
     * Reserves bounded execution capacity without waiting and creates [block] only when admitted.
     *
     * A lazy launch reserves capacity immediately and enqueues only when the returned job is started.
     * Returns `null` when all running and queued request slots are occupied.
     */
    internal fun tryLaunch(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit
    ): Job? {
        if (!admission.tryAcquire()) return null
        val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) admission.release()
        }
        return try {
            launch(start = start, block = block).also { launched ->
                launched.invokeOnCompletion { release() }
                if (launched.isCancelled) release()
            }
        } catch (exception: Throwable) {
            release()
            throw exception
        }
    }

    /** Cancels outstanding work and releases the owned executor. */
    override fun close() {
        cancel("Arc application context is closing.")
        (dispatcher as AutoCloseable).close()
    }
}
