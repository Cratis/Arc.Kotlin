// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import java.util.concurrent.Flow as JdkFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Internal JDK Flow bridge that never reads upstream without subscriber demand. */
internal class DemandAwareFlowPublisher<T : Any>(private val upstream: Flow<T>) : JdkFlow.Publisher<T> {
    override fun subscribe(subscriber: JdkFlow.Subscriber<in T>?) {
        requireNotNull(subscriber) { "subscriber is required" }
        val subscription = Subscription(upstream, subscriber)
        subscriber.onSubscribe(subscription)
        subscription.start()
    }

    private class Subscription<T : Any>(
        private val upstream: Flow<T>,
        private val subscriber: JdkFlow.Subscriber<in T>
    ) : JdkFlow.Subscription {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val demand = AtomicLong()
        private val cancelled = AtomicBoolean()
        private val demandSignal = Channel<Unit>(Channel.CONFLATED)

        override fun request(count: Long) {
            if (count <= 0) {
                if (cancelled.compareAndSet(false, true)) {
                    subscriber.onError(IllegalArgumentException("Reactive Streams demand must be greater than zero."))
                    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                }
                return
            }
            demand.getAndUpdate { current ->
                if (Long.MAX_VALUE - current < count) Long.MAX_VALUE else current + count
            }
            demandSignal.trySend(Unit)
        }

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                demandSignal.close()
            }
        }

        fun start() {
            scope.launch {
                try {
                    upstream.collect { value ->
                        awaitDemand()
                        if (cancelled.get()) return@collect
                        if (demand.get() != Long.MAX_VALUE) demand.decrementAndGet()
                        subscriber.onNext(value)
                    }
                    if (cancelled.compareAndSet(false, true)) subscriber.onComplete()
                } catch (_: CancellationException) {
                    // Subscription cancellation is terminal and does not notify the subscriber.
                } catch (failure: Throwable) {
                    if (cancelled.compareAndSet(false, true)) subscriber.onError(failure)
                } finally {
                    demandSignal.close()
                }
            }
        }

        private suspend fun awaitDemand() {
            while (!cancelled.get() && demand.get() == 0L) demandSignal.receive()
        }
    }
}
