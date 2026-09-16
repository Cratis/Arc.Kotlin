// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.java.BlockingCommandPipeline
import io.cratis.arc.java.BlockingQueryPipeline
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryRequest
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class ArcBlockingPipelineTests {
    private val services = object : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
    private val options = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal("worker", true), services)
    private val queryOptions = QueryExecutionOptions(options.correlationId, options.principal, services)
    private val request = QueryRequest(FullyQualifiedQueryName("Tests.query"))

    @Test
    fun `non web context supplies unbound blocking facades`() {
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java)).run { context ->
            assertEquals(1, context.getBeansOfType(BlockingCommandPipeline::class.java).size)
            assertEquals(1, context.getBeansOfType(BlockingQueryPipeline::class.java).size)
            val commands = context.getBean(BlockingCommandPipeline::class.java)
            val queries = context.getBean(BlockingQueryPipeline::class.java)
            assertThrows(IllegalStateException::class.java) { commands.execute(Any()) }
            assertThrows(IllegalStateException::class.java) { queries.perform(request) }
            // Missing artifact failures prove these are live pipeline facades, not placeholder beans.
            assertFalse(commands.execute(Any(), options).isSuccess)
            assertFalse(queries.perform(request, queryOptions).isSuccess)
        }
    }

    @Test
    fun `application supplied bound facade beans replace both defaults`() {
        val commands = BlockingCommandPipeline(DefaultCommandPipeline(ConcurrentCommandHandlerRegistry()), options)
        val queries = BlockingQueryPipeline(DefaultQueryPipeline(ConcurrentQueryPerformerRegistry()), queryOptions)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))
            .withBean(BlockingCommandPipeline::class.java, { commands })
            .withBean(BlockingQueryPipeline::class.java, { queries })
            .run { context ->
                assertSame(commands, context.getBean(BlockingCommandPipeline::class.java))
                assertSame(queries, context.getBean(BlockingQueryPipeline::class.java))
                assertEquals(1, context.getBeansOfType(BlockingCommandPipeline::class.java).size)
                assertEquals(1, context.getBeansOfType(BlockingQueryPipeline::class.java).size)
            }
    }

    @Test
    fun `bounded scope rejects blocking facades even after dispatcher migration without leaking marker`(): Unit = runBlocking {
        val commands = BlockingCommandPipeline(DefaultCommandPipeline(ConcurrentCommandHandlerRegistry()), options)
        val queries = BlockingQueryPipeline(DefaultQueryPipeline(ConcurrentQueryPerformerRegistry()), queryOptions)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            ArcApplicationCoroutineScope(1).use { scope ->
                withTimeout(5000) {
                    scope.async {
                        assertThrows(IllegalStateException::class.java) { commands.execute(Any()) }
                        assertThrows(IllegalStateException::class.java) { commands.validate(Any()) }
                        assertThrows(IllegalStateException::class.java) { queries.perform(request) }
                        withContext(dispatcher) {
                            assertThrows(IllegalStateException::class.java) { commands.execute(Any()) }
                            assertThrows(IllegalStateException::class.java) { commands.validate(Any()) }
                            assertThrows(IllegalStateException::class.java) { queries.perform(request) }
                        }
                    }.await()
                }
            }
            withContext(dispatcher) {
                // Outside marked work the same pooled thread is allowed; normal missing-artifact results return.
                assertFalse(commands.execute(Any()).isSuccess)
                assertFalse(queries.perform(request).isSuccess)
            }
        }
    }
}
