// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.springdata.jpa.JpaCommandExecutionScope
import io.cratis.arc.springdata.mongodb.MongoCommandExecutionScope
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.IEventLog
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope
import io.cratis.chronicle.events.EventType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus

internal class ChronicleCommandScopeOrderingTests {
    @Test
    fun `Mongo commit failure prevents Chronicle append`() = runBlocking {
        val fixture = Fixture()
        every { fixture.mongoManager.commit(fixture.mongoStatus) } throws IllegalStateException("Mongo commit failed")

        val result = fixture.execute()

        assertFalse(result.isSuccess)
        assertEquals(listOf("Mongo commit failed"), result.exceptionMessages)
        verify(exactly = 1) { fixture.mongoManager.commit(fixture.mongoStatus) }
        verify(exactly = 1) { fixture.jpaManager.rollback(fixture.jpaStatus) }
        fixture.verifyNoChronicleAppend()
    }

    @Test
    fun `JPA commit failure after Mongo commit prevents Chronicle append`() = runBlocking {
        val fixture = Fixture()
        every { fixture.jpaManager.commit(fixture.jpaStatus) } throws IllegalStateException("JPA commit failed")

        val result = fixture.execute()

        assertFalse(result.isSuccess)
        assertEquals(listOf("JPA commit failed"), result.exceptionMessages)
        verify(exactly = 1) { fixture.mongoManager.commit(fixture.mongoStatus) }
        verify(exactly = 1) { fixture.jpaManager.commit(fixture.jpaStatus) }
        fixture.verifyNoChronicleAppend()
    }

    @Test
    fun `Chronicle failure after local commits is partial and indeterminate rather than distributed atomicity`() =
        runBlocking {
            val fixture = Fixture()
            coEvery {
                fixture.eventLog.appendMany(
                    match<List<EventForEventSourceId>>(fixture::matchesExpectedEvents),
                    match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                    match<UUID> { it == fixture.correlationId }
                )
            } throws IllegalStateException("Chronicle append failed after local commits")

            val result = fixture.execute()

            assertFalse(result.isSuccess)
            assertEquals(listOf("Chronicle append failed after local commits"), result.exceptionMessages)
            verify(exactly = 1) { fixture.mongoManager.commit(fixture.mongoStatus) }
            verify(exactly = 1) { fixture.jpaManager.commit(fixture.jpaStatus) }
            coVerify(exactly = 1) {
                fixture.eventLog.appendMany(
                    match<List<EventForEventSourceId>>(fixture::matchesExpectedEvents),
                    match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                    match<UUID> { it == fixture.correlationId }
                )
            }
        }

    private class Fixture {
        val correlationId: UUID = UUID.randomUUID()
        val jpaStatus: TransactionStatus = mockk()
        val mongoStatus: TransactionStatus = mockk()
        val jpaManager: PlatformTransactionManager = mockk(relaxed = true) {
            every { getTransaction(any()) } returns jpaStatus
        }
        val mongoManager: MongoTransactionManager = mockk(relaxed = true) {
            every { getTransaction(any()) } returns mongoStatus
        }
        val eventLog: IEventLog = mockk()
        private val eventStore: IEventStore = mockk<IEventStore>().also { store ->
            every { store.namespace } returns "default"
            every { store.eventLog } returns eventLog
        }
        private val event = SomethingHappened("ordered")
        val events: List<EventForEventSourceId> = listOf(EventForEventSourceId("command", event))
        private val transactions = ChronicleCommandTransaction()
        private val registry = ConcurrentCommandHandlerRegistry().also { registry ->
            registry.register(TestCommandHandler(event))
        }
        private val pipeline: DefaultCommandPipeline

        init {
            val scopes = mutableListOf<CommandExecutionScope>(
                MongoCommandExecutionScope(mongoManager),
                ChronicleCommandExecutionScope(transactions),
                JpaCommandExecutionScope(jpaManager)
            )
            AnnotationAwareOrderComparator.sort(scopes)
            assertEquals(
                listOf(
                    ChronicleCommandExecutionScope::class.java,
                    JpaCommandExecutionScope::class.java,
                    MongoCommandExecutionScope::class.java
                ),
                scopes.map { scope -> scope.javaClass }
            )
            pipeline = DefaultCommandPipeline(
                registry,
                executionScopes = scopes,
                responseValueHandlers = listOf(
                    ChronicleCommandResponseValueHandler(eventStore, registry, transactions)
                )
            )
        }

        suspend fun execute() = pipeline.execute(
            TestCommand,
            CommandExecutionOptions(correlationId, ArcPrincipal.anonymous(), EmptyServiceResolver)
        )

        fun matchesExpectedEvents(actual: List<EventForEventSourceId>): Boolean =
            actual.size == events.size && actual.zip(events).all { (shaped, expected) ->
                shaped.eventSourceId == expected.eventSourceId &&
                    shaped.event == expected.event &&
                    shaped.eventStreamType == expected.eventStreamType &&
                    shaped.eventStreamId == expected.eventStreamId &&
                    shaped.eventSourceType == expected.eventSourceType &&
                    shaped.tags == expected.tags &&
                    shaped.occurred == expected.occurred &&
                    shaped.subject == expected.subject &&
                    shaped.causation.singleOrNull()?.let { causation ->
                        causation.type.name == "Command" &&
                            causation.properties == mapOf(
                                "commandType" to TestCommand::class.java.simpleName,
                                "commandTypeFullName" to TestCommand::class.java.name,
                                "commandKey" to "command"
                            )
                    } == true
            }

        fun verifyNoChronicleAppend() {
            coVerify(exactly = 0) {
                eventLog.appendMany(
                    any<List<EventForEventSourceId>>(),
                    any<Map<String, ConcurrencyScope>>(),
                    any<UUID>()
                )
            }
        }
    }

    private class TestCommandHandler(private val event: SomethingHappened) : CommandHandler {
        override val commandType: Class<*> = TestCommand::class.java
        override val metadata: CommandDescriptor = CommandDescriptor(commandType.simpleName, commandType.name)

        override fun resolveCommandKey(command: Any): Any = "command"

        override suspend fun invoke(context: CommandContext): Any = event
    }

    private data object TestCommand

    @EventType
    private data class SomethingHappened(val value: String)

    private data object EmptyServiceResolver : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
