// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import java.util.concurrent.Flow as JdkFlow
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoroutineFlowPublisherTest {
    @Test
    fun `cancelling while awaiting demand closes the channel without signalling error`() = runBlocking {
        var released = 0
        fixture(flow {
            try {
                emit(1)
                emit(2)
            } finally {
                released++
            }
        }) { subscriber ->
            subscriber.subscription.request(1)
            assertEquals(listOf(1), subscriber.values)
            assertEquals(0, released)
            // Unconfined resumption exposes receive failure synchronously inside channel close.
            subscriber.subscription.cancel()
            subscriber.subscription.cancel()
            subscriber.subscription.request(0)
            subscriber.subscription.request(-1)
            subscriber.subscription.request(Long.MAX_VALUE)
            assertEquals(1, released)
            assertEquals(listOf(1), subscriber.values)
            subscriber.assertNoTerminal()
        }
    }

    @Test
    fun `cancel before first demand ignores invalid and valid requests without collecting`() = runBlocking {
        var collections = 0
        fixture(flow { collections++; emit(1) }) { subscriber ->
            subscriber.subscription.cancel()
            subscriber.subscription.cancel()
            subscriber.subscription.request(0)
            subscriber.subscription.request(-1)
            subscriber.subscription.request(1)
            assertEquals(0, collections)
            assertTrue(subscriber.values.isEmpty())
            subscriber.assertNoTerminal()
        }
    }

    @Test
    fun `normal return after subscriber cancellation does not complete`() = runBlocking {
        var returned = false
        fixture(flow {
            emit(1)
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                returned = true
            }
        }) { subscriber ->
            subscriber.subscription.request(1)
            subscriber.subscription.cancel()
            assertTrue(returned)
            subscriber.assertNoTerminal()
        }
    }

    @Test
    fun `upstream cleanup failure after subscriber cancellation does not signal error`() = runBlocking {
        val failure = UpstreamFailure(1)
        var released = false
        fixture(flow {
            emit(1)
            try {
                awaitCancellation()
            } finally {
                released = true
                throw failure
            }
        }) { subscriber ->
            subscriber.subscription.request(1)
            subscriber.subscription.cancel()
            assertTrue(released)
            subscriber.assertNoTerminal()
        }
    }

    @Test
    fun `real upstream error is delivered exactly once when not cancelled`() = runBlocking {
        val failure = UpstreamFailure(2)
        fixture(flow { emit(1); throw failure }) { subscriber ->
            subscriber.subscription.request(1)
            assertEquals(listOf(1), subscriber.values)
            assertEquals(1, subscriber.errors.size)
            assertSame(failure, subscriber.errors.single())
            subscriber.subscription.request(0)
            subscriber.subscription.cancel()
            assertEquals(1, subscriber.errors.size)
            assertEquals(0, subscriber.completions)
        }
    }

    @Test
    fun `normal completion remains terminal despite later requests and cancellation`() = runBlocking {
        fixture(flow { emit(1) }) { subscriber ->
            subscriber.subscription.request(1)
            subscriber.subscription.request(0)
            subscriber.subscription.cancel()
            subscriber.subscription.cancel()
            assertEquals(listOf(1), subscriber.values)
            assertEquals(1, subscriber.completions)
            assertTrue(subscriber.errors.isEmpty())
        }
    }

    @Test
    fun `invalid demand on an active subscription reports exactly one error and releases collection`() = runBlocking {
        var released = 0
        fixture(flow {
            try {
                emit(1)
                emit(2)
            } finally {
                released++
            }
        }) { subscriber ->
            subscriber.subscription.request(1)
            subscriber.subscription.request(0)
            subscriber.subscription.request(-1)
            subscriber.subscription.cancel()
            assertEquals(1, released)
            assertEquals(listOf(1), subscriber.values)
            assertEquals(1, subscriber.errors.size)
            assertTrue(subscriber.errors.single() is IllegalArgumentException)
            assertEquals("Flow.Subscription.request requires a positive demand.", subscriber.errors.single().message)
            assertEquals(0, subscriber.completions)
        }
    }

    @Test
    fun `scope cancellation remains an error without subscriber cancellation`() = runBlocking {
        val owner = SupervisorJob()
        try {
            val subscriber = RecordingSubscriber()
            CoroutineFlowPublisher<Int>(flow { emit(1); emit(2) }, CoroutineScope(owner + Dispatchers.Unconfined))
                .subscribe(subscriber)
            subscriber.subscription.request(1)
            owner.cancel(CancellationException("scope closed"))
            withTimeout(2_000) { owner.join() }
            assertEquals(1, subscriber.errors.size)
            assertTrue(subscriber.errors.single() is CancellationException)
            assertEquals("scope closed", subscriber.errors.single().message)
            subscriber.subscription.cancel()
            subscriber.subscription.request(0)
            assertEquals(1, subscriber.errors.size)
            assertEquals(0, subscriber.completions)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `real cleanup error during scope cancellation is not swallowed for an active subscriber`() = runBlocking {
        val owner = SupervisorJob()
        val failure = UpstreamFailure(3)
        try {
            val subscriber = RecordingSubscriber()
            CoroutineFlowPublisher<Int>(flow {
                emit(1)
                try {
                    awaitCancellation()
                } finally {
                    throw failure
                }
            }, CoroutineScope(owner + Dispatchers.Unconfined)).subscribe(subscriber)
            subscriber.subscription.request(1)
            owner.cancel()
            withTimeout(2_000) { owner.join() }
            assertEquals(1, subscriber.errors.size)
            assertSame(failure, subscriber.errors.single())
            assertEquals(0, subscriber.completions)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `subscriber cancellation suppresses a queued scope cancellation error`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val owner = SupervisorJob()
        try {
            val subscriber = RecordingSubscriber()
            CoroutineFlowPublisher<Int>(flow { emit(1); emit(2) }, CoroutineScope(owner + dispatcher)).subscribe(subscriber)
            subscriber.subscription.request(1)
            dispatcher.drain()
            assertEquals(listOf(1), subscriber.values)
            owner.cancel()
            subscriber.subscription.cancel()
            dispatcher.drain()
            withTimeout(2_000) { owner.join() }
            subscriber.assertNoTerminal()
            assertEquals(listOf(1), subscriber.values)
        } finally {
            owner.cancel()
            dispatcher.drain()
        }
    }

    @Test
    fun `scope cancellation before scheduled collection starts still reports an error`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val owner = SupervisorJob()
        try {
            val subscriber = RecordingSubscriber()
            CoroutineFlowPublisher<Int>(flow { emit(1) }, CoroutineScope(owner + dispatcher)).subscribe(subscriber)
            subscriber.subscription.request(1)
            owner.cancel()
            dispatcher.drain()
            withTimeout(2_000) { owner.join() }
            assertTrue(subscriber.values.isEmpty())
            assertEquals(1, subscriber.errors.size)
            assertTrue(subscriber.errors.single() is CancellationException)
            assertEquals(0, subscriber.completions)
        } finally {
            owner.cancel()
            dispatcher.drain()
        }
    }

    @Test
    fun `subscriber cancellation before queued collection starts suppresses scope failure`() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val owner = SupervisorJob()
        try {
            val subscriber = RecordingSubscriber()
            CoroutineFlowPublisher<Int>(flow { emit(1) }, CoroutineScope(owner + dispatcher)).subscribe(subscriber)
            subscriber.subscription.request(1)
            owner.cancel()
            subscriber.subscription.cancel()
            dispatcher.drain()
            withTimeout(2_000) { owner.join() }
            assertTrue(subscriber.values.isEmpty())
            subscriber.assertNoTerminal()
        } finally {
            owner.cancel()
            dispatcher.drain()
        }
    }

    private suspend fun fixture(source: Flow<Int>, check: (RecordingSubscriber) -> Unit) {
        val owner = SupervisorJob()
        try {
            val subscriber = RecordingSubscriber()
            CoroutineFlowPublisher(source, CoroutineScope(owner + Dispatchers.Unconfined)).subscribe(subscriber)
            check(subscriber)
        } finally {
            owner.cancel()
            withTimeout(2_000) { owner.join() }
        }
    }

    // Stateful exceptions cannot be copied by coroutine debug stack-trace recovery.
    private class UpstreamFailure(val code: Int) : RuntimeException("upstream failed")

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class RecordingSubscriber : JdkFlow.Subscriber<Int> {
        lateinit var subscription: JdkFlow.Subscription
        val values = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        var completions = 0
        override fun onSubscribe(value: JdkFlow.Subscription) { subscription = value }
        override fun onNext(item: Int) { values += item }
        override fun onError(throwable: Throwable) { errors += throwable }
        override fun onComplete() { completions++ }
        fun assertNoTerminal() {
            assertEquals(emptyList<Throwable>(), errors)
            assertEquals(0, completions)
        }
    }
}
