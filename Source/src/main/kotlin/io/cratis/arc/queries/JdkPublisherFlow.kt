// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import java.util.concurrent.Flow as JdkFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Adapts a JDK [JdkFlow.Publisher] to a cold Kotlin [Flow] without blocking a thread.
 *
 * Each collection owns a subscription and a 64-element buffer. Demand is replenished only after
 * downstream emission returns, so a compliant publisher is backpressured without losing values.
 * Buffered values precede completion or failure. Collection cancellation cancels the subscription,
 * including one delivered late. A publisher overflowing the buffer fails the flow explicitly.
 *
 * An [ObservableState] is unwrapped to the state it already holds instead of being subscribed to
 * as an opaque publisher. That is what lets a Java observable query answer a snapshot `GET` from
 * its current value, exactly as a Kotlin query returning a `StateFlow` does.
 */
public fun <T : Any> JdkFlow.Publisher<T>.asKotlinFlow(): Flow<T> {
    if (this is ObservableState<T>) return asFlow()
    return asSubscribedFlow()
}

private fun <T : Any> JdkFlow.Publisher<T>.asSubscribedFlow(): Flow<T> = flow {
    val values = Channel<T>(64)
    val subscriber = PublisherFlowSubscriber(values)
    try {
        currentCoroutineContext().ensureActive()
        this@asSubscribedFlow.subscribe(subscriber)
        for (item in values) {
            emit(item)
            currentCoroutineContext().ensureActive()
            subscriber.request(1)
        }
    } finally {
        subscriber.cancel()
        values.cancel()
    }
}

private class PublisherFlowSubscriber<T : Any>(private val values: Channel<T>) : JdkFlow.Subscriber<T> {
    private val subscription = AtomicReference<JdkFlow.Subscription?>()
    private val terminated = AtomicBoolean()
    private val pendingDemand = AtomicLong()
    private val requesting = AtomicInteger()

    override fun onSubscribe(value: JdkFlow.Subscription) {
        if (subscription.compareAndSet(null, value)) {
            request(64)
        } else {
            value.cancel()
        }
    }

    override fun onNext(item: T) {
        if (terminated.get()) return
        if (values.trySend(item).isFailure) {
            onError(IllegalStateException("JDK publisher exceeded the flow buffer capacity"))
            cancel()
        }
    }

    override fun onError(throwable: Throwable) {
        if (terminated.compareAndSet(false, true)) values.close(throwable)
    }

    override fun onComplete() {
        if (terminated.compareAndSet(false, true)) values.close()
    }

    fun request(count: Long) {
        if (terminated.get()) return
        pendingDemand.addAndGet(count)
        if (requesting.getAndIncrement() != 0) return
        // An unconfined collector can resume inside onNext. Trampoline its replenishment rather
        // than nesting Subscription.request calls and growing the publisher's stack per item.
        do {
            val demand = pendingDemand.getAndSet(0)
            if (demand > 0 && !terminated.get()) subscription.get()?.request(demand)
        } while (requesting.decrementAndGet() != 0)
    }

    fun cancel() {
        terminated.set(true)
        subscription.getAndSet(CancelledSubscription)?.cancel()
    }

    private object CancelledSubscription : JdkFlow.Subscription {
        override fun request(n: Long) = Unit
        override fun cancel() = Unit
    }
}
