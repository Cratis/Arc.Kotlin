// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import java.util.concurrent.Flow as JdkFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JdkPublisherFlowTest {
    @Test
    fun `slow collection bounds demand without cancelling a compliant synchronous publisher`() = runBlocking {
        val publisher = SynchronousPublisher(1_000)
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf<Int>()
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
            publisher.asKotlinFlow().collect {
                values += it
                if (values.size == 1) {
                    first.complete(Unit)
                    release.await()
                }
            }
        }
        try {
            withTimeout(2_000) { first.await() }
            assertEquals(listOf(0), values)
            assertFalse(publisher.cancelled, "A slow collector must backpressure, not cancel, a compliant publisher")
            assertTrue(publisher.emitted <= 65, "At most one delivered value plus 64 buffered values")
            release.complete(Unit)
            withTimeout(2_000) { collecting.join() }
            assertEquals((0 until 1_000).toList(), values)
            assertTrue(publisher.completed)
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `a synchronous stream larger than the buffer completes without losing values or hanging`() = runBlocking {
        val publisher = SynchronousPublisher(1_000)
        val values = withTimeoutOrNull(2_000) { publisher.asKotlinFlow().toList() }
        assertNotNull(values, "A compliant finite publisher must complete rather than hang after buffer overflow")
        assertEquals((0 until 1_000).toList(), values)
        assertTrue(publisher.completed)
    }

    @Test
    fun `reentrant request delivery completes with bounded request stack depth`() = runBlocking {
        val publisher = SynchronousPublisher(10_000, allowReentrant = true)
        val values = withTimeout(2_000) { publisher.asKotlinFlow().toList() }
        assertEquals((0 until 10_000).toList(), values)
        assertTrue(publisher.maximumRequestDepth <= 2, "Demand replenishment must not recurse per item")
    }

    @Test
    fun `an unconfined collector resuming inside onNext does not recursively request`() = runBlocking {
        val subscribed = CompletableDeferred<JdkFlow.Subscriber<in Int>>()
        val publisher = JdkFlow.Publisher<Int> { subscribed.complete(it) }
        val values = mutableListOf<Int>()
        val collecting = launch(Dispatchers.Unconfined) { publisher.asKotlinFlow().toList(values) }
        val upstream = SynchronousPublisher(10_000, allowReentrant = true)
        try {
            upstream.subscribe(withTimeout(2_000) { subscribed.await() })
            withTimeout(2_000) { collecting.join() }
            assertEquals((0 until 10_000).toList(), values)
            assertEquals(1, upstream.maximumRequestDepth)
        } finally {
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `adaptation is cold and each collection owns a fresh subscription`() = runBlocking {
        var subscriptions = 0
        val publisher = JdkFlow.Publisher<Int> { subscriber ->
            subscriptions++
            SynchronousPublisher(3).subscribe(subscriber)
        }
        val flow = publisher.asKotlinFlow()
        assertEquals(0, subscriptions)
        assertEquals(listOf(0, 1, 2), flow.toList())
        assertEquals(listOf(0, 1, 2), flow.toList())
        assertEquals(2, subscriptions)
    }

    @Test
    fun `publisher error is propagated after buffered values and releases the subscription`() = runBlocking {
        val failure = PublisherFailure(42)
        val publisher = SynchronousPublisher(3, failure = failure)
        val values = mutableListOf<Int>()
        val actual = runCatching { withTimeout(2_000) { publisher.asKotlinFlow().toList(values) } }.exceptionOrNull()
        assertSame(failure, actual)
        assertEquals(listOf(0, 1, 2), values)
        assertTrue(publisher.cancelled)
    }

    @Test
    fun `empty publisher completion does not wait for a value`() = runBlocking {
        val publisher = JdkFlow.Publisher<Int> { subscriber ->
            subscriber.onSubscribe(RecordingSubscription())
            subscriber.onComplete()
        }
        assertEquals(emptyList<Int>(), withTimeout(2_000) { publisher.asKotlinFlow().toList() })
    }

    @Test
    fun `duplicate subscriptions are cancelled without demand and original is released once`() = runBlocking {
        val original = RecordingSubscription()
        val duplicate = RecordingSubscription()
        val publisher = JdkFlow.Publisher<Int> { subscriber ->
            subscriber.onSubscribe(original)
            subscriber.onSubscribe(duplicate)
            subscriber.onComplete()
        }
        withTimeout(2_000) { publisher.asKotlinFlow().collect() }
        assertEquals(0L, duplicate.requested)
        assertEquals(1, duplicate.cancellations)
        assertEquals(1, original.cancellations)
    }

    @Test
    fun `early collection termination cancels upstream and stops replenishing demand`() = runBlocking {
        val publisher = SynchronousPublisher(1_000)
        assertEquals(listOf(0), withTimeout(2_000) { publisher.asKotlinFlow().take(1).toList() })
        assertTrue(publisher.cancelled)
        assertTrue(publisher.emitted <= 64)
    }

    @Test
    fun `cancellation while waiting for a signal releases upstream exactly once`() = runBlocking {
        val subscription = RecordingSubscription()
        val publisher = JdkFlow.Publisher<Int> { it.onSubscribe(subscription) }
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) { publisher.asKotlinFlow().collect() }
        collecting.cancelAndJoin()
        assertEquals(1, subscription.cancellations)
    }

    @Test
    fun `subscription arriving after collection cancellation is cancelled without demand`() = runBlocking {
        val subscribed = CompletableDeferred<JdkFlow.Subscriber<in Int>>()
        val publisher = JdkFlow.Publisher<Int> { subscribed.complete(it) }
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) { publisher.asKotlinFlow().collect() }
        val subscriber = withTimeout(2_000) { subscribed.await() }
        collecting.cancelAndJoin()
        val late = RecordingSubscription()
        subscriber.onSubscribe(late)
        assertEquals(1, late.cancellations)
        assertEquals(0L, late.requested)
    }

    @Test
    fun `a publisher overflowing the buffer fails explicitly instead of hanging`() = runBlocking {
        val subscription = RecordingSubscription()
        val publisher = JdkFlow.Publisher<Int> { subscriber ->
            subscriber.onSubscribe(subscription)
            repeat(100) { subscriber.onNext(it) }
        }
        val failure = runCatching { withTimeout(2_000) { publisher.asKotlinFlow().toList() } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("JDK publisher exceeded the flow buffer capacity", failure?.message)
        assertEquals(1, subscription.cancellations)
    }

    @Test
    fun `a failure thrown by subscribe releases the accepted subscription`() = runBlocking {
        val failure = PublisherFailure(43)
        val subscription = RecordingSubscription()
        val publisher = JdkFlow.Publisher<Int> { subscriber ->
            subscriber.onSubscribe(subscription)
            throw failure
        }
        val actual = runCatching { publisher.asKotlinFlow().collect() }.exceptionOrNull()
        assertSame(failure, actual)
        assertEquals(1, subscription.cancellations)
    }

    // An extra field prevents coroutine stack-trace recovery from copying this exception, allowing
    // an identity assertion to check the publisher's original failure rather than a debug copy.
    private class PublisherFailure(val code: Int) : RuntimeException("publisher failed")

    private class RecordingSubscription : JdkFlow.Subscription {
        var requested = 0L
        var cancellations = 0
        override fun request(n: Long) { requested += n }
        override fun cancel() { cancellations++ }
    }

    /** Synchronous demand-compliant fixture; cancellation deliberately sends no terminal signal. */
    private class SynchronousPublisher(
        private val count: Int,
        private val allowReentrant: Boolean = false,
        private val failure: Throwable? = null
    ) : JdkFlow.Publisher<Int> {
        var emitted = 0
        var cancelled = false
        var completed = false
        var maximumRequestDepth = 0

        override fun subscribe(subscriber: JdkFlow.Subscriber<in Int>) {
            subscriber.onSubscribe(object : JdkFlow.Subscription {
                private var demand = 0L
                private var depth = 0
                override fun request(n: Long) {
                    assertTrue(n > 0)
                    if (cancelled || completed) return
                    demand += n
                    if (depth > 0 && !allowReentrant) return
                    depth++
                    maximumRequestDepth = maxOf(maximumRequestDepth, depth)
                    try {
                        while (demand > 0 && emitted < count && !cancelled) {
                            demand--
                            subscriber.onNext(emitted++)
                        }
                        if (emitted == count && !cancelled && !completed) {
                            completed = true
                            if (failure == null) subscriber.onComplete() else subscriber.onError(failure)
                        }
                    } finally {
                        depth--
                    }
                }
                override fun cancel() { cancelled = true }
            })
        }
    }
}
