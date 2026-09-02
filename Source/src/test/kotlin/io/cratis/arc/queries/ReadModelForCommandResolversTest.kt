// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.ServiceResolver
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ReadModelForCommandResolversTest {
    @Test
    fun `blocking bridge completes successfully on the caller thread`() {
        val callerThread = Thread.currentThread()
        var resolverThread: Thread? = null
        val expected = Model("resolved")
        val resolver = blockingResolver {
            resolverThread = Thread.currentThread()
            expected
        }

        val stage = resolver.resolve(Model::class.java, commandContext(), "model-42")

        assertSame(callerThread, resolverThread)
        assertTrue(stage.toCompletableFuture().isDone)
        assertSame(expected, stage.toCompletableFuture().join())
    }

    @Test
    fun `blocking bridge preserves nullable results`() {
        val resolver = blockingResolver { null }

        assertNull(resolver.resolve(Model::class.java, commandContext(), "missing").toCompletableFuture().join())
    }

    @Test
    fun `blocking bridge returns ordinary failures as exceptional completion`() {
        val failure = IllegalStateException("lookup failed")
        val resolver = blockingResolver { throw failure }

        lateinit var stage: CompletionStage<Any?>
        assertDoesNotThrow {
            stage = resolver.resolve(Model::class.java, commandContext(), "model-42")
        }
        val exception = assertThrows(CompletionException::class.java) {
            stage.toCompletableFuture().join()
        }

        assertSame(failure, exception.cause)
    }

    @Test
    fun `blocking bridge represents cancellation as a cancelled stage`() {
        val resolver = blockingResolver { throw CancellationException("cancelled") }

        lateinit var stage: CompletionStage<Any?>
        assertDoesNotThrow {
            stage = resolver.resolve(Model::class.java, commandContext(), "model-42")
        }
        val future = stage.toCompletableFuture()

        assertTrue(future.isCancelled)
        assertThrows(CancellationException::class.java) { future.join() }
    }

    @Test
    fun `registry adapts synchronous non-blocking failures across all resolution paths on the caller thread`() =
        runBlocking {
            val callerThread = Thread.currentThread()
            val resolverThreads = mutableListOf<Thread>()
            val failure = IllegalStateException("lookup failed synchronously")
            val resolver = object : CanResolveReadModelForCommand {
                override fun readModelTypes(): Set<Class<*>> = setOf(Model::class.java)
                override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED

                override fun resolve(
                    readModelType: Class<*>,
                    commandContext: CommandContext,
                    key: Any
                ): CompletionStage<Any?> {
                    resolverThreads.add(Thread.currentThread())
                    throw failure
                }
            }
            val registry = ReadModelForCommandResolverRegistry(listOf(resolver))
            val context = commandContext()

            lateinit var stage: CompletionStage<Any?>
            assertDoesNotThrow { stage = registry.resolveAsync(Model::class.java, context) }
            val asyncFailure = assertThrows(CompletionException::class.java) {
                stage.toCompletableFuture().join()
            }
            assertSame(failure, asyncFailure.cause)

            val blockingFailure = assertThrows(CompletionException::class.java) {
                registry.resolveBlocking(Model::class.java, context)
            }
            assertSame(failure, blockingFailure.cause)

            var suspendFailure: Throwable? = null
            try {
                registry.resolve(Model::class.java, context)
            } catch (exception: Throwable) {
                suspendFailure = exception
            }
            assertEquals(failure::class.java, suspendFailure?.javaClass)
            assertEquals(failure.message, suspendFailure?.message)
            assertEquals(listOf(callerThread, callerThread, callerThread), resolverThreads)
        }

    @Test
    fun `registry preserves synchronous non-blocking cancellation as a cancelled stage`() {
        val callerThread = Thread.currentThread()
        var resolverThread: Thread? = null
        val resolver = object : CanResolveReadModelForCommand {
            override fun readModelTypes(): Set<Class<*>> = setOf(Model::class.java)
            override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED

            override fun resolve(
                readModelType: Class<*>,
                commandContext: CommandContext,
                key: Any
            ): CompletionStage<Any?> {
                resolverThread = Thread.currentThread()
                throw CancellationException("cancelled")
            }
        }
        val registry = ReadModelForCommandResolverRegistry(listOf(resolver))

        lateinit var stage: CompletionStage<Any?>
        assertDoesNotThrow { stage = registry.resolveAsync(Model::class.java, commandContext()) }

        assertSame(callerThread, resolverThread)
        assertTrue(stage.toCompletableFuture().isCancelled)
        assertThrows(CancellationException::class.java) { stage.toCompletableFuture().join() }
    }

    @Test
    fun `registry preserves deferred stage failure cancellation and coroutine cancellation`() = runBlocking {
        fun registryFor(future: CompletableFuture<Any?>): ReadModelForCommandResolverRegistry {
            val resolver = object : CanResolveReadModelForCommand {
                override fun readModelTypes(): Set<Class<*>> = setOf(Model::class.java)
                override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
                override fun resolve(
                    readModelType: Class<*>,
                    commandContext: CommandContext,
                    key: Any
                ): CompletionStage<Any?> = future
            }
            return ReadModelForCommandResolverRegistry(listOf(resolver))
        }

        val failureFuture = CompletableFuture<Any?>()
        val failureStage = registryFor(failureFuture).resolveAsync(Model::class.java, commandContext())
        val failure = IllegalStateException("failed later")
        failureFuture.completeExceptionally(failure)
        assertSame(failure, assertThrows(CompletionException::class.java) { failureStage.toCompletableFuture().join() }.cause)

        val cancelledFuture = CompletableFuture<Any?>()
        val cancelledStage = registryFor(cancelledFuture).resolveAsync(Model::class.java, commandContext())
        cancelledFuture.cancel(false)
        assertTrue(cancelledStage.toCompletableFuture().isCancelled)

        val waitingFuture = CompletableFuture<Any?>()
        val waiting = async { registryFor(waitingFuture).resolve(Model::class.java, commandContext()) }
        yield()
        waiting.cancelAndJoin()
        assertTrue(waitingFuture.isCancelled)
    }

    @Test
    fun `equal ownership collision selection and diagnostics are globally deterministic`() {
        val first = FirstDeclaredResolver(linkedSetOf(AlphaModel::class.java, ZuluModel::class.java))
        val second = SecondDeclaredResolver(linkedSetOf(ZuluModel::class.java, AlphaModel::class.java))

        val forward = assertThrows(MultipleReadModelResolversForCommandException::class.java) {
            ReadModelForCommandResolverRegistry(listOf(second, first))
        }
        val reverse = assertThrows(MultipleReadModelResolversForCommandException::class.java) {
            ReadModelForCommandResolverRegistry(listOf(first, second))
        }
        val resolverNames = listOf(first.javaClass.name, second.javaClass.name).sorted().joinToString()

        assertSame(AlphaModel::class.java, forward.readModelType)
        assertSame(AlphaModel::class.java, reverse.readModelType)
        assertEquals(forward.message, reverse.message)
        assertEquals(
            "Multiple command-side read-model resolvers claim '${AlphaModel::class.java.name}' with equal ownership: " +
                "$resolverNames.",
            forward.message
        )
    }

    private fun blockingResolver(resolve: () -> Any?): BlockingReadModelForCommandResolver =
        object : BlockingReadModelForCommandResolver {
            override fun readModelTypes(): Set<Class<*>> = setOf(Model::class.java)
            override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
            override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any? = resolve()
        }

    private fun commandContext(): CommandContext = CommandContext(
        UUID.randomUUID(),
        TestCommand,
        TestCommand::class.java,
        ArcPrincipal("Anonymous", false, emptySet()),
        serviceResolver = NoServices,
        commandKey = "model-42"
    )

    private class FirstDeclaredResolver(claimedTypes: Set<Class<*>>) : NamedResolver(claimedTypes)
    private class SecondDeclaredResolver(claimedTypes: Set<Class<*>>) : NamedResolver(claimedTypes)

    private open class NamedResolver(private val claimedTypes: Set<Class<*>>) : BlockingReadModelForCommandResolver {
        override fun readModelTypes(): Set<Class<*>> = claimedTypes
        override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
        override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any? = null
    }

    private data class AlphaModel(val value: String)
    private data class Model(val value: String)
    private data class ZuluModel(val value: String)
    private data object TestCommand

    private data object NoServices : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
