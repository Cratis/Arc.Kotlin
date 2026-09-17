// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.rxjava3

import io.reactivex.rxjava3.core.ObservableSource
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Values buffered between an RxJava producer and a slower collector before the flow fails. */
public const val RX_OBSERVABLE_BUFFER_CAPACITY: Int = 64

/**
 * Adapts an RxJava 3 [ObservableSource] to a cold Kotlin [Flow].
 *
 * Each collection owns its own subscription, so the returned flow is cold even when the source is a
 * hot `Subject`. Values a subject emitted before collection started are not replayed unless that
 * subject replays them itself.
 *
 * RxJava 3's `Observable` has **no backpressure protocol**, so the producer cannot be asked to slow
 * down. Values are buffered up to [RX_OBSERVABLE_BUFFER_CAPACITY]; a producer that outruns the
 * collector beyond that fails the flow with [IllegalStateException] rather than dropping values
 * silently or buffering without bound. Use a backpressure-aware source, or an explicit RxJava
 * operator such as `toFlowable`, when the producer can outpace the consumer.
 *
 * Cancelling collection disposes the subscription, including one delivered after cancellation.
 *
 * @param T Type of value the source emits.
 * @return A cold [Flow] emitting the source's values.
 */
public fun <T : Any> ObservableSource<T>.asKotlinFlow(): Flow<T> = flow {
    val values = Channel<T>(RX_OBSERVABLE_BUFFER_CAPACITY)
    val subscription = RxSubscription()

    currentCoroutineContext().ensureActive()
    subscribe(ChannelObserver(values, subscription))

    try {
        for (item in values) {
            emit(item)
            currentCoroutineContext().ensureActive()
        }
    } finally {
        subscription.dispose()
        values.cancel()
    }
}

/**
 * Bridges RxJava's push-only callbacks onto a bounded channel.
 *
 * `onNext` cannot suspend, so overflow is reported as an explicit failure instead of being dropped.
 */
private class RxSubscription {
    private val disposable = AtomicReference<Disposable?>()
    private val disposed = AtomicBoolean()

    /**
     * Adopts [value], disposing it immediately when collection already ended so a subscription that
     * arrives after cancellation cannot leak.
     */
    fun adopt(value: Disposable) {
        disposable.set(value)
        if (disposed.get()) dispose()
    }

    fun dispose() {
        disposed.set(true)
        disposable.getAndSet(null)?.dispose()
    }
}

private class ChannelObserver<T : Any>(
    private val values: Channel<T>,
    private val subscription: RxSubscription
) : Observer<T> {
    override fun onSubscribe(d: Disposable) {
        subscription.adopt(d)
    }

    override fun onNext(value: T) {
        val result = values.trySend(value)
        // A closed channel means collection already ended, which is not an overflow.
        if (result.isSuccess || result.isClosed) return
        values.close(
            IllegalStateException(
                "RxJava source produced more than $RX_OBSERVABLE_BUFFER_CAPACITY values faster than " +
                    "the collector consumed them. Observable has no backpressure protocol; use a " +
                    "backpressure-aware source or an explicit operator such as toFlowable."
            )
        )
        subscription.dispose()
    }

    override fun onError(error: Throwable) {
        values.close(error)
    }

    override fun onComplete() {
        values.close()
    }
}

/** Java-callable entry point for [asKotlinFlow]. */
public object RxJava3Flows {
    /**
     * Adapts [source] to a cold Kotlin [Flow]; see [asKotlinFlow] for buffering and cancellation.
     *
     * @param T Type of value the source emits.
     * @param source The RxJava 3 source to adapt.
     * @return A cold [Flow] emitting the source's values.
     */
    @JvmStatic
    public fun <T : Any> asKotlinFlow(source: ObservableSource<T>): Flow<T> = source.asKotlinFlow()
}
