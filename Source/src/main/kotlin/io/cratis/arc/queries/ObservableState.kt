// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import java.util.Objects
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Flow as JdkFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state that always has a current value, for an observable query written in Java.
 *
 * Kotlin has `MutableStateFlow`: a value you can read now and that pushes on change. The JDK has no
 * equivalent — a bare [JdkFlow.Publisher] promises only that values will arrive eventually. That
 * difference is visible on the wire, because Arc answers a snapshot `GET` on an observable query
 * from the source's current value and reports a source without one as `202 Not Ready` rather than
 * holding the request open. A Java application that publishes through a plain publisher therefore
 * cannot serve that `GET` at all, however promptly it emits.
 *
 * This is the missing half. It is a [JdkFlow.Publisher], so a Java query method returns it directly,
 * and Arc recognizes it as holding a current value, so the same query answers `GET` and a live
 * subscription alike.
 *
 * ```java
 * @ReadModel
 * public record Ticker(int count) {
 *     public static Flow.Publisher<Ticker> observe(@FromServices TickerSource source) {
 *         return source.state();
 *     }
 * }
 * ```
 *
 * Every subscriber sees the current value first and then each change. A subscriber that is behind
 * sees only the newest value, never a backlog — the same conflating behavior `MutableStateFlow`
 * has, and the right one for state: a stale intermediate value is of no use to anybody.
 *
 * Kotlin code has no reason to reach for this. Use `MutableStateFlow` and return a `Flow`.
 *
 * @param T The published value type.
 * @param initial The value every subscriber sees before the first change.
 */
public class ObservableState<T : Any>(initial: T) : JdkFlow.Publisher<T> {
    private val state: MutableStateFlow<T> = MutableStateFlow(initial)
    private val subscriptions = CopyOnWriteArrayList<StateSubscription<T>>()
    private val versions = AtomicLong()
    private val current = AtomicReference(Versioned(versions.incrementAndGet(), initial))

    /** The current value, readable and writable from Kotlin as a property. */
    public var value: T
        get() = current.get().value
        set(newValue) = set(newValue)

    /**
     * Gets the current value.
     *
     * @return The value most recently published.
     */
    public fun get(): T = current.get().value

    /**
     * Publishes a new value to every subscriber.
     *
     * @param value The value to publish.
     */
    public fun set(value: T) {
        val published = Versioned(versions.incrementAndGet(), Objects.requireNonNull(value, "value"))
        current.set(published)
        state.value = value
        subscriptions.forEach { subscription -> subscription.offer(published) }
    }

    override fun subscribe(subscriber: JdkFlow.Subscriber<in T>) {
        Objects.requireNonNull(subscriber, "subscriber")
        val subscription = StateSubscription(subscriber, subscriptions)
        subscriptions.add(subscription)
        subscription.offer(current.get())
        try {
            subscriber.onSubscribe(subscription)
        } catch (_: RuntimeException) {
            subscription.cancel()
        }
    }

    /** The Kotlin view Arc collects, which is what makes the snapshot `GET` fast path apply. */
    internal fun asFlow(): StateFlow<T> = state.asStateFlow()

    private class Versioned<T>(val version: Long, val value: T)

    private class StateSubscription<T : Any>(
        private val subscriber: JdkFlow.Subscriber<in T>,
        private val owner: CopyOnWriteArrayList<StateSubscription<T>>
    ) : JdkFlow.Subscription {
        private val demand = AtomicLong()
        private val pending = AtomicReference<Versioned<T>?>()
        private val draining = AtomicInteger()
        private val active = AtomicBoolean(true)
        private val offered = AtomicLong(-1)

        override fun request(n: Long) {
            if (n <= 0) {
                cancel()
                subscriber.onError(IllegalArgumentException("Flow demand must be positive."))
                return
            }
            demand.updateAndGet { existing -> if (existing > Long.MAX_VALUE - n) Long.MAX_VALUE else existing + n }
            drain()
        }

        override fun cancel() {
            if (active.compareAndSet(true, false)) {
                pending.set(null)
                owner.remove(this)
            }
        }

        fun offer(value: Versioned<T>) {
            if (!active.get() || offered.getAndAccumulate(value.version, ::maxOf) >= value.version) return
            pending.set(value)
            drain()
        }

        private fun drain() {
            if (draining.getAndIncrement() != 0) return
            var missed = 1
            do {
                if (active.get() && demand.get() > 0) {
                    val value = pending.getAndSet(null)
                    if (value != null) {
                        demand.updateAndGet { existing -> if (existing == Long.MAX_VALUE) existing else existing - 1 }
                        try {
                            subscriber.onNext(value.value)
                        } catch (_: RuntimeException) {
                            cancel()
                        }
                    }
                } else if (!active.get()) {
                    pending.set(null)
                }
                missed = draining.addAndGet(-missed)
            } while (missed != 0)
        }
    }
}
