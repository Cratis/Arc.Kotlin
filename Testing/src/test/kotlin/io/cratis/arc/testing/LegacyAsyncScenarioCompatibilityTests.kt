// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.testing.java.AsyncCommandScenario
import io.cratis.arc.testing.java.AsyncObservableQueryScenario
import io.cratis.arc.testing.java.AsyncQueryScenario
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacyAsyncScenarioCompatibilityTests {
    @Test
    fun `success callback exception is delivered to failure on the same coroutine`(): Unit = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val failure = IllegalStateException("success callback")
        val delivered = CompletableDeferred<Throwable>()
        var thread: Thread? = null
        try {
            AsyncObservableQueryScenario(observable(), scope).collect(1, onSuccess = {
                thread = Thread.currentThread()
                throw failure
            }, onFailure = {
                assertSame(thread, Thread.currentThread())
                delivered.complete(it)
            })
            assertSame(failure, withTimeout(3000) { delivered.await() })
            assertTrue(parent.isActive)
        } finally { parent.cancelAndJoin() }
    }

    @Test
    fun `failure callback exception escapes coroutine and cancels ordinary parent`(): Unit = runBlocking {
        val escaped = CompletableDeferred<Throwable>()
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default + CoroutineExceptionHandler { _, error -> escaped.complete(error) })
        val failure = IllegalStateException("failure callback")
        try {
            AsyncObservableQueryScenario(observable(), scope).collect(0, onSuccess = { error("not success") }, onFailure = {
                assertTrue(it is IllegalArgumentException)
                throw failure
            })
            assertSame(failure, withTimeout(3000) { escaped.await() })
            assertTrue(parent.isCancelled)
        } finally { parent.cancelAndJoin() }
    }

    @Test
    fun `all legacy constructor variants and Kotlin default bridges remain executable`(): Unit = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val module = ManualArtifactModule()
            val commands = listOf(
                AsyncCommandScenario(CommandScenario<TestCommand>(ManualCommandHandler()), scope),
                AsyncCommandScenario<TestCommand>(ManualCommandHandler(), scope),
                AsyncCommandScenario(module, TestCommand::class.java, scope)
            )
            commands.forEach {
                it.execute(TestCommand("legacy")).toCompletableFuture().get(3, TimeUnit.SECONDS).shouldSucceed()
                it.validate(TestCommand("legacy")).toCompletableFuture().get(3, TimeUnit.SECONDS).shouldSucceed()
            }
            val queries = listOf(
                AsyncQueryScenario(QueryScenario<TestModel>(ManualQueryPerformer()), scope),
                AsyncQueryScenario<TestModel>(ManualQueryPerformer(), scope),
                AsyncQueryScenario<TestModel>(module, ManualQueryPerformer.QUERY_NAME, scope)
            )
            queries.forEach { it.perform().toCompletableFuture().get(3, TimeUnit.SECONDS).shouldHaveData(TestModel("default")) }
            val completed = CompletableDeferred<Unit>()
            AsyncObservableQueryScenario(observable(), scope).collect(1, onSuccess = {
                it.shouldHaveEmissionCount(1).shouldHaveData(0, "value")
                completed.complete(Unit)
            }, onFailure = { completed.completeExceptionally(it) })
            withTimeout(3000) { completed.await() }
            assertFalse(parent.isCancelled)
        } finally { parent.cancelAndJoin() }
    }

    private fun observable(): ObservableQueryScenario<String> = ObservableQueryScenario(object : QueryPerformer {
        override val fullyQualifiedName = FullyQualifiedQueryName("Test.values")
        override val descriptor = QueryDescriptor("values", "Test", "java.lang.String",
            authorization = AuthorizationMetadata(allowAnonymous = true), transport = QueryTransportType.OBSERVABLE)
        override suspend fun perform(context: QueryContext): Any = flowOf("value")
    })
}
