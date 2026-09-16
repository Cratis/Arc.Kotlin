// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultObservableQueryPipeline
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.flowOf
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

class ConceptValidationCancellationTest {
    enum class Boundary { EXECUTE, VALIDATE, QUERY, OBSERVABLE }

    @ParameterizedTest
    @EnumSource(Boundary::class)
    fun `leaf validator cancellation escapes real pipelines before invocation`(boundary: Boundary) {
        val cancellation = CancellationException("concept cancelled")
        val fixture = Fixture(rule { throw cancellation })
        assertCancellation(cancellation, assertThrows(CancellationException::class.java) {
            runBlocking { fixture.run(boundary, Input(Code("value"))) }
        })
        fixture.assertNotInvoked(boundary)
    }

    @ParameterizedTest
    @EnumSource(Boundary::class)
    fun `Kotlin getter cancellation escapes reflection and real pipelines`(boundary: Boundary) {
        val cancellation = CancellationException("getter cancelled")
        val fixture = Fixture(rule { emptyList() })
        assertCancellation(cancellation, assertThrows(CancellationException::class.java) {
            runBlocking { fixture.run(boundary, ThrowingGetter(cancellation)) }
        })
        fixture.assertNotInvoked(boundary)
    }

    @ParameterizedTest
    @EnumSource(Boundary::class)
    fun `ordinary leaf validator failure retains safe member feedback`(boundary: Boundary): Unit = runBlocking {
        val fixture = Fixture(rule { throw IllegalStateException("private detail") })
        val results = fixture.run(boundary, Input(Code("value")))
        val feedback = results.single()
        assertEquals(ValidationResultReasons.VALIDATOR_FAILED, feedback.reason)
        assertEquals("The value could not be validated.", feedback.message)
        assertEquals(listOf(if (boundary == Boundary.QUERY || boundary == Boundary.OBSERVABLE) "input.code" else "code"), feedback.members)
        assertNull(feedback.state)
        assertNull(feedback.reasonDetail)
        assertEquals(0, fixture.invocations)
    }

    @ParameterizedTest
    @EnumSource(Boundary::class)
    fun `ordinary Kotlin getter failure is still skipped`(boundary: Boundary): Unit = runBlocking {
        val fixture = Fixture(rule { emptyList() })
        assertTrue(fixture.run(boundary, ThrowingGetter(IllegalStateException("unreadable"))).isEmpty())
        assertEquals(if (boundary == Boundary.VALIDATE) 0 else 1, fixture.invocations)
    }

