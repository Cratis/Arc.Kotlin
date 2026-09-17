// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.rxjava3

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ObservableSourceFlowTests {
    @Test
    fun `emits every value then completes`(): Unit = runBlocking {
        val values = Observable.just("first", "second", "third").asKotlinFlow().toList()
        assertIterableEquals(listOf("first", "second", "third"), values)
    }

    @Test
    fun `an empty source completes without emitting`(): Unit = runBlocking {
        assertIterableEquals(emptyList<String>(), Observable.empty<String>().asKotlinFlow().toList())
    }

    @Test
    fun `source failure surfaces to the collector`(): Unit = runBlocking {
        val failure = IllegalArgumentException("source failed")
        val thrown = assertThrows<IllegalArgumentException> {
            runBlocking { Observable.error<String>(failure).asKotlinFlow().toList() }
        }
        assertEquals("source failed", thrown.message)
    }

    @Test
    fun `a subject delivers values emitted after collection started`(): Unit = runBlocking {
        val subject = PublishSubject.create<String>()
        val subscribed = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()

        val collection = launch {
            subject.doOnSubscribe { subscribed.complete(Unit) }
                .asKotlinFlow()
                .toList(received)
        }

        // A PublishSubject drops anything emitted before the subscription exists, so waiting for the
        // subscription is what makes this deterministic rather than a race.
        subscribed.await()
        subject.onNext("late")
        subject.onComplete()
        collection.join()

        assertIterableEquals(listOf("late"), received)
    }

    @Test
    fun `cancelling collection disposes the subscription`(): Unit = runBlocking {
        val subject = PublishSubject.create<String>()
        val subscribed = CompletableDeferred<Unit>()

        val collection = launch {
            subject.doOnSubscribe { subscribed.complete(Unit) }.asKotlinFlow().toList()
        }
        subscribed.await()
        assertTrue(subject.hasObservers(), "the subject must have an observer while collecting")

        collection.cancelAndJoin()

        assertTrue(!subject.hasObservers(), "cancelling collection must dispose the subscription")
    }

    @Test
    fun `taking one value from an infinite source terminates`(): Unit = runBlocking {
        val first = Observable.fromIterable(generateSequence(1) { it + 1 }.asIterable())
            .asKotlinFlow()
            .first()
        assertEquals(1, first)
    }

    @Test
    fun `overflowing the buffer fails loudly instead of dropping values`(): Unit = runBlocking {
        // The source pushes far more than the buffer holds before the collector consumes anything,
        // which is exactly the case Observable's missing backpressure protocol cannot express.
        val overflowing = Observable.fromIterable((1..RX_OBSERVABLE_BUFFER_CAPACITY * 4).toList())
        val thrown = assertThrows<IllegalStateException> {
            runBlocking { overflowing.asKotlinFlow().toList() }
        }
        assertTrue(
            thrown.message.orEmpty().contains("backpressure"),
            "the failure must explain the missing backpressure protocol, was: ${thrown.message}"
        )
    }

    @Test
    fun `the Java entry point adapts the same source`(): Unit = runBlocking {
        val values = RxJava3Flows.asKotlinFlow(Observable.just("value")).toList()
        assertIterableEquals(listOf("value"), values)
    }
}
