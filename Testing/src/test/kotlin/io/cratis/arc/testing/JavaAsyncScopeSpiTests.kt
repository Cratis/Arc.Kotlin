// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.commands.await
import io.cratis.arc.java.JavaAsyncScope
import io.cratis.arc.java.launchStage
import io.cratis.arc.testing.java.AsyncCommandScenario
import io.cratis.arc.testing.java.AsyncQueryScenario
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Calls supported synthetic Core APIs from a separately compiled Kotlin consumer module. */
class JavaAsyncScopeSpiTests {
    @Test
    fun `owner stage and job launch support pending children cancellation and late calls`(): Unit = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val owner = JavaAsyncScope.usingExecutor(executor)
        try {
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val child = owner.launch {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned.complete(Unit) }
            }
            withTimeout(3000) { entered.await() }
            assertEquals("stage", owner.launchStage { "stage" }.await())
            val failure = IllegalArgumentException("stage failure")
            val failed = owner.launchStage<String> { throw failure }.toCompletableFuture()
            assertSame(failure, assertThrows(ExecutionException::class.java) { failed.get(3, TimeUnit.SECONDS) }.cause)
            assertTrue(child.isActive)
            child.cancel()
            withTimeout(3000) { cleaned.await(); child.join() }
            owner.close()
            var invoked = false
            val late = owner.launch { invoked = true }
            withTimeout(3000) { late.join() }
            assertTrue(late.isCancelled)
            val stage = owner.launchStage { invoked = true }.toCompletableFuture()
            assertThrows(CancellationException::class.java) { stage.get(3, TimeUnit.SECONDS) }
            assertFalse(invoked)
            assertFalse(executor.isShutdown)
        } finally { owner.close(); executor.shutdownNow() }
    }

    @Test
    fun `shared helper retains ordinary parent semantics and fatal throwable propagation`(): Unit = runBlocking {
        val parent = Job()
        val escaped = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(parent + Dispatchers.Default + CoroutineExceptionHandler { _, error -> escaped.complete(error) })
        try {
            val failure = IllegalStateException("ordinary")
            val stage = scope.launchStage<String> { throw failure }.toCompletableFuture()
            assertSame(failure, assertThrows(ExecutionException::class.java) { stage.get(3, TimeUnit.SECONDS) }.cause)
            assertTrue(parent.isActive)
            assertEquals("alive", scope.launchStage { "alive" }.await())
            val fatal = AssertionError("fatal")
            val failed = scope.launchStage<String> { throw fatal }.toCompletableFuture()
            assertSame(fatal, withTimeout(3000) { escaped.await() })
            assertSame(fatal, assertThrows(ExecutionException::class.java) { failed.get(3, TimeUnit.SECONDS) }.cause)
            assertTrue(parent.isCancelled)
        } finally { parent.cancelAndJoin() }
    }

    @Test
    fun `queued owner children cancelled before dispatch never execute and complete their stages`(): Unit = runBlocking {
        val queued = AtomicReference<Runnable>()
        JavaAsyncScope.usingExecutor(Executor { check(queued.compareAndSet(null, it)) }).use { owner ->
            var invoked = false
            val stage = owner.launchStage { invoked = true }.toCompletableFuture()
            assertFalse(stage.isDone)
            owner.close()
            // Cooperative dispatchers may require their already-submitted task to drain cancellation.
            queued.getAndSet(null).run()
            assertThrows(CancellationException::class.java) { stage.get(3, TimeUnit.SECONDS) }
            assertFalse(invoked)
        }
    }

    @Test
    fun `legacy command and query stages cancel upstream without cancelling ordinary scope`(): Unit = runBlocking {
        for (command in listOf(true, false)) {
            val parent = Job()
            val scope = CoroutineScope(parent + Dispatchers.Default)
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val operation: suspend () -> Nothing = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned.complete(Unit) }
            }
            try {
                val stage = if (command) {
                    AsyncCommandScenario<TestCommand>(ManualCommandHandler { operation() }, scope).execute(TestCommand("pending"))
                } else {
                    AsyncQueryScenario<TestModel>(ManualQueryPerformer { operation() }, scope).perform()
                }.toCompletableFuture()
                withTimeout(3000) { entered.await() }
                stage.cancel(false)
                withTimeout(3000) { cleaned.await() }
                assertTrue(stage.isCancelled)
                assertTrue(parent.isActive)
            } finally { parent.cancelAndJoin() }
        }
    }
}
