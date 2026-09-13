// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.artifacts.CommandEventStreamIdProvider
import io.cratis.arc.artifacts.CommandEventSubjectProvider
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.results.CommandResult
import io.cratis.arc.ExceptionDetailRedactor
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal class CommandContextFailureTest {
    private val correlationId = UUID.fromString("c815ff8a-8476-4618-a1b3-99b72b026df1")
    private val services = object : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `execute converts each context provider failure before any scope begins`(stage: Stage) = runBlocking {
        val fixture = Fixture()
        val result = fixture.pipeline.execute(fixture.command("failed", stage), options())

        assertFailure(result)
        assertEquals(providerPrefix("failed", stage), fixture.calls)
        assertTrue(fixture.completions.isEmpty())
        // A failed root must not poison the next independent root on the same pipeline.
        assertTrue(fixture.pipeline.execute(fixture.command("next"), options()).isSuccess)
        assertEquals(1, fixture.completions.size)
        assertTrue(fixture.completions.single().isSuccess)
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `validate converts each context provider failure without filters or scopes`(stage: Stage) = runBlocking {
        val fixture = Fixture()
        val result = fixture.pipeline.validate(fixture.command("failed", stage), options())

        assertFailure(result)
        assertEquals(providerPrefix("failed", stage), fixture.calls)
        assertTrue(fixture.completions.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `root provider cancellation stays cancellation in execute and validate`(stage: Stage) {
        for (validateOnly in listOf(false, true)) {
            val fixture = Fixture()
            val cancellation = ProviderCancellation(stage)
            val command = fixture.command("cancelled", stage, cancellation)
            val thrown = assertThrows(CancellationException::class.java) {
                runBlocking {
                    if (validateOnly) fixture.pipeline.validate(command, options())
                    else fixture.pipeline.execute(command, options())
                }
            }
            assertSame(cancellation, thrown)
            assertEquals(providerPrefix("cancelled", stage), fixture.calls)
            assertTrue(fixture.completions.isEmpty())
        }
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `ignored nested provider failure marks the root rollback only`(stage: Stage) = runBlocking {
        for (explicitToken in listOf(false, true)) {
            val fixture = Fixture()
            var childResult: CommandResult<*>? = null
            var escaped: Exception? = null
            fixture.operation = { context ->
                try {
                    childResult = fixture.pipeline.execute(
                        fixture.command("child", stage),
                        if (explicitToken) CommandExecutionOptions.nested(context) else options()
                    )
                } catch (exception: Exception) {
                    escaped = exception // Ignoring the pre-fix exception must not permit the root to commit.
                }
                "must be cleared"
            }

            val result = fixture.pipeline.execute(fixture.command("root"), options())

            assertRollbackOnly(result, fixture)
            assertNull(escaped)
            assertFailure(requireNotNull(childResult))
            assertEquals(providerPrefix("child", stage), fixture.calls.filter { it.startsWith("child:") })
        }
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `ignored nested provider cancellation still marks the root rollback only`(stage: Stage) = runBlocking {
        for (explicitToken in listOf(false, true)) {
            val fixture = Fixture()
            val cancellation = ProviderCancellation(stage)
            var caught: CancellationException? = null
            fixture.operation = { context ->
                try {
                    fixture.pipeline.execute(
                        fixture.command("child", stage, cancellation),
                        if (explicitToken) CommandExecutionOptions.nested(context) else options()
                    )
                } catch (exception: CancellationException) {
                    caught = exception
                }
                "must be cleared"
            }

            val result = fixture.pipeline.execute(fixture.command("root"), options())

            assertSame(cancellation, caught)
            assertRollbackOnly(result, fixture)
            assertEquals(providerPrefix("child", stage), fixture.calls.filter { it.startsWith("child:") })
        }
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `validate failure or caught cancellation does not enlist in an active execution`(stage: Stage) = runBlocking {
        for (cancel in listOf(false, true)) {
            val fixture = Fixture()
            val failure = if (cancel) ProviderCancellation(stage) else IllegalStateException("provider failed")
            fixture.operation = { context ->
                try {
                    val child = fixture.pipeline.validate(
                        fixture.command("child", stage, failure), CommandExecutionOptions.nested(context)
                    )
                    assertFalse(cancel)
                    assertFailure(child)
                } catch (exception: CancellationException) {
                    assertTrue(cancel)
                    assertSame(failure, exception)
                }
                "root response"
            }

            val result = fixture.pipeline.execute(fixture.command("root"), options())

            assertTrue(result.isSuccess)
            assertEquals("root response", result.response)
            assertEquals(1, fixture.completions.size)
            assertTrue(fixture.completions.single().isSuccess)
            assertEquals(providerPrefix("child", stage), fixture.calls.filter { it.startsWith("child:") })
        }
    }

    @Test
    fun `provider failures retain core exception detail for host redaction regardless of options`() = runBlocking {
        for (expose in listOf(false, true)) {
            for (validateOnly in listOf(false, true)) {
                val fixture = Fixture()
                val command = fixture.command("failed", Stage.VALUES_FIRST)
                val result = if (validateOnly) fixture.pipeline.validate(command, options(expose))
                else fixture.pipeline.execute(command, options(expose))

                assertFailure(result)
                val serialized = ExceptionDetailRedactor.redact(result, expose)
                assertEquals(correlationId, serialized.correlationId)
                if (expose) assertSame(result, serialized)
                else {
                    assertEquals(listOf(ExceptionDetailRedactor.REDACTED_MESSAGE), serialized.exceptionMessages)
                    assertEquals("", serialized.exceptionStackTrace)
                }
            }
        }
    }

    @Test
    fun `successful providers precede scopes filters preparation and handler with unchanged values`() = runBlocking {
        val fixture = Fixture()
        fixture.operation = { context ->
            assertEquals(mapOf("shared" to "second", "first" to 1), context.values)
            assertEquals("key", context.commandKey)
            assertEquals("stream", context.eventMetadata?.eventStreamId)
            assertEquals("subject", context.eventMetadata?.subject)
            "response"
        }
        val result = fixture.pipeline.execute(fixture.command("success"), options())
        assertTrue(result.isSuccess)
        assertEquals("response", result.response)
        assertEquals(
            providerPrefix("success", Stage.SUBJECT) + listOf(
                "success:begin", "success:filter", "success:prepare", "success:invoke", "success:complete"
            ), fixture.calls
        )

        fixture.calls.clear()
        val validation = fixture.pipeline.validate(fixture.command("validate"), options())
        assertTrue(validation.isSuccess)
        assertNull(validation.response)
        assertEquals(providerPrefix("validate", Stage.SUBJECT) + "validate:filter", fixture.calls)
        assertEquals(1, fixture.completions.size)
    }

    @Test
    fun `different active root tokens remain programmer errors before context providers`() {
        val fixture = Fixture()
        val inherited = CommandExecutionOwner(correlationId, "tenant").createRoot()
        val supplied = CommandExecutionOwner(correlationId, "tenant").createRoot()
        try {
            val thrown = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    withContext(CommandExecutionContext(inherited)) {
                        fixture.pipeline.execute(fixture.command("rejected"), options().join(supplied))
                    }
                }
            }
            assertEquals("A nested command execution cannot join a different active root execution.", thrown.message)
            assertTrue(fixture.calls.isEmpty())
            assertTrue(fixture.completions.isEmpty())
        } finally {
            inherited.executionOwner.close(inherited)
            supplied.executionOwner.close(supplied)
        }
    }

    private fun options(expose: Boolean = false): CommandExecutionOptions = CommandExecutionOptions(
        correlationId, ArcPrincipal.anonymous(), services, tenantNamespace = "tenant", exposeExceptionDetails = expose
    )

    private fun assertFailure(result: CommandResult<*>) {
        assertFalse(result.isSuccess)
        assertTrue(result.hasExceptions)
        assertTrue(result.isAuthorized)
        assertTrue(result.isValid)
        assertEquals(correlationId, result.correlationId)
        assertEquals(listOf("provider failed"), result.exceptionMessages)
        assertTrue(result.exceptionStackTrace.contains("IllegalStateException: provider failed"))
        assertNull(result.response)
    }

    private fun assertRollbackOnly(result: CommandResult<*>, fixture: Fixture) {
        assertFalse(result.isSuccess)
        assertEquals(correlationId, result.correlationId)
        assertEquals(
            listOf("A nested command execution failed; the root execution is rollback-only."), result.exceptionMessages
        )
        assertNull(result.response)
        assertEquals(1, fixture.completions.size)
        assertFalse(fixture.completions.single().isSuccess)
    }

    private fun providerPrefix(name: String, stage: Stage): List<String> =
        Stage.entries.take(stage.ordinal + 1).map { "$name:$it" }

    enum class Stage { VALUES_FIRST, VALUES_SECOND, KEY, STREAM, SUBJECT }

    // No String constructor: coroutine debug stack recovery cannot clone this exception,
    // so identity assertions can verify that the pipeline rethrows the provider's cancellation.
    private class ProviderCancellation(stage: Stage) : CancellationException("Provider cancelled at $stage")

    private class TestCommand(
        val name: String,
        private val calls: MutableList<String>,
        private val stage: Stage?,
        private val failure: Exception
    ) : CommandEventStreamIdProvider, CommandEventSubjectProvider {
        fun visit(current: Stage) {
            calls.add("$name:$current")
            if (stage == current) throw failure
        }
        override fun eventStreamId(): String { visit(Stage.STREAM); return "stream" }
        override fun eventSubject(): String { visit(Stage.SUBJECT); return "subject" }
    }

    private class Fixture {
        val calls = mutableListOf<String>()
        val completions = mutableListOf<CommandResult<*>>()
        var operation: suspend (CommandContext) -> Any? = { "response" }
        val pipeline: DefaultCommandPipeline

        init {
            val registry = ConcurrentCommandHandlerRegistry()
            registry.register(object : CommandHandler {
                override val commandType: Class<*> = TestCommand::class.java
                override val metadata = CommandDescriptor("TestCommand", commandType.name)
                override fun resolveCommandKey(command: Any): Any {
                    (command as TestCommand).visit(Stage.KEY)
                    return "key"
                }
                override suspend fun prepare(context: CommandContext): CommandPreparation {
                    record(context, "prepare")
                    return CommandPreparation.empty(context.correlationId)
                }
                override suspend fun invoke(context: CommandContext): Any? {
                    record(context, "invoke")
                    return operation(context)
                }
            })
            pipeline = DefaultCommandPipeline(
                registry,
                commandFilters = listOf(CommandFilter { context ->
                    record(context, "filter")
                    CommandResult.success(context.correlationId)
                }),
                executionScopes = listOf(object : CommandExecutionScope {
                    override fun begin(context: CommandContext) { record(context, "begin") }
                    override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                        record(context, "complete")
                        completions.add(result)
                        return null
                    }
                }),
                contextValuesProviders = listOf(
                    CommandContextValuesProvider {
                        (it as TestCommand).visit(Stage.VALUES_FIRST)
                        mapOf("shared" to "first", "first" to 1)
                    },
                    CommandContextValuesProvider {
                        (it as TestCommand).visit(Stage.VALUES_SECOND)
                        mapOf("shared" to "second")
                    }
                )
            )
        }

        fun command(name: String, stage: Stage? = null, failure: Exception = IllegalStateException("provider failed")) =
            TestCommand(name, calls, stage, failure)

        private fun record(context: CommandContext, step: String) {
            calls.add("${(context.command as TestCommand).name}:$step")
        }
    }
}
