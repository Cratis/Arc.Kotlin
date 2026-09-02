// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.commands.ArcOneOf
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.CommandResponseValues
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.results.CommandResult
import io.cratis.chronicle.auditing.Causation
import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.identity.Identity
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ChronicleCommandSideEffectHandlerTests {
    @Test
    fun `nested aggregate and collection commands execute sequentially in declaration order`() = runBlocking {
        val commands = listOf(TestCommand("first"), TestCommand("second"), TestCommand("third"), TestCommand("fourth"))
        val registry = registry()
        val pipeline = RecordingPipeline()
        val handler = handler(pipeline, registry)
        val response = CommandResponseValues(
            listOf(
                commands[0],
                listOf(commands[1], arrayOf(commands[2])),
                ArcOneOf.of(commands[3])
            )
        )

        assertTrue(handler.canHandle(response))
        val result = handler.execute(response, RegularReactor::class.java, eventContext())

        assertTrue(result.isSuccess)
        assertEquals(commands, pipeline.executed.map(Pair<Any, CommandExecutionOptions>::first))
    }

    @Test
    fun `event tenant and correlation are forwarded to every side effect command`() = runBlocking {
        val correlationId = UUID.randomUUID()
        val registry = registry()
        val pipeline = RecordingPipeline()
        val handler = handler(pipeline, registry)

        val result = handler.execute(
            listOf(TestCommand("first"), TestCommand("second")),
            RegularReactor::class.java,
            eventContext(correlationId = correlationId, namespace = "tenant-two")
        )

        assertTrue(result.isSuccess)
        assertEquals(correlationId, result.correlationId)
        pipeline.executed.forEach { (_, options) ->
            assertEquals(correlationId, options.correlationId)
            assertEquals("tenant-two", options.tenantId)
            assertEquals("tenant-two", options.tenantNamespace)
        }
    }

    @Test
    fun `java asynchronous execution remains incomplete while a command is suspended`() = runBlocking {
        val registry = registry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pipeline = object : CommandPipeline {
            override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> {
                started.complete(Unit)
                release.await()
                return CommandResult.success(options.correlationId)
            }

            override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> =
                CommandResult.success(options.correlationId)
        }
        val handler = handler(pipeline, registry)
        val correlationId = UUID.randomUUID()

        val future = handler.executeAsync(
            TestCommand("async"),
            RegularReactor::class.java,
            eventContext(correlationId = correlationId)
        ).toCompletableFuture()

        started.await()
        assertFalse(future.isDone)
        release.complete(Unit)
        val result = future.join()

        assertTrue(result.isSuccess)
        assertEquals(correlationId, result.correlationId)
    }

    @Test
    fun `declared system roles authorize with only the exact trimmed roles`() = runBlocking {
        val registry = registry()
        val pipeline = RecordingPipeline { _, options ->
            if (options.principal.isInRole("Administrator")) {
                CommandResult.success(options.correlationId)
            } else {
                CommandResult.unauthorized(options.correlationId, "Administrator required")
            }
        }
        val handler = handler(pipeline, registry)

        val result = handler.execute(TestCommand("secured"), SystemReactor::class.java, eventContext())

        assertTrue(result.isSuccess)
        val principal = pipeline.executed.single().second.principal
        assertTrue(principal.isAuthenticated)
        assertEquals(Identity.system.subject, principal.id)
        assertEquals(setOf("Administrator", "Auditor"), principal.roles)
        assertFalse(principal.isInRole("Owner"))
    }

    @Test
    fun `unannotated reactor preserves causing identity but never escalates its roles`() = runBlocking {
        val registry = registry()
        val pipeline = RecordingPipeline()
        val handler = handler(pipeline, registry)
        val identity = identity("user-42", "Ada")

        val result = handler.execute(
            TestCommand("regular"),
            RegularReactor::class.java,
            eventContext(identity = identity)
        )

        assertTrue(result.isSuccess)
        val principal = pipeline.executed.single().second.principal
        assertEquals("user-42", principal.id)
        assertEquals("Ada", principal.name)
        assertTrue(principal.isAuthenticated)
        assertTrue(principal.roles.isEmpty())
    }

    @Test
    fun `system causing identity does not escalate an unannotated reactor`() = runBlocking {
        val registry = registry()
        val pipeline = RecordingPipeline()
        val handler = handler(pipeline, registry)

        handler.execute(TestCommand("regular"), RegularReactor::class.java, eventContext(identity = Identity.system))

        val principal = pipeline.executed.single().second.principal
        assertEquals(Identity.system.subject, principal.id)
        assertTrue(principal.roles.isEmpty())
    }

    @Test
    fun `first authorization failure stops the remaining commands and is returned unchanged`() = runBlocking {
        val registry = registry()
        val pipeline = RecordingPipeline { command, options ->
            if ((command as TestCommand).value == "denied") {
                CommandResult.unauthorized(options.correlationId, "Denied by policy")
            } else {
                CommandResult.success(options.correlationId)
            }
        }
        val handler = handler(pipeline, registry)
        val commands = listOf(TestCommand("first"), TestCommand("denied"), TestCommand("never"))

        val result = handler.execute(commands, RegularReactor::class.java, eventContext())

        assertFalse(result.isSuccess)
        assertFalse(result.isAuthorized)
        assertEquals("Denied by policy", result.authorizationFailureReason)
        assertEquals(listOf("first", "denied"), pipeline.executed.map { (it.first as TestCommand).value })
    }

    @Test
    fun `unsupported nested value rejects the entire side effect without executing a prefix`() = runBlocking {
        val registry = registry()
        val pipeline = RecordingPipeline()
        val handler = handler(pipeline, registry)
        val response = listOf(TestCommand("would-have-run"), listOf("not-a-command"))

        assertFalse(handler.canHandle(response))
        val result = handler.execute(response, RegularReactor::class.java, eventContext())

        assertFalse(result.isSuccess)
        assertTrue(pipeline.executed.isEmpty())
    }

    private fun handler(
        pipeline: CommandPipeline,
        registry: ConcurrentCommandHandlerRegistry
    ): ChronicleCommandSideEffectHandler = ChronicleCommandSideEffectHandler(
        pipeline,
        registry,
        EmptyServiceResolver,
        CoroutineScope(Dispatchers.Unconfined)
    )

    private fun registry(): ConcurrentCommandHandlerRegistry = ConcurrentCommandHandlerRegistry().also {
        it.register(TestCommandHandler())
    }

    private fun eventContext(
        correlationId: UUID = UUID.randomUUID(),
        namespace: String = "tenant-one",
        identity: Identity = identity("user-42", "Ada"),
        causation: List<Causation> = emptyList()
    ): EventContext = mockk {
        every { this@mockk.correlationId } returns correlationId
        every { this@mockk.namespace } returns namespace
        every { this@mockk.causedBy } returns identity
        every { this@mockk.causation } returns causation
    }

    private fun identity(subject: String, name: String): Identity = mockk {
        every { this@mockk.subject } returns subject
        every { this@mockk.name } returns name
    }

    private class RecordingPipeline(
        private val result: (Any, CommandExecutionOptions) -> CommandResult<*> = { _, options ->
            CommandResult.success(options.correlationId)
        }
    ) : CommandPipeline {
        val executed = mutableListOf<Pair<Any, CommandExecutionOptions>>()

        override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> {
            executed.add(command to options)
            return result(command, options)
        }

        override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> =
            CommandResult.success(options.correlationId)
    }

    private class TestCommandHandler : CommandHandler {
        override val commandType: Class<*> = TestCommand::class.java
        override val metadata: CommandDescriptor = CommandDescriptor(commandType.simpleName, commandType.name)
        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private data class TestCommand(val value: String)

    private class RegularReactor

    @ExecuteCommandsAsSystem("Administrator", " Auditor ")
    private class SystemReactor

    private data object EmptyServiceResolver : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
