// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandPreparation
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.results.CommandResult
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.transaction.TransactionStatus

class MongoCommandExecutionScopeTests {
    @Test
    fun `commits a successful command`() = runBlocking {
        val manager = mock(MongoTransactionManager::class.java)
        val status = mock(TransactionStatus::class.java)
        `when`(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(status)
        val scope = MongoCommandExecutionScope(manager)
        val context = commandContext()

        scope.begin(context)
        scope.complete(context, CommandResult.success(context.correlationId))

        verify(manager).commit(status)
    }

    @Test
    fun `separate executions sharing a command instance and correlation retain independent transaction state`() = runBlocking {
        val manager = mock(MongoTransactionManager::class.java)
        val firstStatus = mock(TransactionStatus::class.java)
        val secondStatus = mock(TransactionStatus::class.java)
        `when`(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(firstStatus, secondStatus)
        val scope = MongoCommandExecutionScope(manager)
        val command = Any()
        val correlationId = UUID.randomUUID()
        val first = commandContext(command, correlationId)
        val second = commandContext(command, correlationId)

        scope.begin(first)
        scope.begin(second)
        scope.complete(first, CommandResult.success(correlationId))
        scope.complete(second, CommandResult.success(correlationId))

        verify(manager).commit(firstStatus)
        verify(manager).commit(secondStatus)
    }

    @Test
    fun `pipeline context copies complete the transaction by execution token`() = runBlocking {
        val manager = mock(MongoTransactionManager::class.java)
        val status = mock(TransactionStatus::class.java)
        `when`(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(status)
        val scope = MongoCommandExecutionScope(manager)
        val registry = ConcurrentCommandHandlerRegistry()
        registry.register(copyingHandler())
        val pipeline = DefaultCommandPipeline(registry, executionScopes = listOf(scope))
        val result = pipeline.execute(
            TestCommand,
            CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), serviceResolver)
        )

        assertTrue(result.isSuccess)
        verify(manager).commit(status)
    }

    @Test
    fun `pipeline context copies roll back a failed transaction by execution token`() = runBlocking {
        val manager = mock(MongoTransactionManager::class.java)
        val status = mock(TransactionStatus::class.java)
        `when`(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(status)
        val scope = MongoCommandExecutionScope(manager)
        val registry = ConcurrentCommandHandlerRegistry()
        registry.register(copyingHandler(IllegalStateException("failed after copy")))
        val pipeline = DefaultCommandPipeline(registry, executionScopes = listOf(scope))
        val result = pipeline.execute(
            TestCommand,
            CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), serviceResolver)
        )

        assertTrue(!result.isSuccess)
        verify(manager).rollback(status)
    }

    @Test
    fun `rolls back a failed command`() = runBlocking {
        val manager = mock(MongoTransactionManager::class.java)
        val status = mock(TransactionStatus::class.java)
        `when`(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(status)
        val scope = MongoCommandExecutionScope(manager)
        val context = commandContext()

        scope.begin(context)
        scope.complete(context, CommandResult.error(context.correlationId, "failed"))

        verify(manager).rollback(status)
    }

    private val serviceResolver = object : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }

    private fun copyingHandler(failure: RuntimeException? = null): CommandHandler = object : CommandHandler {
        override val commandType: Class<*> = TestCommand::class.java
        override val metadata: CommandDescriptor = CommandDescriptor("TestCommand", commandType.name)
        override val allowsAnonymous: Boolean = true

        override suspend fun prepare(context: CommandContext): CommandPreparation =
            CommandPreparation(listOf("provided"), CommandResult.success(context.correlationId))

        override suspend fun invoke(context: CommandContext): Any = failure?.let { throw it } ?: "response"
    }

    private fun commandContext(
        command: Any = Any(),
        correlationId: UUID = UUID.randomUUID()
    ): CommandContext = CommandContext(
            correlationId,
            command,
            command.javaClass,
            ArcPrincipal.anonymous(),
            serviceResolver = serviceResolver
        )

    private data object TestCommand
}
