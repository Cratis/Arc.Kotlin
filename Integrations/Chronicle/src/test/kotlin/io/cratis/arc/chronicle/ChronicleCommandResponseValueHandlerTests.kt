// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandKeyProvider
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs as ArcConceptAs
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.CommandResponseValueDescriptor
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.concepts.ConceptAs as ChronicleConceptAs
import io.cratis.chronicle.eventSequences.AppendError
import io.cratis.chronicle.eventSequences.AppendOptions
import io.cratis.chronicle.eventSequences.AppendResult
import io.cratis.chronicle.eventSequences.ConstraintViolation
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.EventSequenceNumber
import io.cratis.chronicle.eventSequences.IEventLog
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyViolation
import io.cratis.chronicle.events.EventType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ChronicleCommandResponseValueHandlerTests {
    @Test
    fun `same command key for two tenants appends to distinct stores`() = runBlocking {
        val tenantOneEventLog = mockk<IEventLog>()
        val tenantTwoEventLog = mockk<IEventLog>()
        val tenantOneStore = mockk<IEventStore> {
            every { namespace } returns "tenant-one"
            every { eventLog } returns tenantOneEventLog
        }
        val tenantTwoStore = mockk<IEventStore> {
            every { namespace } returns "tenant-two"
            every { eventLog } returns tenantTwoEventLog
        }
        val firstEvent = SomethingHappened("first")
        val secondEvent = SomethingHappened("second")
        val firstCommand = KeyedCommand("shared-key", firstEvent)
        val secondCommand = KeyedCommand("shared-key", secondEvent)
        val registry = ConcurrentCommandHandlerRegistry()
        registry.register(TestCommandHandler(firstCommand) { command -> (command as KeyedCommand).key })
        val provider = TenantEventStoreProvider { namespace ->
            when (namespace) {
                "tenant-one" -> tenantOneStore
                "tenant-two" -> tenantTwoStore
                else -> null
            }
        }
        val resolver = tenantEventStoreResolver(tenantOneStore, provider)
        val responseHandler = ChronicleCommandResponseValueHandler(resolver, registry)
        val pipeline = DefaultCommandPipeline(registry, responseValueHandlers = listOf(responseHandler))
        coEvery { tenantOneEventLog.append("shared-key", firstEvent, any<AppendOptions>()) } returns successfulAppend()
        coEvery { tenantTwoEventLog.append("shared-key", secondEvent, any<AppendOptions>()) } returns successfulAppend()

        val firstResult = pipeline.execute(firstCommand, executionOptions(UUID.randomUUID(), "tenant-one"))
        val secondResult = pipeline.execute(secondCommand, executionOptions(UUID.randomUUID(), "tenant-two"))

        assertTrue(firstResult.isSuccess)
        assertTrue(secondResult.isSuccess)
        coVerify(exactly = 1) { tenantOneEventLog.append("shared-key", firstEvent, any<AppendOptions>()) }
        coVerify(exactly = 1) { tenantTwoEventLog.append("shared-key", secondEvent, any<AppendOptions>()) }
    }

    @Test
    fun `single store serves null tenant and its matching namespace`() = runBlocking {
        val nullTenantEvent = SomethingHappened("null-tenant")
        val matchingTenantEvent = SomethingHappened("matching-tenant")
        val fixture = fixture(KeyedCommand("shared-key", nullTenantEvent)) { command -> (command as KeyedCommand).key }
        every { fixture.eventStore.namespace } returns "tenant-one"
        coEvery { fixture.eventLog.append("shared-key", nullTenantEvent, any<AppendOptions>()) } returns successfulAppend()
        coEvery { fixture.eventLog.append("shared-key", matchingTenantEvent, any<AppendOptions>()) } returns successfulAppend()

        val nullTenantResult = fixture.pipeline.execute(fixture.command, executionOptions(UUID.randomUUID()))
        val matchingTenantResult = fixture.pipeline.execute(
            KeyedCommand("shared-key", matchingTenantEvent),
            executionOptions(UUID.randomUUID(), "tenant-one")
        )

        assertTrue(nullTenantResult.isSuccess)
        assertTrue(matchingTenantResult.isSuccess)
    }

    @Test
    fun `single store rejects a mismatched tenant without appending`() = runBlocking {
        val event = SomethingHappened("not-appended")
        val fixture = fixture(KeyedCommand("shared-key", event)) { command -> (command as KeyedCommand).key }
        every { fixture.eventStore.namespace } returns "tenant-one"

        val result = fixture.pipeline.execute(fixture.command, executionOptions(UUID.randomUUID(), "tenant-two"))

        assertFalse(result.isSuccess)
        assertEquals(ValidationResultSeverity.Error, result.validationResults.single().severity)
        assertEquals(ValidationResultReasons.DEPENDENCY_UNAVAILABLE, result.validationResults.single().reason)
        coVerify(exactly = 0) {
            fixture.eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
        }
    }

    @Test
    fun `provider failure returns dependency unavailable without appending`() = runBlocking {
        val event = SomethingHappened("not-appended")
        val fixture = fixture(KeyedCommand("shared-key", event)) { command -> (command as KeyedCommand).key }
        every { fixture.eventStore.namespace } returns "default"
        val provider = TenantEventStoreProvider { error("provider failed") }
        val resolver = tenantEventStoreResolver(fixture.eventStore, provider)
        val responseHandler = ChronicleCommandResponseValueHandler(resolver, fixture.registry)
        val pipeline = DefaultCommandPipeline(fixture.registry, responseValueHandlers = listOf(responseHandler))

        val result = pipeline.execute(fixture.command, executionOptions(UUID.randomUUID(), "tenant-one"))

        assertFalse(result.isSuccess)
        assertEquals(ValidationResultReasons.DEPENDENCY_UNAVAILABLE, result.validationResults.single().reason)
        assertTrue(result.exceptionMessages.isEmpty())
        coVerify(exactly = 0) {
            fixture.eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
        }
    }

    @Test
    fun `resolver returning a mismatched store fails closed without appending`() = runBlocking {
        val event = SomethingHappened("not-appended")
        val fixture = fixture(KeyedCommand("shared-key", event)) { command -> (command as KeyedCommand).key }
        every { fixture.eventStore.namespace } returns "tenant-two"
        val responseHandler = ChronicleCommandResponseValueHandler(
            TenantEventStoreResolver { fixture.eventStore },
            fixture.registry
        )
        val pipeline = DefaultCommandPipeline(fixture.registry, responseValueHandlers = listOf(responseHandler))

        val result = pipeline.execute(fixture.command, executionOptions(UUID.randomUUID(), "tenant-one"))

        assertFalse(result.isSuccess)
        assertEquals(ValidationResultReasons.DEPENDENCY_UNAVAILABLE, result.validationResults.single().reason)
        coVerify(exactly = 0) {
            fixture.eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
        }
    }

    @Test
    fun `single event appends with explicit command append options and is consumed`() = runBlocking {
        val event = SomethingHappened("one")
        val correlationId = UUID.randomUUID()
        val appendOptions = slot<AppendOptions>()
        val fixture = fixture(KeyedCommand("source-1", event)) { command -> (command as KeyedCommand).key }
        coEvery {
            fixture.eventLog.append("source-1", event, capture(appendOptions))
        } returns successfulAppend()

        val result = fixture.pipeline.execute(fixture.command, executionOptions(correlationId))

        assertTrue(result.isSuccess)
        assertNull(result.response)
        assertEquals(correlationId, appendOptions.captured.correlationId)
        val causation = appendOptions.captured.causation.single()
        assertEquals("Command", causation.type.name)
        assertEquals(KeyedCommand::class.java.simpleName, causation.properties["commandType"])
        assertEquals(KeyedCommand::class.java.name, causation.properties["commandTypeFullName"])
        assertEquals("source-1", causation.properties["commandKey"])
    }

    @Test
    fun `parallel dispatcher hops keep command append options isolated`() = runBlocking {
        val firstCorrelation = UUID.randomUUID()
        val secondCorrelation = UUID.randomUUID()
        val first = KeyedCommand("first-key", SomethingHappened("first"))
        val second = KeyedCommand("second-key", SomethingHappened("second"))
        val fixture = fixture(first) { command -> (command as KeyedCommand).key }
        val captured = Collections.synchronizedList(mutableListOf<AppendOptions>())
        coEvery {
            fixture.eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
        } coAnswers {
            captured.add(thirdArg<AppendOptions>())
            successfulAppend()
        }

        val results = withContext(Dispatchers.Default) {
            listOf(
                async {
                    fixture.pipeline.execute(
                        first,
                        executionOptions(firstCorrelation)
                    )
                },
                async {
                    fixture.pipeline.execute(
                        second,
                        executionOptions(secondCorrelation)
                    )
                }
            ).awaitAll()
        }

        assertTrue(results.all { it.isSuccess })
        val options = captured.associateBy(AppendOptions::correlationId)
        assertEquals(setOf(firstCorrelation, secondCorrelation), options.keys)
        assertEquals("first-key", options.getValue(firstCorrelation).causation.single().properties["commandKey"])
        assertEquals("second-key", options.getValue(secondCorrelation).causation.single().properties["commandKey"])
    }

    @Test
    fun `multiple events append atomically to one event source with explicit correlation`() = runBlocking {
        val events = listOf(SomethingHappened("one"), SomethingHappened("two"))
        val correlationId = UUID.randomUUID()
        val appendOptions = slot<AppendOptions>()
        val fixture = fixture(KeyedCommand(UUID.fromString("71360956-01a0-4ebc-a186-cb98f00d4ea4"), events)) {
            command -> (command as KeyedCommand).key
        }
        coEvery {
            fixture.eventLog.appendMany(
                "71360956-01a0-4ebc-a186-cb98f00d4ea4",
                events,
                capture(appendOptions)
            )
        } returns listOf(successfulAppend(1), successfulAppend(2))

        val result = fixture.pipeline.execute(fixture.command, executionOptions(correlationId))

        assertTrue(result.isSuccess)
        assertNull(result.response)
        assertEquals(correlationId, appendOptions.captured.correlationId)
    }

    @Test
    fun `supported scalar and concept keys become stable event source ids`() = runBlocking {
        val uuid = UUID.fromString("27037072-fbb7-440a-b72e-5c0f9683981a")
        val keys = listOf<Any>(
            "string-key",
            uuid,
            42,
            ArcOrderId(uuid),
            ChronicleOrderId(12L)
        )
        val expected = listOf("string-key", uuid.toString(), "42", uuid.toString(), "12")

        keys.zip(expected).forEach { (key, eventSourceId) ->
            val event = SomethingHappened(eventSourceId)
            val fixture = fixture(KeyedCommand(key, event)) { command -> (command as KeyedCommand).key }
            coEvery { fixture.eventLog.append(eventSourceId, event, any<AppendOptions>()) } returns successfulAppend()

            val result = fixture.pipeline.execute(fixture.command, executionOptions(UUID.randomUUID()))

            assertTrue(result.isSuccess)
            coVerify(exactly = 1) { fixture.eventLog.append(eventSourceId, event, any<AppendOptions>()) }
        }
    }

    @Test
    fun `command key provider supplies key when generated handler has no key`() = runBlocking {
        val event = SomethingHappened("provided")
        val command = ProviderCommand("provider-key", event)
        val fixture = fixture(command)
        coEvery { fixture.eventLog.append("provider-key", event, any<AppendOptions>()) } returns successfulAppend()

        val result = fixture.pipeline.execute(command, executionOptions(UUID.randomUUID()))

        assertTrue(result.isSuccess)
    }

    @Test
    fun `routed events append as one cross-stream batch with explicit correlation`() = runBlocking {
        val correlationId = UUID.randomUUID()
        val events = listOf(
            EventForEventSourceId("first", SomethingHappened("one")),
            EventForEventSourceId("second", SomethingHappened("two"))
        )
        val fixture = fixture(KeyedCommand(UnsupportedKey, events)) { command -> (command as KeyedCommand).key }
        coEvery {
            fixture.eventLog.appendMany(
                match<List<EventForEventSourceId>> { routed ->
                    routed.map { it.eventSourceId to it.event } == events.map { it.eventSourceId to it.event }
                },
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                correlationId
            )
        } returns listOf(successfulAppend(1), successfulAppend(2))

        val result = fixture.pipeline.execute(fixture.command, executionOptions(correlationId))

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            fixture.eventLog.appendMany(
                match<List<EventForEventSourceId>> { routed ->
                    routed.map { it.eventSourceId to it.event } == events.map { it.eventSourceId to it.event }
                },
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                correlationId
            )
        }
    }

    @Test
    fun `constraint concurrency and append errors are mapped in event order`() = runBlocking {
        val events = listOf(
            SomethingHappened("constraint"),
            SomethingHappened("concurrency"),
            SomethingHappened("error")
        )
        val fixture = fixture(KeyedCommand("source", events)) { command -> (command as KeyedCommand).key }
        coEvery {
            fixture.eventLog.appendMany("source", events, any<AppendOptions>())
        } returns listOf(
            failedAppend(
                constraints = listOf(
                    ConstraintViolation("unique-name", "The name is already used.", mapOf("name" to "taken"))
                )
            ),
            failedAppend(
                concurrency = ConcurrencyViolation("source", EventSequenceNumber(3), EventSequenceNumber(4))
            ),
            failedAppend(errors = listOf(AppendError("The event store is unavailable.")))
        )

        val result = fixture.pipeline.execute(fixture.command, executionOptions(UUID.randomUUID()))

        assertFalse(result.isSuccess)
        assertNull(result.response)
        assertEquals(
            listOf(ValidationResultReasons.CONSTRAINT_VIOLATION, ValidationResultReasons.CONCURRENCY_VIOLATION),
            result.validationResults.map(ValidationResult::reason)
        )
        assertEquals("unique-name", result.validationResults[0].reasonDetail)
        assertEquals(mapOf("name" to "taken"), result.validationResults[0].state)
        assertEquals("source", result.validationResults[1].reasonDetail)
        assertEquals(mapOf("expectedSequenceNumber" to 3L, "actualSequenceNumber" to 4L), result.validationResults[1].state)
        assertEquals(listOf("The event store is unavailable."), result.exceptionMessages)
    }

    @Test
    fun `repeated batch concurrency violation is mapped once`() {
        val violation = ConcurrencyViolation("source", EventSequenceNumber(1), EventSequenceNumber(2))
        val repeatedBatchResult = AppendResult(
            EventSequenceNumber.unavailable,
            emptyList(),
            emptyList(),
            false,
            violation
        )
        val command = KeyedCommand("source", SomethingHappened("event"))

        val result = appendResultsToCommandResult(
            commandContext(command),
            listOf(repeatedBatchResult, repeatedBatchResult),
            2
        )

        assertEquals(listOf("source"), result.validationResults.map(ValidationResult::reasonDetail))
        assertEquals(
            listOf(mapOf("expectedSequenceNumber" to 1L, "actualSequenceNumber" to 2L)),
            result.validationResults.map(ValidationResult::state)
        )
        assertTrue(result.exceptionMessages.isEmpty())
    }

    @Test
    fun `repeated batch constraints and errors are mapped once by full value and keep count checks`() {
        val firstConstraint = ConstraintViolation("first-rule", "Rejected.", mapOf("value" to "one"))
        val secondConstraint = ConstraintViolation("second-rule", "Rejected.", mapOf("value" to "two"))
        val firstError = AppendError("First append error.")
        val secondError = AppendError("Second append error.")
        val repeatedBatchResult = AppendResult(
            EventSequenceNumber.unavailable,
            listOf(firstConstraint, secondConstraint),
            listOf(firstError, secondError),
            false,
            null
        )
        val command = KeyedCommand("source", SomethingHappened("event"))

        val result = appendResultsToCommandResult(
            commandContext(command),
            listOf(repeatedBatchResult, repeatedBatchResult),
            3
        )

        assertEquals(
            listOf("first-rule", "second-rule"),
            result.validationResults.map(ValidationResult::reasonDetail)
        )
        assertEquals(
            listOf(mapOf("value" to "one"), mapOf("value" to "two")),
            result.validationResults.map(ValidationResult::state)
        )
        assertEquals(
            listOf(
                "First append error.",
                "Second append error.",
                "Chronicle returned 2 append results for 3 events."
            ),
            result.exceptionMessages
        )
    }

    @Test
    fun `missing blank null and unsupported command keys are validation failures`() = runBlocking {
        listOf<Any?>(null, "", " \t", "source\nkey", UnsupportedKey).forEach { key ->
            val event = SomethingHappened("not-appended")
            val fixture = fixture(KeyedCommand(key, event)) { command -> (command as KeyedCommand).key }

            val result = fixture.pipeline.execute(fixture.command, executionOptions(UUID.randomUUID()))

            assertFalse(result.isSuccess)
            assertEquals("commandKey", result.validationResults.single().reasonDetail)
            assertTrue(result.exceptionMessages.isEmpty())
            coVerify(exactly = 0) {
                fixture.eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
            }
        }
    }

    @Test
    fun `blank and control character routed event source ids are validation failures`() = runBlocking {
        val events = listOf(
            EventForEventSourceId(" \t", SomethingHappened("blank")),
            EventForEventSourceId("source\u0000key", SomethingHappened("control"))
        )
        val fixture = fixture(KeyedCommand("unused", events)) { command -> (command as KeyedCommand).key }

        val result = fixture.pipeline.execute(fixture.command, executionOptions(UUID.randomUUID()))

        assertFalse(result.isSuccess)
        assertEquals("eventSourceId", result.validationResults.single().reasonDetail)
        coVerify(exactly = 0) {
            fixture.eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                any<Map<String, ConcurrencyScope>>(),
                any<UUID>()
            )
        }
    }

    @Test
    fun `malformed direct routed wrapper is owned and fails without becoming a client response`() = runBlocking {
        assertMalformedRoutedResponseIsRejected(
            EventForEventSourceId("source", NotAnEvent("malformed"))
        )
    }

    @Test
    fun `malformed routed collections and arrays are owned and fail without becoming client responses`() = runBlocking {
        listOf<Any>(
            listOf(EventForEventSourceId("source", NotAnEvent("collection"))),
            arrayOf(EventForEventSourceId("source", NotAnEvent("array")))
        ).forEach { response ->
            assertMalformedRoutedResponseIsRejected(response)
        }
    }

    @Test
    fun `mixed valid and malformed routed wrappers fail atomically without becoming client responses`() = runBlocking {
        listOf<Any>(
            listOf(
                EventForEventSourceId("valid", SomethingHappened("valid")),
                EventForEventSourceId("invalid", NotAnEvent("invalid"))
            ),
            arrayOf(
                EventForEventSourceId("valid", SomethingHappened("valid")),
                EventForEventSourceId("invalid", NotAnEvent("invalid"))
            )
        ).forEach { response ->
            assertMalformedRoutedResponseIsRejected(response)
        }
    }

    @Test
    fun `heterogeneous collections and arrays containing routed wrappers fail atomically without client leakage`() =
        runBlocking {
            listOf<Any>(
                listOf(
                    EventForEventSourceId("routed", SomethingHappened("routed")),
                    SomethingHappened("plain")
                ),
                arrayOf<Any>(
                    EventForEventSourceId("routed", SomethingHappened("routed")),
                    "client-value"
                ),
                listOf(
                    EventForEventSourceId("malformed", NotAnEvent("malformed")),
                    SomethingHappened("plain")
                ),
                arrayOf<Any>(
                    EventForEventSourceId("malformed", NotAnEvent("malformed")),
                    EventForEventSourceId("valid", SomethingHappened("valid")),
                    "client-value"
                )
            ).forEach { response ->
                assertMalformedRoutedResponseIsRejected(response)
            }
        }

    @Test
    fun `empty statically handled routed collection succeeds without resolving or appending`() = runBlocking {
        val descriptor = CommandResponseValueDescriptor(
            EventForEventSourceId::class.java.name,
            true,
            CommandResponseValueDisposition.HANDLED
        )
        listOf<Any>(emptyList<EventForEventSourceId>(), emptyArray<EventForEventSourceId>()).forEach { response ->
            val command = KeyedCommand("unused", response)
            val fixture = fixture(command, listOf(descriptor)) { typed -> (typed as KeyedCommand).key }
            val handler = ChronicleCommandResponseValueHandler(fixture.eventStore, fixture.registry)

            assertTrue(handler.canHandle(commandContext(command), response))

            val result = fixture.pipeline.execute(command, executionOptions(UUID.randomUUID()))

            assertTrue(result.isSuccess)
            assertNull(result.response)
            coVerify(exactly = 0) {
                fixture.eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
            }
            coVerify(exactly = 0) {
                fixture.eventLog.appendMany(
                    any<List<EventForEventSourceId>>(),
                    any<Map<String, ConcurrencyScope>>(),
                    any<UUID>()
                )
            }
        }
    }

    @Test
    fun `empty statically handled routed collection fails closed when transaction state is missing`() = runBlocking {
        val descriptor = CommandResponseValueDescriptor(
            EventForEventSourceId::class.java.name,
            true,
            CommandResponseValueDisposition.HANDLED
        )
        val response = emptyList<EventForEventSourceId>()
        val command = KeyedCommand("unused", response)
        val fixture = fixture(command, listOf(descriptor)) { typed -> (typed as KeyedCommand).key }
        val transactions = ChronicleCommandTransaction()
        val handler = ChronicleCommandResponseValueHandler(fixture.eventStore, fixture.registry, transactions)
        val pipeline = DefaultCommandPipeline(fixture.registry, responseValueHandlers = listOf(handler))

        val result = pipeline.execute(command, executionOptions(UUID.randomUUID()))

        assertFalse(result.isSuccess)
        assertEquals(
            "No active Chronicle transaction exists for this command execution root.",
            result.exceptionMessages.single()
        )
        coVerify(exactly = 0) {
            fixture.eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
        }
        coVerify(exactly = 0) {
            fixture.eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                any<Map<String, ConcurrencyScope>>(),
                any<UUID>()
            )
        }
    }

    @Test
    fun `empty collections validation collections and non-events are not event responses`() {
        val command = KeyedCommand("source", "response")
        val fixture = fixture(command) { typed -> (typed as KeyedCommand).key }
        val handler = ChronicleCommandResponseValueHandler(fixture.eventStore, fixture.registry)
        val context = commandContext(command)

        assertFalse(handler.canHandle(context, emptyList<Any>()))
        assertFalse(
            handler.canHandle(
                context,
                listOf(ValidationResult(ValidationResultSeverity.Error, "Not an event."))
            )
        )
        assertFalse(handler.canHandle(context, arrayOf("not-an-event")))
    }

    private suspend fun assertMalformedRoutedResponseIsRejected(response: Any) {
        val command = KeyedCommand("unused", response)
        val fixture = fixture(command) { typed -> (typed as KeyedCommand).key }
        val handler = ChronicleCommandResponseValueHandler(fixture.eventStore, fixture.registry)

        assertTrue(handler.canHandle(commandContext(command), response))

        val result = fixture.pipeline.execute(command, executionOptions(UUID.randomUUID()))

        assertFalse(result.isSuccess)
        assertNull(result.response)
        assertEquals(ValidationResultReasons.RULE, result.validationResults.single().reason)
        assertEquals("events", result.validationResults.single().reasonDetail)
        coVerify(exactly = 0) {
            fixture.eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
        }
        coVerify(exactly = 0) {
            fixture.eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                any<Map<String, ConcurrencyScope>>(),
                any<UUID>()
            )
        }
    }

    private fun fixture(
        command: Any,
        responseValues: List<CommandResponseValueDescriptor> = emptyList(),
        key: ((Any) -> Any?)? = null
    ): Fixture {
        val eventLog = mockk<IEventLog>()
        val eventStore = mockk<IEventStore>()
        every { eventStore.eventLog } returns eventLog
        val registry = ConcurrentCommandHandlerRegistry()
        registry.register(TestCommandHandler(command, responseValues, key))
        val responseHandler = ChronicleCommandResponseValueHandler(eventStore, registry)
        val pipeline = DefaultCommandPipeline(registry, responseValueHandlers = listOf(responseHandler))
        return Fixture(command, eventStore, eventLog, registry, pipeline)
    }

    private fun successfulAppend(sequenceNumber: Long = 0): AppendResult = AppendResult(
        EventSequenceNumber(sequenceNumber),
        emptyList(),
        emptyList(),
        true,
        null
    )

    private fun failedAppend(
        constraints: List<ConstraintViolation> = emptyList(),
        errors: List<AppendError> = emptyList(),
        concurrency: ConcurrencyViolation? = null
    ): AppendResult = AppendResult(
        EventSequenceNumber.unavailable,
        constraints,
        errors,
        false,
        concurrency
    )

    private fun executionOptions(
        correlationId: UUID,
        tenantNamespace: String? = null,
        principal: ArcPrincipal = ArcPrincipal.anonymous()
    ): CommandExecutionOptions = CommandExecutionOptions(
        correlationId,
        principal,
        EmptyServiceResolver,
        tenantId = tenantNamespace,
        tenantNamespace = tenantNamespace
    )

    private fun commandContext(command: Any): CommandContext = CommandContext(
        UUID.randomUUID(),
        command,
        command.javaClass,
        ArcPrincipal.anonymous(),
        serviceResolver = EmptyServiceResolver
    )

    private class TestCommandHandler(
        private val command: Any,
        responseValues: List<CommandResponseValueDescriptor> = emptyList(),
        private val key: ((Any) -> Any?)? = null
    ) : CommandHandler {
        override val commandType: Class<*> = command.javaClass
        override val metadata: CommandDescriptor = CommandDescriptor.withResponseValues(
            commandType.simpleName,
            commandType.name,
            responseValues
        )

        override fun resolveCommandKey(command: Any): Any? = key?.invoke(command) ?: super.resolveCommandKey(command)

        override suspend fun invoke(context: CommandContext): Any = when (val typed = context.command) {
            is KeyedCommand -> typed.response
            is ProviderCommand -> typed.response
            else -> error("Unexpected command type '${typed.javaClass.name}'.")
        }
    }

    private data class Fixture(
        val command: Any,
        val eventStore: IEventStore,
        val eventLog: IEventLog,
        val registry: ConcurrentCommandHandlerRegistry,
        val pipeline: DefaultCommandPipeline
    )

    @Command
    private data class KeyedCommand(val key: Any?, val response: Any)

    @Command
    private data class ProviderCommand(
        private val key: String,
        val response: Any
    ) : CommandKeyProvider {
        override fun commandKey(): Any = key
    }

    @EventType
    private data class SomethingHappened(val value: String)

    private data class NotAnEvent(val value: String)

    private data class ArcOrderId(private val id: UUID) : ArcConceptAs<UUID> {
        override fun value(): UUID = id
    }

    private data class ChronicleOrderId(override val value: Long) : ChronicleConceptAs<Long>

    private data object UnsupportedKey

    private data object EmptyServiceResolver : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
