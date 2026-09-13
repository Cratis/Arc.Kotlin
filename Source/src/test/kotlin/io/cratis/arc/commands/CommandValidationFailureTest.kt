// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.artifacts.CommandEventStreamIdProvider
import io.cratis.arc.artifacts.CommandEventSubjectProvider
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.CommandResponseValueDescriptor
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.validation.ValidationFailure
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal class CommandValidationFailureTest {
    private val id = UUID.randomUUID()
    private fun options(allowed: ValidationResultSeverity? = null) = CommandExecutionOptions(
        id, ArcPrincipal.anonymous(), object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = null
        }, allowedValidationSeverity = allowed
    )

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `each existing ordinary boundary converts only the validation payload`(stage: Stage): Unit = runBlocking {
        val fixture = Fixture()
        val feedback = ValidationResult.error("safe", listOf("value"), mapOf("visible" to 42), "application", "detail")
        val result = fixture.pipeline.execute(TestCommand(stage, Failure(listOf(feedback))), options())
        assertEquals(id, result.correlationId)
        assertEquals(listOf(feedback), result.validationResults)
        assertFalse(result.isSuccess)
        assertTrue(result.isAuthorized)
        assertFalse(result.hasExceptions)
        assertEquals("", result.exceptionStackTrace)
        assertNull(result.response)
        assertEquals(expectedCleanup(stage), fixture.completed)
        assertEquals(1, fixture.visits.count { it == stage })
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `ordinary errors still retain original details at every boundary`(stage: Stage): Unit = runBlocking {
        val fixture = Fixture()
        val failure = IllegalStateException("ordinary secret")
        val result = fixture.pipeline.execute(TestCommand(stage, failure), options())
        assertFalse(result.isSuccess)
        assertEquals(listOf("ordinary secret"), result.exceptionMessages)
        assertTrue(result.exceptionStackTrace.contains("IllegalStateException: ordinary secret"))
        assertTrue(result.validationResults.isEmpty())
        assertNull(result.response)
        assertEquals(expectedCleanup(stage), fixture.completed)
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `severity matrix retains the original stage specific filtering locations`(stage: Stage): Unit = runBlocking {
        val payload = ValidationResultSeverity.entries.map { ValidationResult(it, it.name) }
        for (threshold in listOf(null) + ValidationResultSeverity.entries) {
            val fixture = Fixture()
            val result = fixture.pipeline.execute(TestCommand(stage, Failure(payload)), options(threshold))
            val expected = if (stage.filtered) payload.filter {
                if (threshold == null) it.severity == ValidationResultSeverity.Error
                else it.severity.value() > threshold.value()
            } else payload
            assertEquals(expected, result.validationResults, "$stage / $threshold")
            assertEquals(expected.isEmpty(), result.isSuccess, "$stage / $threshold")
            assertFalse(result.hasExceptions)
        }
        val fixture = Fixture()
        val warning = ValidationResult.warning("warning")
        val warningResult = fixture.pipeline.execute(TestCommand(stage, Failure(listOf(warning))), options())
        assertEquals(stage.filtered, warningResult.isSuccess)
        assertEquals(if (stage.filtered) emptyList() else listOf(warning), warningResult.validationResults)
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `direct and extraction cancellation finish only begun scopes once before rethrow`(stage: Stage) {
        for (extract in listOf(false, true)) {
            val fixture = Fixture()
            val cancellation = TestCancellation(stage)
            val failure = if (extract) GetterFailure(cancellation) else cancellation
            val thrown = assertThrows(CancellationException::class.java) {
                runBlocking { fixture.pipeline.execute(TestCommand(stage, failure), options()) }
            }
            assertSame(cancellation, thrown)
            assertEquals(expectedCleanup(stage), fixture.completed)
            // A completion that threw had already observed the prior result; every remaining completion sees failure.
            val remaining = if (stage == Stage.COMPLETE_SECOND) fixture.completionResults.drop(1)
                else if (stage == Stage.COMPLETE_FIRST) emptyList() else fixture.completionResults
            assertTrue(remaining.all { !it.isSuccess })
        }
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `ignored nested validation or extraction cancellation poisons root with reverse cleanup`(stage: Stage): Unit = runBlocking {
        for (extract in listOf(false, true)) {
            for (explicit in listOf(false, true)) {
                val fixture = Fixture()
                val cancellation = TestCancellation(stage)
                fixture.operation = { context ->
                    try {
                        val child = fixture.pipeline.execute(
                            TestCommand(stage, if (extract) GetterFailure(cancellation) else Failure(listOf(ValidationResult.error("safe")))),
                            if (explicit) CommandExecutionOptions.nested(context) else options()
                        )
                        assertFalse(extract)
                        assertEquals("safe", child.validationResults.single().message)
                    } catch (caught: CancellationException) {
                        assertTrue(extract)
                        assertSame(cancellation, caught)
                    }
                    "rejected root response"
                }
                val root = fixture.pipeline.execute(TestCommand(), options())
                assertFalse(root.isSuccess)
                assertEquals(listOf("A nested command execution failed; the root execution is rollback-only."), root.exceptionMessages)
                assertNull(root.response)
                assertEquals(expectedCleanup(stage) + listOf("second", "first"), fixture.completed)
                assertTrue(fixture.completionResults.takeLast(2).all { !it.isSuccess })
            }
        }
    }

    @Test
    fun `validate converts only context and filters without enlisting or poisoning an active root`(): Unit = runBlocking {
        for (stage in Stage.entries.filter { it.preScope || it == Stage.FILTER }) {
            for (extract in listOf(false, true)) {
                val fixture = Fixture()
                val cancellation = TestCancellation(stage)
                fixture.operation = { context ->
                    try {
                        val validation = fixture.pipeline.validate(
                            TestCommand(stage, if (extract) GetterFailure(cancellation) else Failure(listOf(ValidationResult.warning("warning")))),
                            CommandExecutionOptions.nested(context)
                        )
                        assertFalse(extract)
                        assertEquals(stage == Stage.FILTER, validation.isSuccess)
                        assertNull(validation.response)
                        assertFalse(validation.hasExceptions)
                    } catch (caught: CancellationException) {
                        assertTrue(extract)
                        assertSame(cancellation, caught)
                    }
                    "root response"
                }
                val root = fixture.pipeline.execute(TestCommand(), options())
                assertTrue(root.isSuccess)
                assertEquals("root response", root.response)
                assertEquals(listOf("second", "first"), fixture.completed)
                assertTrue(fixture.completionResults.all { it.isSuccess })
            }
        }
    }

    @Test
    fun `factory retains every severity and snapshots the list but does not clone application state`() {
        val state = mutableMapOf("client-visible" to "value")
        val payload = ValidationResultSeverity.entries.map { ValidationResult(it, "safe", listOf("value"), state, "reason", "detail") }.toMutableList()
        val result = CommandResult.fromException(id, Failure(payload))
        val expected = payload.toList()
        payload.clear()
        assertEquals(expected, result.validationResults)
        assertSame(state, result.validationResults.first().state)
        assertEquals("", result.exceptionStackTrace)
        assertTrue(result.exceptionMessages.isEmpty())
    }

    @Test
    fun `legacy and metadata aggregate conversion preserves fragments and deduplicates predicate payloads`(): Unit = runBlocking {
        for (metadata in listOf(false, true)) {
            var predicateCalls = 0
            var payloadReads = 0
            val predicateFeedback = ValidationResult.error("predicate")
            val priorFeedback = ValidationResult.error("prior")
            val handlerFeedback = ValidationResult.error("handler")
            val predicateFailure = object : RuntimeException("predicate secret"), ValidationFailure {
                override val validationResults: List<ValidationResult> get() {
                    payloadReads++
                    return listOf(predicateFeedback)
                }
            }
            val registry = ConcurrentCommandHandlerRegistry()
            registry.register(object : CommandHandler {
                override val commandType: Class<*> = TestCommand::class.java
                override val metadata = CommandDescriptor("TestCommand", commandType.name, responseValues = if (metadata) listOf(
                    CommandResponseValueDescriptor(Response::class.java.name, false, CommandResponseValueDisposition.HANDLED),
                    CommandResponseValueDescriptor(String::class.java.name, false, CommandResponseValueDisposition.CLIENT)
                ) else emptyList())
                override suspend fun invoke(context: CommandContext): Any = Triple(
                    CommandResult<Void>(id, isAuthorized = false, validationResults = listOf(priorFeedback),
                        exceptionMessages = listOf("prior error"), exceptionStackTrace = "prior stack",
                        authorizationFailureReason = "prior denial"), Response(), "rejected response"
                )
            })
            val pipeline = DefaultCommandPipeline(registry, responseValueHandlers = listOf(
                object : CommandResponseValueHandler {
                    override fun canHandle(context: CommandContext, value: Any): Boolean {
                        if (value !is Response) return false
                        predicateCalls++
                        throw predicateFailure
                    }
                    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> = error("must not run")
                },
                object : CommandResponseValueHandler {
                    override fun canHandle(context: CommandContext, value: Any): Boolean = value is Response
                    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> = throw Failure(listOf(handlerFeedback))
                }
            ))
            val result = pipeline.execute(TestCommand(), options())
            assertEquals(2, predicateCalls)
            assertEquals(1, payloadReads)
            assertEquals(listOf(predicateFeedback, priorFeedback, handlerFeedback), result.validationResults)
            assertEquals(listOf("prior error"), result.exceptionMessages)
            assertEquals("prior stack", result.exceptionStackTrace)
            assertFalse(result.isAuthorized)
            assertEquals("prior denial", result.authorizationFailureReason)
            assertNull(result.response)
        }
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `checked getter failure retains original error and completes remaining scopes`(stage: Stage): Unit = runBlocking {
        assertCheckedBoundary(stage, Extraction.GETTER)
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `checked iterator failure discards partial feedback and completes remaining scopes`(stage: Stage): Unit = runBlocking {
        assertCheckedBoundary(stage, Extraction.ITERATOR)
    }

    private suspend fun assertCheckedBoundary(stage: Stage, extraction: Extraction) {
        val fixture = Fixture()
        val failure = CheckedPayloadFailure(extraction)
        val result = fixture.pipeline.execute(TestCommand(stage, failure), options())
        assertOriginalFailure(failure, result)
        assertEquals(expectedCleanup(stage), fixture.completed)
        assertEquals(1, fixture.visits.count { it == stage })
        val remaining = when (stage) {
            Stage.COMPLETE_SECOND -> fixture.completionResults.drop(1)
            Stage.COMPLETE_FIRST -> emptyList()
            else -> fixture.completionResults
        }
        assertTrue(remaining.all { !it.isSuccess })
        remaining.forEach { assertOriginalFailure(failure, it) }
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `ignored nested checked getter failure cannot bypass root rollback`(stage: Stage): Unit = runBlocking {
        assertCheckedNested(stage, Extraction.GETTER)
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `ignored nested checked iterator failure cannot bypass root rollback`(stage: Stage): Unit = runBlocking {
        assertCheckedNested(stage, Extraction.ITERATOR)
    }

    private suspend fun assertCheckedNested(stage: Stage, extraction: Extraction) {
        for (explicit in listOf(false, true)) {
            val fixture = Fixture()
            val failure = CheckedPayloadFailure(extraction)
            var child: CommandResult<*>? = null
            var escaped: Exception? = null
            fixture.operation = { context ->
                try {
                    child = fixture.pipeline.execute(TestCommand(stage, failure),
                        if (explicit) CommandExecutionOptions.nested(context) else options())
                } catch (caught: Exception) {
                    // A consumer may catch and ignore a child exception; the root must still fail closed.
                    escaped = caught
                }
                "rejected root response"
            }
            val root = fixture.pipeline.execute(TestCommand(), options())
            assertFalse(root.isSuccess)
            assertEquals(listOf("A nested command execution failed; the root execution is rollback-only."), root.exceptionMessages)
            assertNull(root.response)
            assertEquals(expectedCleanup(stage) + listOf("second", "first"), fixture.completed)
            assertTrue(fixture.completionResults.takeLast(2).all { !it.isSuccess })
            assertNull(escaped)
            assertOriginalFailure(failure, requireNotNull(child))
        }
    }

    @ParameterizedTest
    @EnumSource(Extraction::class)
    fun `checked extraction during validate remains unenlisted and does not poison root`(extraction: Extraction): Unit = runBlocking {
        for (stage in Stage.entries.filter { it.preScope || it == Stage.FILTER }) {
            val fixture = Fixture()
            val failure = CheckedPayloadFailure(extraction)
            var validation: CommandResult<*>? = null
            fixture.operation = { context ->
                validation = fixture.pipeline.validate(TestCommand(stage, failure), CommandExecutionOptions.nested(context))
                "root response"
            }
            val root = fixture.pipeline.execute(TestCommand(), options())
            assertTrue(root.isSuccess)
            assertEquals("root response", root.response)
            assertOriginalFailure(failure, requireNotNull(validation))
            assertEquals(listOf("second", "first"), fixture.completed)
            assertTrue(fixture.completionResults.all { it.isSuccess })
        }
    }

    @ParameterizedTest
    @EnumSource(Extraction::class)
    fun `factory checked extraction fallback reads original exception identity and details`(extraction: Extraction) {
        val failure = CheckedPayloadFailure(extraction)
        assertOriginalFailure(failure, CommandResult.fromException(id, failure))
        assertSame(failure, failure.messageOwner)
        assertSame(failure, failure.stackOwner)
    }

    @Test
    fun `fatal extraction errors propagate by identity rather than becoming malformed feedback`() {
        for (extraction in Extraction.entries) {
            val fatal = AssertionError("fatal payload")
            assertSame(fatal, assertThrows(AssertionError::class.java) {
                CommandResult.fromException(id, CheckedPayloadFailure(extraction, fatal))
            })
        }
    }

    @ParameterizedTest
    @EnumSource(Extraction::class)
    fun `checked aggregate failures preserve prior fragments and deduplicate extraction`(extraction: Extraction): Unit = runBlocking {
        for (metadata in listOf(false, true)) {
            val predicateFailure = CheckedPayloadFailure(extraction)
            val handlerFailure = CheckedPayloadFailure(extraction)
            val completionFailure = CheckedPayloadFailure(extraction)
            val priorFeedback = ValidationResult.error("prior")
            val registry = ConcurrentCommandHandlerRegistry()
            registry.register(object : CommandHandler {
                override val commandType: Class<*> = TestCommand::class.java
                override val metadata = CommandDescriptor("TestCommand", commandType.name, responseValues = if (metadata) listOf(
                    CommandResponseValueDescriptor(Response::class.java.name, false, CommandResponseValueDisposition.HANDLED),
                    CommandResponseValueDescriptor(String::class.java.name, false, CommandResponseValueDisposition.CLIENT)
                ) else emptyList())
                override suspend fun invoke(context: CommandContext): Any = Triple(
                    CommandResult<Void>(id, isAuthorized = false, validationResults = listOf(priorFeedback),
                        exceptionMessages = listOf("prior error"), exceptionStackTrace = "prior stack",
                        authorizationFailureReason = "prior denial"), Response(), "rejected response"
                )
            })
            val beginnings = mutableListOf<String>()
            val completions = mutableListOf<String>()
            var finalCompletion: CommandResult<*>? = null
            val pipeline = DefaultCommandPipeline(registry, executionScopes = listOf("first", "second").map { name ->
                object : CommandExecutionScope {
                    override fun begin(context: CommandContext) { beginnings.add(name) }
                    override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                        completions.add(name)
                        if (name == "second") throw completionFailure
                        finalCompletion = result
                        return null
                    }
                }
            }, responseValueHandlers = listOf(
                object : CommandResponseValueHandler {
                    override fun canHandle(context: CommandContext, value: Any): Boolean {
                        if (value !is Response) return false
                        throw predicateFailure
                    }
                    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> = error("must not run")
                },
                object : CommandResponseValueHandler {
                    override fun canHandle(context: CommandContext, value: Any): Boolean = value is Response
                    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> = throw handlerFailure
                }
            ))
            val result = pipeline.execute(TestCommand(), options())
            assertEquals(listOf("first", "second"), beginnings)
            assertEquals(listOf("second", "first"), completions)
            for (outcome in listOf(result, requireNotNull(finalCompletion))) {
                assertEquals(listOf(priorFeedback), outcome.validationResults)
                assertEquals(listOf("original checked payload", "prior error", "original checked payload", "original checked payload"), outcome.exceptionMessages)
                assertEquals(listOf(predicateFailure.stackTraceToString(), "prior stack", handlerFailure.stackTraceToString(),
                    completionFailure.stackTraceToString()).joinToString("\n"), outcome.exceptionStackTrace)
                assertFalse(outcome.isAuthorized)
                assertEquals("prior denial", outcome.authorizationFailureReason)
                assertNull(outcome.response)
            }
            assertEquals(listOf(1, 1, 1), listOf(predicateFailure, handlerFailure, completionFailure).map { it.reads })
        }
    }

    private fun assertOriginalFailure(failure: CheckedPayloadFailure, result: CommandResult<*>) {
        assertEquals(id, result.correlationId)
        assertFalse(result.isSuccess)
        assertTrue(result.isAuthorized)
        assertTrue(result.hasExceptions)
        assertTrue(result.validationResults.isEmpty())
        assertEquals(listOf("original checked payload"), result.exceptionMessages)
        assertEquals(failure.stackTraceToString(), result.exceptionStackTrace)
        assertNull(result.response)
    }

    enum class Extraction { GETTER, ITERATOR }

    private class CheckedPayloadFailure(
        private val extraction: Extraction,
        private val extractionFailure: Throwable = if (extraction == Extraction.GETTER) IOException("getter extraction")
            else Exception("iterator extraction")
    ) : RuntimeException(), ValidationFailure {
        var reads = 0
        var messageOwner: Throwable? = null
        var stackOwner: Throwable? = null
        override val message: String get() { messageOwner = this; return "original checked payload" }
        override fun printStackTrace(writer: java.io.PrintWriter) {
            stackOwner = this
            super.printStackTrace(writer)
        }
        override val validationResults: List<ValidationResult> get() {
            reads++
            if (extraction == Extraction.GETTER) throw extractionFailure
            return object : AbstractList<ValidationResult>() {
                override val size: Int = 2
                override fun get(index: Int): ValidationResult = error("iterator must be used")
                override fun iterator(): Iterator<ValidationResult> = object : Iterator<ValidationResult> {
                    private var first = true
                    override fun hasNext(): Boolean = true
                    override fun next(): ValidationResult {
                        if (!first) throw extractionFailure
                        first = false
                        return ValidationResult.error("partial payload must be discarded")
                    }
                }
            }
        }
    }

    private fun expectedCleanup(stage: Stage): List<String> = when {
        stage.preScope || stage == Stage.BEGIN_FIRST -> emptyList()
        stage == Stage.BEGIN_SECOND -> listOf("first")
        else -> listOf("second", "first")
    }

    enum class Stage(val preScope: Boolean = false, val filtered: Boolean = false) {
        VALUES_FIRST(true), VALUES_SECOND(true), KEY(true), STREAM(true), SUBJECT(true),
        BEGIN_FIRST, BEGIN_SECOND, FILTER(filtered = true), PREPARE, INVOKE,
        MATCH(filtered = true), HANDLE(filtered = true), COMPLETE_SECOND, COMPLETE_FIRST
    }

    private class Failure(override val validationResults: List<ValidationResult>) : RuntimeException("exception secret"), ValidationFailure
    private class GetterFailure(private val cancellation: CancellationException) : RuntimeException("getter secret"), ValidationFailure {
        override val validationResults: List<ValidationResult> get() = throw cancellation
    }
    private class TestCancellation(stage: Stage) : CancellationException("cancel $stage")
    private class Response
    private class TestCommand(val stage: Stage? = null, val failure: RuntimeException? = null) :
        CommandEventStreamIdProvider, CommandEventSubjectProvider {
        var visit: (Stage) -> Unit = {}
        override fun eventStreamId(): String { visit(Stage.STREAM); return "stream" }
        override fun eventSubject(): String { visit(Stage.SUBJECT); return "subject" }
    }

    private class Fixture {
        val completed = mutableListOf<String>()
        val completionResults = mutableListOf<CommandResult<*>>()
        val visits = mutableListOf<Stage>()
        var operation: suspend (CommandContext) -> Any? = { Response() }
        val pipeline: DefaultCommandPipeline

        init {
            fun visit(command: TestCommand, stage: Stage) {
                visits.add(stage)
                if (command.stage == stage) throw requireNotNull(command.failure)
            }
            fun visit(context: CommandContext, stage: Stage) = visit(context.command as TestCommand, stage)
            val registry = ConcurrentCommandHandlerRegistry()
            registry.register(object : CommandHandler {
                override val commandType: Class<*> = TestCommand::class.java
                override val metadata = CommandDescriptor("TestCommand", commandType.name)
                override fun resolveCommandKey(command: Any): Any { visit(command as TestCommand, Stage.KEY); return "key" }
                override suspend fun prepare(context: CommandContext): CommandPreparation {
                    visit(context, Stage.PREPARE)
                    return CommandPreparation.empty(context.correlationId)
                }
                override suspend fun invoke(context: CommandContext): Any? {
                    visit(context, Stage.INVOKE)
                    return if ((context.command as TestCommand).stage == null) operation(context) else Response()
                }
            })
            pipeline = DefaultCommandPipeline(
                registry,
                commandFilters = listOf(CommandFilter { visit(it, Stage.FILTER); CommandResult.success(it.correlationId) }),
                executionScopes = listOf("first", "second").map { name -> object : CommandExecutionScope {
                    override fun begin(context: CommandContext) {
                        visit(context, if (name == "first") Stage.BEGIN_FIRST else Stage.BEGIN_SECOND)
                    }
                    override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                        completed.add(name)
                        completionResults.add(result)
                        visit(context, if (name == "first") Stage.COMPLETE_FIRST else Stage.COMPLETE_SECOND)
                        return null
                    }
                } },
                responseValueHandlers = listOf(object : CommandResponseValueHandler {
                    override fun canHandle(context: CommandContext, value: Any): Boolean {
                        if (value !is Response) return false
                        visit(context, Stage.MATCH)
                        return true
                    }
                    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                        visit(context, Stage.HANDLE)
                        return CommandResult.success(context.correlationId)
                    }
                }),
                contextValuesProviders = listOf(
                    CommandContextValuesProvider {
                        val command = it as TestCommand
                        command.visit = { stage -> visit(command, stage) }
                        visit(command, Stage.VALUES_FIRST)
                        emptyMap()
                    },
                    CommandContextValuesProvider { visit(it as TestCommand, Stage.VALUES_SECOND); emptyMap() }
                )
            )
        }
    }
}
