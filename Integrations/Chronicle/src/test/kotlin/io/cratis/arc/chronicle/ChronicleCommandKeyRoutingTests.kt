// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandKeyProvider
import io.cratis.arc.commands.CommandResponseValues
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.results.CommandResult
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.events.EventType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class ChronicleCommandKeyRoutingTests {
    @ParameterizedTest(name = "{0}, staged={1}, throwOnSecondCall={2}")
    @CsvSource(
        "single,false,false", "single,false,true", "single,true,false", "single,true,true",
        "many,false,false", "many,false,true", "many,true,false", "many,true,true",
        "mixed,false,false", "mixed,false,true", "mixed,true,false", "mixed,true,true",
        "separate,true,false", "separate,true,true"
    )
    fun `routing retains the key captured before validation and command mutation`(
        shape: String,
        staged: Boolean,
        throwOnSecondCall: Boolean
    ): Unit = runBlocking {
        val command = ChangingKeyCommand("captured-source", throwOnSecondCall)
        val sink = ChronicleRoutingEventSink()
        val response = response(shape)
        val result = pipeline(command, response, staged, sink).execute(command, options())

        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        assertNull(result.response)
        assertEquals(1, command.keyCalls)
        assertEquals("changed-source", command.key)
        assertEquals(1, sink.appendCalls)
        assertEquals(
            if (staged || shape == "mixed") "routed-batch" else if (shape == "single") "single" else "plain-batch",
            sink.appendKind
        )
        assertEquals(
            when (shape) {
                "single" -> listOf("captured-source")
                "many", "separate" -> listOf("captured-source", "captured-source")
                else -> listOf("captured-source", "explicit-source")
            },
            sink.events.map { it.eventSourceId }
        )
        sink.events.forEach { event ->
            assertEquals("captured-source", event.causation.single().properties["commandKey"])
        }
        if (staged) assertEquals(1, sink.events.map { it.causation }.distinct().size)
    }

    @ParameterizedTest(name = "{0}, staged={1}")
    @CsvSource("single,false", "single,true", "many,false", "many,true", "mixed,false", "mixed,true", "routed,false", "routed,true")
    fun `null captured key fails closed only when a plain event needs routing`(shape: String, staged: Boolean): Unit = runBlocking {
        val command = ChangingKeyCommand(null, false)
        val sink = ChronicleRoutingEventSink()
        val result = pipeline(command, response(shape), staged, sink).execute(command, options())

        assertEquals(1, command.keyCalls)
        assertEquals("changed-source", command.key)
        assertNull(result.response)
        assertTrue(result.exceptionMessages.isEmpty())
        if (shape == "routed") {
            assertTrue(result.isSuccess)
            assertEquals(listOf("explicit-source"), sink.events.map { it.eventSourceId })
            assertFalse(sink.events.single().causation.single().properties.containsKey("commandKey"))
        } else {
            assertFalse(result.isSuccess)
            assertEquals("commandKey", result.validationResults.single().reasonDetail)
            assertEquals(0, sink.appendCalls)
            assertTrue(sink.events.isEmpty())
        }
    }

    @Test
    fun `manual contexts require an explicit handler-resolved key without registry fallback`(): Unit = runBlocking {
        for (shape in listOf("single", "mixed")) {
            val command = Any()
            var keyCalls = 0
            val registry = ConcurrentCommandHandlerRegistry().apply {
                register(object : CommandHandler {
                    override val commandType: Class<*> = command.javaClass
                    override val metadata = CommandDescriptor("ManualCommand", commandType.name)
                    override fun resolveCommandKey(command: Any): Any {
                        keyCalls++
                        return "registry-source"
                    }
                    override suspend fun invoke(context: CommandContext): Any = response(shape)
                })
            }
            val sink = ChronicleRoutingEventSink()
            val handler = ChronicleCommandResponseValueHandler(sink.eventStore, registry)
            val missing = CommandContext(
                UUID.randomUUID(), command, command.javaClass, ArcPrincipal.anonymous(), serviceResolver = Services
            )
            val rejected = handler.handle(missing, response(shape))
            assertFalse(rejected.isSuccess)
            assertEquals("commandKey", rejected.validationResults.single().reasonDetail)
            assertEquals(0, keyCalls)
            assertEquals(0, sink.appendCalls)

            val explicit = CommandContext(
                UUID.randomUUID(), command, command.javaClass, ArcPrincipal.anonymous(),
                serviceResolver = Services, commandKey = "explicit-command-key"
            )
            assertTrue(handler.handle(explicit, response(shape)).isSuccess)
            assertEquals(0, keyCalls)
            assertEquals("explicit-command-key", sink.events.first().eventSourceId)
            assertEquals("explicit-command-key", sink.events.first().causation.single().properties["commandKey"])
        }
    }

    @Test
    fun `manual context provider default captures once and explicit null never invokes the provider`(): Unit = runBlocking {
        val sink = ChronicleRoutingEventSink()
        val handler = ChronicleCommandResponseValueHandler(sink.eventStore, ConcurrentCommandHandlerRegistry())
        val command = ChangingKeyCommand("manual-source", true)
        val captured = CommandContext(
            UUID.randomUUID(), command, command.javaClass, ArcPrincipal.anonymous(), serviceResolver = Services
        )
        command.key = "changed-source"
        assertTrue(handler.handle(captured, response("single")).isSuccess)
        assertEquals(1, command.keyCalls)
        assertEquals("manual-source", sink.events.single().eventSourceId)

        val explicitNull = CommandContext(
            UUID.randomUUID(), command, command.javaClass, ArcPrincipal.anonymous(),
            serviceResolver = Services, commandKey = null
        )
        val rejected = handler.handle(explicitNull, response("mixed"))
        assertFalse(rejected.isSuccess)
        assertEquals("commandKey", rejected.validationResults.single().reasonDetail)
        assertEquals(1, command.keyCalls)
        assertEquals(1, sink.appendCalls)
    }

    private fun pipeline(
        command: ChangingKeyCommand,
        response: Any,
        staged: Boolean,
        sink: ChronicleRoutingEventSink
    ): DefaultCommandPipeline {
        val capturedKey = command.key
        val registry = ConcurrentCommandHandlerRegistry().apply {
            register(object : CommandHandler {
                override val commandType: Class<*> = ChangingKeyCommand::class.java
                override val metadata = CommandDescriptor("ChangingKeyCommand", commandType.name)
                override suspend fun invoke(context: CommandContext): Any {
                    assertEquals(capturedKey, context.commandKey)
                    command.key = "changed-source"
                    return response
                }
            })
        }
        val validation = object : CommandFilter {
            override suspend fun execute(context: CommandContext): CommandResult<*> {
                assertEquals(capturedKey, context.commandKey)
                assertEquals(1, command.keyCalls)
                return CommandResult.success(context.correlationId)
            }
        }
        val transactions = if (staged) ChronicleCommandTransaction() else null
        return DefaultCommandPipeline(
            registry,
            commandFilters = listOf(validation),
            executionScopes = transactions?.let { listOf(ChronicleCommandExecutionScope(it)) }.orEmpty(),
            responseValueHandlers = listOf(
                ChronicleCommandResponseValueHandler(TenantEventStoreResolver { sink.eventStore }, registry, transactions)
            )
        )
    }

    private fun response(shape: String): Any = when (shape) {
        "single" -> RoutingEvent("first")
        "many" -> listOf(RoutingEvent("first"), RoutingEvent("second"))
        "separate" -> CommandResponseValues(listOf(RoutingEvent("first"), RoutingEvent("second")))
        "mixed" -> listOf(RoutingEvent("first"), EventForEventSourceId("explicit-source", RoutingEvent("second")))
        "routed" -> EventForEventSourceId("explicit-source", RoutingEvent("only"))
        else -> error("Unexpected shape '$shape'.")
    }

    private fun options(): CommandExecutionOptions = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), Services)

    private class ChangingKeyCommand(var key: String?, private val throwOnSecondCall: Boolean) : CommandKeyProvider {
        var keyCalls = 0
        override fun commandKey(): Any? {
            keyCalls++
            check(!throwOnSecondCall || keyCalls == 1) { "The command key must not be resolved twice." }
            return key
        }
    }

    @EventType
    private data class RoutingEvent(val value: String)

    private object Services : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
