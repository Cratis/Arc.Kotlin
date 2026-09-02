// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandResponseValues
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.eventSequences.AppendResult
import io.cratis.chronicle.eventSequences.ConstraintViolation
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.EventSequenceNumber
import io.cratis.chronicle.eventSequences.IEventLog
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope
import io.cratis.chronicle.events.EventType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ChronicleCommandTransactionTests {
    @Test
    fun `successful command commits every staged response as one ordered atomic batch`() = runBlocking {
        val first = SomethingHappened("first")
        val second = SomethingHappened("second")
        val command = TransactionalCommand(
            "primary",
            CommandResponseValues(
                listOf(
                    first,
                    EventForEventSourceId("secondary", second)
                )
            )
        )
        val fixture = fixture(command)
        val appendedEvents = io.mockk.slot<List<EventForEventSourceId>>()
        coEvery {
            fixture.eventLog.appendMany(
                capture(appendedEvents),
                any<Map<String, ConcurrencyScope>>(),
                any<UUID>()
            )
        } returns listOf(successfulAppend(1), successfulAppend(2))

        val result = fixture.pipeline.execute(command, fixture.options())

        assertTrue(result.isSuccess)
        assertEquals(listOf("primary", "secondary"), appendedEvents.captured.map { it.eventSourceId })
        assertEquals(listOf(first, second), appendedEvents.captured.map { it.event })
        coVerify(exactly = 1) {
            fixture.eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                any<Map<String, ConcurrencyScope>>(),
                fixture.correlationId
            )
        }
    }

    @Test
    fun `failed command rolls staged events back without touching Chronicle`() = runBlocking {
        val event = SomethingHappened("discarded")
        val command = TransactionalCommand(
            "primary",
            CommandResponseValues(
                listOf(
                    event,
                    ValidationResult(
                        ValidationResultSeverity.Error,
                        "The command was rejected.",
                        reason = ValidationResultReasons.RULE
                    )
                )
            )
        )
        val fixture = fixture(command)

        val result = fixture.pipeline.execute(command, fixture.options())

        assertFalse(result.isSuccess)
        assertEquals("The command was rejected.", result.validationResults.single().message)
        coVerify(exactly = 0) {
            fixture.eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                any<Map<String, ConcurrencyScope>>(),
                any<UUID>()
            )
        }
    }

    @Test
    fun `commit maps constraint details to the offending camel case member`() = runBlocking {
        val event = SomethingHappened("rejected")
        val command = TransactionalCommand("primary", event)
        val fixture = fixture(command)
        coEvery {
            fixture.eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                any<Map<String, ConcurrencyScope>>(),
                fixture.correlationId
            )
        } returns listOf(
            AppendResult(
                EventSequenceNumber.unavailable,
                listOf(
                    ConstraintViolation(
                        "unique-name",
                        "The name is already used.",
                        mapOf("propertyName" to "Name", "value" to "taken")
                    )
                ),
                emptyList(),
                false,
                null
            )
        )

        val result = fixture.pipeline.execute(command, fixture.options())

        assertFalse(result.isSuccess)
        val validation = result.validationResults.single()
        assertEquals(listOf("name"), validation.members)
        assertEquals(ValidationResultReasons.CONSTRAINT_VIOLATION, validation.reason)
        assertEquals("unique-name", validation.reasonDetail)
        assertEquals(mapOf("propertyName" to "Name", "value" to "taken"), validation.state)
        coVerify(exactly = 1) {
            fixture.eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                any<Map<String, ConcurrencyScope>>(),
                fixture.correlationId
            )
        }
    }

    @Test
    fun `scoped response commits exact concurrency scopes with tenant store and correlation`() = runBlocking {
        val correlationId = UUID.randomUUID()
        val tenantStore = mockk<IEventStore>()
        val eventLog = mockk<IEventLog>()
        every { tenantStore.namespace } returns "tenant-one"
        every { tenantStore.eventLog } returns eventLog
        var resolvedTenant: String? = null
        val resolver = TenantEventStoreResolver { tenant ->
            resolvedTenant = tenant
            tenantStore
        }
        val event = SomethingHappened("scoped")
        val scope = ConcurrencyScope.none
        val response = EventsWithConcurrencyScopes.builder()
            .event("primary", event)
            .concurrencyScope("primary", scope)
            .build()
        val command = TransactionalCommand("unused", response)
        val registry = registry(command)
        val transactions = ChronicleCommandTransaction()
        val handler = EventsWithConcurrencyScopesCommandResponseValueHandler(resolver, transactions)
        val pipeline = DefaultCommandPipeline(
            registry,
            executionScopes = listOf(ChronicleCommandExecutionScope(transactions)),
            responseValueHandlers = listOf(handler)
        )
        val expectedEvents = listOf(EventForEventSourceId("primary", event))
        val expectedScopes = mapOf("primary" to scope)
        coEvery {
            eventLog.appendMany(
                match<List<EventForEventSourceId>> { events ->
                    events.map { it.eventSourceId to it.event } == expectedEvents.map { it.eventSourceId to it.event }
                },
                expectedScopes,
                correlationId
            )
        } returns listOf(successfulAppend(11))

        val result = pipeline.execute(command, executionOptions(correlationId, "tenant-one"))

        assertTrue(result.isSuccess)
        assertEquals("tenant-one", resolvedTenant)
        coVerify(exactly = 1) {
            eventLog.appendMany(
                match<List<EventForEventSourceId>> { events ->
                    events.map { it.eventSourceId to it.event } == expectedEvents.map { it.eventSourceId to it.event }
                },
                expectedScopes,
                correlationId
            )
        }
        assertSame(scope, expectedScopes.getValue("primary"))
    }

    @Test
    fun `immediate scoped response passes correlation and exact scopes`() = runBlocking {
        val correlationId = UUID.randomUUID()
        val eventLog = mockk<IEventLog>()
        val eventStore = mockk<IEventStore> {
            every { namespace } returns "default"
            every { this@mockk.eventLog } returns eventLog
        }
        val event = SomethingHappened("immediate")
        val scope = ConcurrencyScope.none
        val response = EventsWithConcurrencyScopes.builder()
            .event("primary", event)
            .concurrencyScope("primary", scope)
            .build()
        val command = TransactionalCommand("command-key", response)
        val registry = registry(command)
        coEvery {
            eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                response.concurrencyScopes,
                correlationId
            )
        } returns listOf(successfulAppend(1))
        val pipeline = DefaultCommandPipeline(
            registry,
            responseValueHandlers = listOf(EventsWithConcurrencyScopesCommandResponseValueHandler(eventStore))
        )

        val result = pipeline.execute(command, executionOptions(correlationId))

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                response.concurrencyScopes,
                correlationId
            )
        }
    }

    @Test
    fun `cancellation during append propagates without retry`() {
        val command = TransactionalCommand("primary", SomethingHappened("cancelled"))
        val fixture = fixture(command)
        coEvery {
            fixture.eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                any<Map<String, ConcurrencyScope>>(),
                any<UUID>()
            )
        } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking { fixture.pipeline.execute(command, fixture.options()) }
        }
        coVerify(exactly = 1) {
            fixture.eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                any<Map<String, ConcurrencyScope>>(),
                fixture.correlationId
            )
        }
    }

    private fun fixture(command: TransactionalCommand): Fixture {
        val eventLog = mockk<IEventLog>()
        val eventStore = mockk<IEventStore>()
        every { eventStore.namespace } returns "default"
        every { eventStore.eventLog } returns eventLog
        val registry = registry(command)
        val transactions = ChronicleCommandTransaction()
        val responseHandler = ChronicleCommandResponseValueHandler(eventStore, registry, transactions)
        val pipeline = DefaultCommandPipeline(
            registry,
            executionScopes = listOf(ChronicleCommandExecutionScope(transactions)),
            responseValueHandlers = listOf(responseHandler)
        )
        return Fixture(eventLog, pipeline)
    }

    private fun registry(command: TransactionalCommand): ConcurrentCommandHandlerRegistry =
        ConcurrentCommandHandlerRegistry().also { registry -> registry.register(TestCommandHandler(command)) }

    private fun successfulAppend(sequenceNumber: Long): AppendResult = AppendResult(
        EventSequenceNumber(sequenceNumber),
        emptyList(),
        emptyList(),
        true,
        null
    )

    private fun executionOptions(
        correlationId: UUID,
        tenantNamespace: String? = null
    ): CommandExecutionOptions = CommandExecutionOptions(
        correlationId,
        ArcPrincipal.anonymous(),
        EmptyServiceResolver,
        tenantId = tenantNamespace,
        tenantNamespace = tenantNamespace
    )

    private inner class Fixture(
        val eventLog: IEventLog,
        val pipeline: DefaultCommandPipeline,
        val correlationId: UUID = UUID.randomUUID()
    ) {
        fun options(): CommandExecutionOptions = executionOptions(correlationId)
    }

    private class TestCommandHandler(private val command: TransactionalCommand) : CommandHandler {
        override val commandType: Class<*> = command.javaClass
        override val metadata: CommandDescriptor = CommandDescriptor(commandType.simpleName, commandType.name)

        override fun resolveCommandKey(command: Any): Any = (command as TransactionalCommand).key

        override suspend fun invoke(context: CommandContext): Any = (context.command as TransactionalCommand).response
    }

    @Command
    private data class TransactionalCommand(val key: String, val response: Any)

    @EventType
    private data class SomethingHappened(val value: String)

    private data object EmptyServiceResolver : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
