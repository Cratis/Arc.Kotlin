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
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.chronicle.IEventStore
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
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ChronicleNestedCommandTransactionTests {
    @Test
    fun `all successful nested frames commit once in enrollment order`() = runBlocking {
        val fixture = Fixture()
        val childEvent = SomethingHappened("child")
        val outerEvent = SomethingHappened("outer")
        fixture.respond("child", childEvent)
        fixture.respond("outer") {
            assertTrue(fixture.execute("child").isSuccess)
            outerEvent
        }
        val expected = listOf(
            EventForEventSourceId("child", childEvent),
            EventForEventSourceId("outer", outerEvent)
        )
        fixture.accept(expected)

        val result = fixture.execute("outer")

        assertTrue(result.isSuccess)
        fixture.verifyAppend(expected)
    }

    @Test
    fun `three nested levels share one root batch and only the root commits`() = runBlocking {
        val fixture = Fixture()
        val events = listOf(
            SomethingHappened("grandchild"),
            SomethingHappened("child"),
            SomethingHappened("outer")
        )
        fixture.respond("grandchild", events[0])
        fixture.respond("child") {
            assertTrue(fixture.execute("grandchild").isSuccess)
            events[1]
        }
        fixture.respond("outer") {
            assertTrue(fixture.execute("child").isSuccess)
            events[2]
        }
        val expected = listOf(
            EventForEventSourceId("grandchild", events[0]),
            EventForEventSourceId("child", events[1]),
            EventForEventSourceId("outer", events[2])
        )
        fixture.accept(expected)

        assertTrue(fixture.execute("outer").isSuccess)

        fixture.verifyAppend(expected)
    }

    @Test
    fun `nested success followed by outer failure rolls the whole root back`() = runBlocking {
        val fixture = Fixture()
        fixture.respond("child", SomethingHappened("child"))
        fixture.respond("outer") {
            assertTrue(fixture.execute("child").isSuccess)
            CommandResponseValues.of(
                SomethingHappened("outer"),
                ValidationResult(ValidationResultSeverity.Error, "outer failed")
            )
        }

        val result = fixture.execute("outer")

        assertFalse(result.isSuccess)
        assertEquals("outer failed", result.validationResults.single().message)
        fixture.verifyNoAppend()
    }

    @Test
    fun `ignored child failure marks the root rollback only`() = runBlocking {
        val fixture = Fixture()
        fixture.respond("child") {
            CommandResponseValues.of(
                SomethingHappened("child"),
                ValidationResult(ValidationResultSeverity.Error, "child failed")
            )
        }
        fixture.respond("outer") {
            assertFalse(fixture.execute("child").isSuccess)
            SomethingHappened("outer")
        }

        val result = fixture.execute("outer")

        assertFalse(result.isSuccess)
        assertNull(result.response)
        fixture.verifyNoAppend()
    }

    @Test
    fun `ignored child cancellation after staging marks the root rollback only`() = runBlocking {
        val fixture = Fixture()
        fixture.respond("child") { context ->
            fixture.transactions.enroll(
                context,
                fixture.eventStore,
                listOf(EventForEventSourceId("child", SomethingHappened("staged")))
            )
            throw CancellationException("child cancelled")
        }
        fixture.respond("outer") {
            var cancelled = false
            try {
                fixture.execute("child")
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            SomethingHappened("outer")
        }

        val result = fixture.execute("outer")

        assertFalse(result.isSuccess)
        fixture.verifyNoAppend()
    }

    @Test
    fun `structured sibling children enroll in one synchronized root batch`() = runBlocking {
        val fixture = Fixture()
        val first = SomethingHappened("first")
        val second = SomethingHappened("second")
        val outer = SomethingHappened("outer")
        fixture.respond("first", first)
        fixture.respond("second", second)
        fixture.respond("outer") {
            coroutineScope {
                listOf("first", "second").map { name ->
                    async(start = CoroutineStart.UNDISPATCHED) { fixture.execute(name) }
                }.awaitAll().forEach { result -> assertTrue(result.isSuccess) }
            }
            outer
        }
        val expected = listOf(
            EventForEventSourceId("first", first),
            EventForEventSourceId("second", second),
            EventForEventSourceId("outer", outer)
        )
        fixture.accept(expected)

        assertTrue(fixture.execute("outer").isSuccess)

        fixture.verifyAppend(expected)
    }

    @Test
    fun `root correlation identifier and root command causation win when child enrolls first`() = runBlocking {
        val fixture = Fixture()
        val childEvent = SomethingHappened("child")
        val outerEvent = SomethingHappened("outer")
        fixture.respond("child", childEvent)
        fixture.respond("outer") {
            assertTrue(fixture.execute("child").isSuccess)
            outerEvent
        }
        val expected = listOf(
            EventForEventSourceId("child", childEvent),
            EventForEventSourceId("outer", outerEvent)
        )
        fixture.accept(expected)

        val result = fixture.execute("outer")

        assertTrue(result.isSuccess)
        fixture.verifyAppend(expected)
    }

    @Test
    fun `root append maps child constraint and concurrency failures to the root result`() = runBlocking {
        val fixture = Fixture()
        val child = SomethingHappened("child")
        val outer = SomethingHappened("outer")
        fixture.respond("child", child)
        fixture.respond("outer") {
            fixture.execute("child")
            outer
        }
        val expected = listOf(
            EventForEventSourceId("child", child),
            EventForEventSourceId("outer", outer)
        )
        coEvery {
            fixture.eventLog.appendMany(
                match<List<EventForEventSourceId>> { actual -> eventsMatch(actual, expected) },
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                match<UUID> { it == fixture.correlationId }
            )
        } returns listOf(
            AppendResult(
                EventSequenceNumber.unavailable,
                listOf(ConstraintViolation("child-constraint", "child rejected", emptyMap())),
                emptyList(),
                false,
                null
            ),
            AppendResult(
                EventSequenceNumber.unavailable,
                emptyList(),
                emptyList(),
                false,
                ConcurrencyViolation("outer", EventSequenceNumber(3), EventSequenceNumber(4))
            )
        )

        val result = fixture.execute("outer")

        assertFalse(result.isSuccess)
        assertEquals(fixture.correlationId, result.correlationId)
        assertEquals(
            listOf(ValidationResultReasons.CONSTRAINT_VIOLATION, ValidationResultReasons.CONCURRENCY_VIOLATION),
            result.validationResults.map(ValidationResult::reason)
        )
    }

    @Test
    fun `one root rejects enrollment in a second store`() = runBlocking {
        val fixture = Fixture()
        val otherStore = mockk<IEventStore>()
        every { otherStore.namespace } returns "default"
        fixture.respond("outer") { context ->
            fixture.transactions.enroll(
                context,
                fixture.eventStore,
                listOf(EventForEventSourceId("first", SomethingHappened("first")))
            )
            fixture.transactions.enroll(
                context,
                otherStore,
                listOf(EventForEventSourceId("second", SomethingHappened("second")))
            )
            null
        }

        val result = fixture.execute("outer")

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionMessages.single().contains("more than one Chronicle event store"))
        fixture.verifyNoAppend()
    }

    @Test
    fun `one root rejects conflicting exact concurrency scopes`() = runBlocking {
        val fixture = Fixture()
        val firstScope = mockk<ConcurrencyScope>()
        val secondScope = mockk<ConcurrencyScope>()
        fixture.respond("outer") { context ->
            fixture.transactions.enroll(
                context,
                fixture.eventStore,
                listOf(EventForEventSourceId("source", SomethingHappened("first"))),
                mapOf("source" to firstScope)
            )
            fixture.transactions.enroll(
                context,
                fixture.eventStore,
                listOf(EventForEventSourceId("source", SomethingHappened("second"))),
                mapOf("source" to secondScope)
            )
            null
        }

        val result = fixture.execute("outer")

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionMessages.single().contains("Conflicting concurrency scopes"))
        fixture.verifyNoAppend()
    }

    @Test
    fun `late enrollment is rejected after the root committed`() = runBlocking {
        val fixture = Fixture()
        val event = SomethingHappened("committed")
        var context: CommandContext? = null
        fixture.respond("outer") { received ->
            context = received
            event
        }
        val expected = listOf(EventForEventSourceId("outer", event))
        fixture.accept(expected)
        assertTrue(fixture.execute("outer").isSuccess)

        val exception = assertThrows(IllegalStateException::class.java) {
            fixture.transactions.enroll(
                requireNotNull(context),
                fixture.eventStore,
                listOf(EventForEventSourceId("late", SomethingHappened("late")))
            )
        }

        assertEquals("No active Chronicle transaction exists for this command execution root.", exception.message)
        fixture.verifyAppend(expected)
    }

    @Test
    fun `transactional handler without a transaction scope fails closed instead of appending immediately`() = runBlocking {
        val fixture = Fixture(includeScope = false)
        fixture.respond("outer", SomethingHappened("must not append"))

        val result = fixture.execute("outer")

        assertFalse(result.isSuccess)
        assertEquals("No active Chronicle transaction exists for this command execution root.", result.exceptionMessages.single())
        fixture.verifyNoAppend()
    }

    @Test
    fun `append exception fails the root after staging and does not retry`() = runBlocking {
        val fixture = Fixture()
        val event = SomethingHappened("staged")
        fixture.respond("outer", event)
        val expected = listOf(EventForEventSourceId("outer", event))
        coEvery {
            fixture.eventLog.appendMany(
                match<List<EventForEventSourceId>> { actual -> eventsMatch(actual, expected) },
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                match<UUID> { it == fixture.correlationId }
            )
        } throws IllegalStateException("append failed")

        val result = fixture.execute("outer")

        assertFalse(result.isSuccess)
        assertEquals(listOf("append failed"), result.exceptionMessages)
        fixture.verifyAppend(expected)
    }

    @Test
    fun `append cancellation is propagated and is never retried`() {
        val fixture = Fixture()
        val event = SomethingHappened("staged")
        fixture.respond("outer", event)
        val expected = listOf(EventForEventSourceId("outer", event))
        coEvery {
            fixture.eventLog.appendMany(
                match<List<EventForEventSourceId>> { actual -> eventsMatch(actual, expected) },
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                match<UUID> { it == fixture.correlationId }
            )
        } throws CancellationException("append cancelled")

        val exception = assertThrows(CancellationException::class.java) {
            runBlocking { fixture.execute("outer") }
        }

        assertEquals("append cancelled", exception.message)
        fixture.verifyAppend(expected)
    }

    private class Fixture(includeScope: Boolean = true) {
        val correlationId: UUID = UUID.randomUUID()
        val eventLog: IEventLog = mockk()
        val eventStore: IEventStore = mockk<IEventStore>().also { store ->
            every { store.namespace } returns "default"
            every { store.eventLog } returns eventLog
        }
        val transactions = ChronicleCommandTransaction()
        private val responses = linkedMapOf<String, suspend (CommandContext) -> Any?>()
        private val registry = ConcurrentCommandHandlerRegistry().also { registry ->
            registry.register(NestedCommandHandler(responses))
        }
        private val responseHandler = ChronicleCommandResponseValueHandler(eventStore, registry, transactions)
        private val pipeline = DefaultCommandPipeline(
            registry,
            executionScopes = if (includeScope) listOf(ChronicleCommandExecutionScope(transactions)) else emptyList(),
            responseValueHandlers = listOf(responseHandler)
        )

        fun respond(name: String, response: Any?) {
            responses[name] = { response }
        }

        fun respond(name: String, response: suspend (CommandContext) -> Any?) {
            responses[name] = response
        }

        suspend fun execute(
            name: String,
            principal: ArcPrincipal = ArcPrincipal.anonymous()
        ): CommandResult<*> = pipeline.execute(
            NestedCommand(name),
            CommandExecutionOptions(correlationId, principal, EmptyServiceResolver)
        )

        fun accept(events: List<EventForEventSourceId>) {
            coEvery {
                eventLog.appendMany(
                    match<List<EventForEventSourceId>> { actual -> eventsMatch(actual, events) },
                    match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                    match<UUID> { it == correlationId }
                )
            } returns events.indices.map { index -> successfulAppend(index.toLong()) }
        }

        fun verifyAppend(events: List<EventForEventSourceId>) {
            coVerify(exactly = 1) {
                eventLog.appendMany(
                    match<List<EventForEventSourceId>> { actual -> eventsMatch(actual, events) },
                    match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                    match<UUID> { it == correlationId }
                )
            }
        }

        fun verifyNoAppend() {
            coVerify(exactly = 0) {
                eventLog.appendMany(
                    any<List<EventForEventSourceId>>(),
                    any<Map<String, ConcurrencyScope>>(),
                    any<UUID>()
                )
            }
            coVerify(exactly = 0) {
                eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
            }
        }
    }

    private class NestedCommandHandler(
        private val responses: Map<String, suspend (CommandContext) -> Any?>
    ) : CommandHandler {
        override val commandType: Class<*> = NestedCommand::class.java
        override val metadata: CommandDescriptor = CommandDescriptor(commandType.simpleName, commandType.name)

        override fun resolveCommandKey(command: Any): Any = (command as NestedCommand).name

        override suspend fun invoke(context: CommandContext): Any? =
            checkNotNull(responses[(context.command as NestedCommand).name]) { "Missing test response." }(context)
    }

    @Command
    private data class NestedCommand(val name: String)

    @EventType
    private data class SomethingHappened(val value: String)

    private data object EmptyServiceResolver : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }

    private companion object {
        fun eventsMatch(actual: List<EventForEventSourceId>, expected: List<EventForEventSourceId>): Boolean =
            actual.size == expected.size && actual.zip(expected).all { (actualEvent, expectedEvent) ->
                actualEvent.copy(causation = emptyList()) == expectedEvent &&
                    actualEvent.causation.singleOrNull()?.let { causation ->
                        causation.type.name == "Command" &&
                            causation.properties == mapOf(
                                "commandType" to NestedCommand::class.java.simpleName,
                                "commandTypeFullName" to NestedCommand::class.java.name,
                                "commandKey" to expectedEvent.eventSourceId
                            )
                    } == true
            }

        fun successfulAppend(sequenceNumber: Long): AppendResult = AppendResult(
            EventSequenceNumber(sequenceNumber),
            emptyList(),
            emptyList(),
            true,
            null
        )
    }
}