    @Test
    fun `base and derived concept validators retain assignable matching and order`() {
        val calls = mutableListOf<String>()
        val base = rule { calls.add("base"); listOf(ValidationResult.error("base", listOf("value"))) }
        val derived = object : ConceptValidator<DerivedCode> {
            override val conceptType = DerivedCode::class.java
            override fun validate(concept: DerivedCode): List<ValidationResult> {
                calls.add("derived")
                return listOf(ValidationResult.error("derived", listOf("detail")))
            }
        }
        val validation = ConceptValidation(listOf(base, derived))
        val results = validation.validate(listOf(Code("base"), DerivedCode("derived")), "codes")
        assertEquals(listOf("base", "base", "derived"), calls)
        assertEquals(listOf("codes[0]", "codes[1]", "codes[1].detail"), results.flatMap { it.members })
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `nested leaf cancellation completes failed scopes and poisons a caught child`(catchChild: Boolean): Unit = runBlocking {
        val cancellation = CancellationException("nested concept cancelled")
        val fixture = Fixture(rule { throw cancellation })
        fixture.rootOperation = {
            try {
                fixture.commands.execute(Input(Code("child")), fixture.commandOptions)
            } catch (exception: CancellationException) {
                assertCancellation(cancellation, exception)
                if (!catchChild) throw exception
            }
            "must not escape"
        }
        if (catchChild) {
            val result = fixture.commands.execute(Root, fixture.commandOptions)
            assertFalse(result.isSuccess)
            assertNull(result.response)
            assertEquals(listOf("A nested command execution failed; the root execution is rollback-only."), result.exceptionMessages)
        } else {
            assertCancellation(cancellation, assertThrows(CancellationException::class.java) {
                runBlocking { fixture.commands.execute(Root, fixture.commandOptions) }
            })
        }
        assertEquals(1, fixture.invocations) // Root only; the child handler never runs.
        assertEquals(listOf(false, true), fixture.completions.map { it.first })
        fixture.completions.forEach { (_, result) ->
            assertFalse(result.isSuccess)
            assertTrue(result.validationResults.isEmpty())
        }
    }

    @Test
    fun `traversal rethrows original cancellation without a reflection wrapper`() {
        val cancellation = CancellationException("original")
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            ConceptValidation(listOf(rule { throw cancellation })).validate(Input(Code("value")))
        })
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            ConceptValidation(listOf(rule { emptyList() })).validate(ThrowingGetter(cancellation))
        })
    }

    private fun assertCancellation(expected: CancellationException, actual: CancellationException) {
        assertEquals(expected.message, actual.message)
        // Coroutine stack-trace recovery can copy cancellation at withContext boundaries.
        // Its cancellation-only cause chain must still lead to the exact original exception.
        val original = generateSequence(actual) { it.cause as? CancellationException }.last()
        assertSame(expected, original)
    }

    private open class Code(private val raw: String) : ConceptAs<String> {
        override fun value(): String = raw
    }
    private class DerivedCode(raw: String) : Code(raw)
    private data class Input(val code: Code)
    private class ThrowingGetter(private val failure: RuntimeException) {
        val code: Code get() = throw failure
    }
    private data object Root

    private fun rule(validate: (Code) -> List<ValidationResult>): ConceptValidator<Code> = object : ConceptValidator<Code> {
        override val conceptType = Code::class.java
        override fun validate(concept: Code): List<ValidationResult> = validate(concept)
    }

    private class Fixture(validator: ConceptValidator<*>) {
        var invocations = 0
        var rootOperation: suspend () -> Any? = { "root" }
        val completions = mutableListOf<Pair<Boolean, CommandResult<*>>>()
        private val services = object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = null
        }
        val commandOptions = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services)
        private val queryOptions = QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services)
        private val handlers = ConcurrentCommandHandlerRegistry().apply {
            listOf(Input::class.java, ThrowingGetter::class.java, Root::class.java).forEach { type ->
                register(object : CommandHandler {
                    override val commandType = type
                    override val metadata = CommandDescriptor(type.simpleName, type.name)
                    override suspend fun invoke(context: CommandContext): Any? {
                        invocations++
                        return if (context.command === Root) rootOperation() else "response"
                    }
                })
            }
        }
        val commands = DefaultCommandPipeline(handlers,
            listOf(DefaultCommandValidationFilter(emptyList(), listOf(validator))),
            listOf(object : CommandExecutionScope {
                override fun begin(context: CommandContext) = Unit
                override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                    completions.add((context.command === Root) to result)
                    return null
                }
            }))
        private val performers = ConcurrentQueryPerformerRegistry().apply {
            QueryTransportType.entries.forEach { transport ->
                register(object : QueryPerformer {
                    override val fullyQualifiedName = FullyQualifiedQueryName("test.$transport")
                    override val descriptor = QueryDescriptor(transport.name, "test", "kotlin.String", transport = transport)
                    override suspend fun perform(context: QueryContext): Any {
                        invocations++
                        return if (transport == QueryTransportType.OBSERVABLE) flowOf("data") else "data"
                    }
                })
            }
        }
        private val filter = DefaultQueryValidationFilter(emptyList(), listOf(validator))
        suspend fun run(boundary: Boundary, input: Any): List<ValidationResult> = when (boundary) {
            Boundary.EXECUTE -> commands.execute(input, commandOptions).validationResults
            Boundary.VALIDATE -> commands.validate(input, commandOptions).validationResults
            Boundary.QUERY -> DefaultQueryPipeline(performers, listOf(filter)).perform(
                QueryRequest(FullyQualifiedQueryName("test.REQUEST_RESPONSE"), mapOf("input" to input)), queryOptions
            ).validationResults
            Boundary.OBSERVABLE -> when (val opened = DefaultObservableQueryPipeline(performers, listOf(filter)).open(
                QueryRequest(FullyQualifiedQueryName("test.OBSERVABLE"), mapOf("input" to input)), queryOptions
            )) {
                is io.cratis.arc.queries.ObservableQueryOpenResult.Failure -> opened.result.validationResults
                is io.cratis.arc.queries.ObservableQueryOpenResult.Stream -> emptyList()
            }
        }
        fun assertNotInvoked(boundary: Boundary) {
            assertEquals(0, invocations)
            assertEquals(if (boundary == Boundary.EXECUTE) 1 else 0, completions.size)
            completions.forEach { (_, result) ->
                assertFalse(result.isSuccess)
                assertTrue(result.hasExceptions)
                assertTrue(result.validationResults.isEmpty())
            }
        }
    }
}
