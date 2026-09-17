// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import java.util.concurrent.Flow as JdkFlow
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObservableStateTest {
    @Test
    fun `holds the initial value before anything is published`() {
        val state = ObservableState("initial")

        assertEquals("initial", state.get())
        assertEquals("initial", state.value)
    }

    @Test
    fun `set replaces the current value`() {
        val state = ObservableState("initial")

        state.set("changed")

        assertEquals("changed", state.get())
    }

    @Test
    fun `a new subscriber receives the current value without waiting for a change`() {
        val state = ObservableState("initial")
        state.set("changed")
        val subscriber = RecordingSubscriber<String>()

        state.subscribe(subscriber)

        assertEquals(listOf("changed"), subscriber.values())
    }

    @Test
    fun `a subscriber receives each change after the replayed value`() {
        val state = ObservableState(1)
        val subscriber = RecordingSubscriber<Int>()
        state.subscribe(subscriber)

        state.set(2)
        state.set(3)

        assertEquals(listOf(1, 2, 3), subscriber.values())
    }

    @Test
    fun `a subscriber without demand sees only the newest value it missed`() {
        val state = ObservableState(1)
        val subscriber = RecordingSubscriber<Int>(initialDemand = 0)
        state.subscribe(subscriber)

        state.set(2)
        state.set(3)
        state.set(4)
        subscriber.request(1)

        assertEquals(listOf(4), subscriber.values())
    }

    @Test
    fun `a cancelled subscription stops receiving values`() {
        val state = ObservableState(1)
        val subscriber = RecordingSubscriber<Int>()
        state.subscribe(subscriber)

        subscriber.cancel()
        state.set(2)

        assertEquals(listOf(1), subscriber.values())
    }

    @Test
    fun `non positive demand fails the subscriber instead of delivering`() {
        val state = ObservableState(1)
        val subscriber = RecordingSubscriber<Int>(initialDemand = 0)
        state.subscribe(subscriber)

        subscriber.request(0)

        assertInstanceOf(IllegalArgumentException::class.java, subscriber.error())
    }

    // The snapshot GET contract: Arc reads a current value off a StateFlow source and answers 202 for a
    // source without one. Unwrapping here is what lets an ObservableState-backed Java query answer 200.
    @Test
    fun `asKotlinFlow exposes the held state rather than an opaque subscription`() {
        val state: JdkFlow.Publisher<String> = ObservableState("initial")

        val flow = state.asKotlinFlow()

        assertTrue(flow is StateFlow<*>)
        assertEquals("initial", (flow as StateFlow<*>).value)
    }

    @Test
    fun `taking one value from the adapted flow completes immediately`() = runBlocking {
        val state = ObservableState("initial")
        state.set("changed")

        val snapshot = withTimeout(1_000) { (state as JdkFlow.Publisher<String>).asKotlinFlow().take(1).toList() }

        assertEquals(listOf("changed"), snapshot)
    }

    @Test
    fun `collecting the adapted flow sees later values`() = runBlocking {
        val state = ObservableState(1)

        val collected = withTimeout(1_000) {
            val flow = (state as JdkFlow.Publisher<Int>).asKotlinFlow()
            val first = flow.first()
            state.set(2)
            listOf(first, flow.first { it == 2 })
        }

        assertEquals(listOf(1, 2), collected)
    }

    @Test
    fun `a subscriber that throws is dropped without breaking publication`() {
        val state = ObservableState(1)
        val throwing = ThrowingSubscriber<Int>()
        val healthy = RecordingSubscriber<Int>()
        state.subscribe(throwing)
        state.subscribe(healthy)

        state.set(2)

        assertEquals(listOf(1, 2), healthy.values())
        assertFalse(throwing.receivedAfterFailure)
    }

    private class RecordingSubscriber<T : Any>(private val initialDemand: Long = Long.MAX_VALUE) : JdkFlow.Subscriber<T> {
        private val received = CopyOnWriteArrayList<T>()
        private val failure = AtomicReference<Throwable?>()
        private val subscription = AtomicReference<JdkFlow.Subscription?>()

        override fun onSubscribe(value: JdkFlow.Subscription) {
            subscription.set(value)
            if (initialDemand > 0) value.request(initialDemand)
        }

        override fun onNext(item: T) {
            received.add(item)
        }

        override fun onError(throwable: Throwable) {
            failure.set(throwable)
        }

        override fun onComplete() = Unit

        fun values(): List<T> = received.toList()
        fun error(): Throwable? = failure.get()
        fun request(count: Long) = subscription.get()!!.request(count)
        fun cancel() = subscription.get()!!.cancel()
    }

    private class ThrowingSubscriber<T : Any> : JdkFlow.Subscriber<T> {
        var receivedAfterFailure: Boolean = false
            private set
        private var failed = false

        override fun onSubscribe(value: JdkFlow.Subscription) {
            value.request(Long.MAX_VALUE)
        }

        override fun onNext(item: T) {
            if (failed) {
                receivedAfterFailure = true
                return
            }
            failed = true
            throw IllegalStateException("subscriber failed")
        }

        override fun onError(throwable: Throwable) = Unit
        override fun onComplete() = Unit
    }
}
