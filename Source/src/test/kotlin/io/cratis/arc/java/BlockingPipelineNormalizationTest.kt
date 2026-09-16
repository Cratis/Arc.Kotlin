// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandContextValuesProvider
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal class BlockingPipelineNormalizationTest {
    internal enum class Site { PROVIDER, BEGIN, FILTER, PREDICATE, RESPONSE, COMPLETE }
    private val services = object : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
    private val options = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal("worker", true), services)

    @ParameterizedTest
    @EnumSource(Site::class)
    fun `normalization completes every begun command scope even when completion itself interrupts`(site: Site) {
        val interruption = InterruptedException(site.name)
        val completed = mutableListOf<Int>()
        val pipeline = DefaultCommandPipeline(
            handlers(Any::class.java) { "response" },
            listOf(object : CommandFilter {
                override suspend fun execute(context: CommandContext): CommandResult<*> {
                    if (site == Site.FILTER) throw interruption
                    return CommandResult.success(context.correlationId)
                }
            }),
            (1..2).map { index -> object : CommandExecutionScope {
                override fun begin(context: CommandContext) {
                    if (site == Site.BEGIN && index == 2) throw interruption
                }
                override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                    yield()
                    completed.add(index)
                    if (site == Site.COMPLETE && index == 2) throw interruption
                    assertFalse(result.isSuccess, "Remaining scopes receive failed result for rollback")
                    return null
                }
            } },
            listOf(object : CommandResponseValueHandler {
                override fun canHandle(context: CommandContext, value: Any): Boolean {
                    if (site == Site.PREDICATE) throw interruption
                    return true
                }
                override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                    if (site == Site.RESPONSE) throw interruption
                    return CommandResult.success(context.correlationId)
                }
            }),
            listOf(CommandContextValuesProvider {
                if (site == Site.PROVIDER) throw interruption
                emptyMap()
            })
        )
        try {
            val failure = assertThrows(CancellationException::class.java) {
                BlockingCommandPipeline(pipeline, options).execute(Any())
            }
            assertSame(interruption, failure.cause)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(when (site) {
                Site.PROVIDER -> emptyList()
                Site.BEGIN -> listOf(1)
                else -> listOf(2, 1)
            }, completed)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cleanup interruption is not lost behind earlier ordinary command cancellation`() {
        val cancellation = CancellationException("handler cancelled first")
        val interruption = InterruptedException("cleanup interrupted later")
        val completed = mutableListOf<Int>()
        val pipeline = DefaultCommandPipeline(handlers(Any::class.java) { throw cancellation },
            executionScopes = (1..2).map { index -> object : CommandExecutionScope {
                override fun begin(context: CommandContext) = Unit
                override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                    assertFalse(result.isSuccess)
                    yield()
                    completed.add(index)
                    if (index == 2) throw interruption
                    return null
                }
            } })
        try {
            val failure = assertThrows(CancellationException::class.java) {
                BlockingCommandPipeline(pipeline, options).execute(Any())
            }
            assertSame(interruption, failure.cause)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf(2, 1), completed)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `pure exception results do not activate interruption policy even inside facade invocation`() {
        val pipeline = DefaultCommandPipeline(handlers(Any::class.java) {
            CommandResult.fromException(options.correlationId, InterruptedException("result data, not thrown"))
        })
        assertFalse(BlockingCommandPipeline(pipeline, options).execute(Any()).isSuccess)
        assertFalse(Thread.currentThread().isInterrupted)
    }

    private class Graph(private val interruption: InterruptedException) {
        val child: String get() = throw interruption
    }

    @Test
    fun `reflective graph interruption cannot disappear into unreadable property fallback`() {
        val interruption = InterruptedException("getter")
        val validator = object : ModelValidator<Graph> {
            override val modelType = Graph::class.java
            override suspend fun validate(model: Graph, context: ModelValidationContext): List<ValidationResult> = emptyList()
        }
        val pipeline = DefaultCommandPipeline(handlers(Graph::class.java) { throw AssertionError("Handler must not run") },
            listOf(DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(validator))))
        try {
            val failure = assertThrows(CancellationException::class.java) {
                BlockingCommandPipeline(pipeline, options).execute(Graph(interruption))
            }
            assertSame(interruption, failure.cause)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `model validation existing invocation and stage wrappers expose interruption without changing factories`() {
        val interruption = InterruptedException("model")
        var subsequentValidatorCalled = false
        val first = object : ModelValidator<Any> {
            override val modelType = Any::class.java
            override suspend fun validate(model: Any, context: ModelValidationContext): List<ValidationResult> =
                throw CompletionException(InvocationTargetException(interruption))
        }
        val second = object : ModelValidator<Any> {
            override val modelType = Any::class.java
            override suspend fun validate(model: Any, context: ModelValidationContext): List<ValidationResult> {
                subsequentValidatorCalled = true
                return emptyList()
            }
        }
        val pipeline = DefaultCommandPipeline(handlers(Any::class.java) { throw AssertionError("Handler must not run") },
            listOf(DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(first, second))))
        try {
            val failure = assertThrows(CancellationException::class.java) {
                BlockingCommandPipeline(pipeline, options).validate(Any())
            }
            assertSame(interruption, failure.cause)
            assertFalse(subsequentValidatorCalled)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `facade invocation marker is restored on reused worker after normalization`(): Unit = runBlocking {
        val failure = InterruptedException("worker")
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val pipeline = DefaultCommandPipeline(handlers(Any::class.java) {
                withContext(dispatcher) { throw failure }
            })
            try {
                assertThrows(CancellationException::class.java) {
                    BlockingCommandPipeline(pipeline, options).execute(Any())
                }
            } finally {
                Thread.interrupted()
            }
            // Both the caller and the exact same pooled worker lose the invocation-only marker.
            assertFalse(pipeline.execute(Any(), options).isSuccess)
            withContext(dispatcher + BlockingPipelineGuard.contextElement()) {
                assertFalse(pipeline.execute(Any(), options).isSuccess)
            }
        }
        assertFalse(Thread.currentThread().isInterrupted)
    }

    private fun handlers(type: Class<*>, invoke: suspend () -> Any?): ConcurrentCommandHandlerRegistry =
        ConcurrentCommandHandlerRegistry().apply {
            register(object : CommandHandler {
                override val commandType = type
                override val metadata = CommandDescriptor("Work", type.name)
                override suspend fun invoke(context: CommandContext): Any? = invoke()
            })
        }
}
