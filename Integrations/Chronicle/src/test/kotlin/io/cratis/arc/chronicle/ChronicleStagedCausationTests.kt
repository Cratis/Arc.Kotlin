// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import Cratis.Chronicle.Contracts.EventSequences.EventSequencesGrpcKt
import Cratis.Chronicle.Contracts.EventSequences.Eventsequences
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandResponseValues
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.auditing.Causation
import io.cratis.chronicle.auditing.CausationType
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.EventLog
import io.cratis.chronicle.events.EventType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pinned real SDK preflight executes; only the final gRPC transport is mocked. No kernel claim. */
internal class ChronicleStagedCausationTests {
    @Test
    fun `separately enrolled responses from one command pass real SDK single-chain preflight`(): Unit = runBlocking {
        val command = StagedCommand()
        val registry = ConcurrentCommandHandlerRegistry().apply {
            register(object : CommandHandler {
                override val commandType: Class<*> = StagedCommand::class.java
                override val metadata = CommandDescriptor("StagedCommand", commandType.name)
                override fun resolveCommandKey(command: Any): Any = "source"
                override suspend fun invoke(context: CommandContext): Any = CommandResponseValues(listOf(
                    StagedEvent("first"), StagedEvent("second")
                ))
            })
        }
        val fixture = fixture(registry)
        val result = fixture.pipeline.execute(command, options())
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        val batch = fixture.request.captured
        assertEquals(listOf("source", "source"), batch.eventsList.map { it.eventSourceId })
        assertEquals(2, batch.eventsCount)
        assertTrue(batch.eventsList[0].content.contains("first"))
        assertTrue(batch.eventsList[1].content.contains("second"))
        assertEquals(1, batch.causationCount)
        assertEquals("source", batch.causationList.single().propertiesMap["commandKey"])
        coVerify(exactly = 1) { fixture.stub.appendMany(any(), any()) }
    }

    @Test
    fun `explicit heterogeneous lineage is preserved and rejected rather than flattened or split`(): Unit = runBlocking {
        val registry = ConcurrentCommandHandlerRegistry().apply {
            register(object : CommandHandler {
                override val commandType: Class<*> = StagedCommand::class.java
                override val metadata = CommandDescriptor("StagedCommand", commandType.name)
                override suspend fun invoke(context: CommandContext): Any = listOf(
                    EventForEventSourceId("source", StagedEvent("first"), causation = listOf(
                        Causation(Instant.EPOCH, CausationType("Explicit"), mapOf("source" to "first"))
                    )),
                    EventForEventSourceId("source", StagedEvent("second"), causation = listOf(
                        Causation(Instant.EPOCH, CausationType("Explicit"), mapOf("source" to "second"))
                    ))
                )
            })
        }
        val fixture = fixture(registry)
        val result = fixture.pipeline.execute(StagedCommand(), options())
        assertFalse(result.isSuccess)
        coVerify(exactly = 0) { fixture.stub.appendMany(any(), any()) }
    }

    private fun fixture(registry: ConcurrentCommandHandlerRegistry): Fixture {
        val request = slot<Eventsequences.AppendManyRequest>()
        val stub = mockk<EventSequencesGrpcKt.EventSequencesCoroutineStub>()
        coEvery { stub.appendMany(capture(request), any()) } returns Eventsequences.AppendManyResponse.newBuilder()
            .addSequenceNumbers(1).addSequenceNumbers(2).build()
        val log = EventLog("fixture", "default", stub, unitOfWorkManager = mockk())
        val store = mockk<IEventStore>()
        every { store.namespace } returns "default"
        every { store.eventLog } returns log
        val transactions = ChronicleCommandTransaction()
        val pipeline = DefaultCommandPipeline(registry,
            executionScopes = listOf(ChronicleCommandExecutionScope(transactions)),
            responseValueHandlers = listOf(ChronicleCommandResponseValueHandler(store, registry, transactions)))
        return Fixture(pipeline, request, stub)
    }

    private fun options() = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), object : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    })

    private data class Fixture(
        val pipeline: DefaultCommandPipeline,
        val request: io.mockk.CapturingSlot<Eventsequences.AppendManyRequest>,
        val stub: EventSequencesGrpcKt.EventSequencesCoroutineStub
    )

    private class StagedCommand
    @EventType private data class StagedEvent(val value: String)
}
