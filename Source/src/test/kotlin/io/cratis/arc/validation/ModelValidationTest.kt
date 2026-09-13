// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelValidationTest {
    @Test
    fun `one reusable nested rule validates two commands and supplied query arguments`() : Unit = runBlocking {
        val calls = mutableListOf<String>()
        val rule = rule(Input::class.java) { model, context ->
            calls.add(context.memberPath)
            if (model.name.isEmpty()) listOf(ValidationResult.error("required", listOf("name"))) else emptyList()
        }
        val commandFilter = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rule))
        val queryFilter = DefaultQueryValidationFilter(emptyList(), emptyList(), listOf(rule))
        assertEquals(listOf("input.name"), commandFilter.execute(command(First(Input("")))).validationResults.single().members)
        assertEquals(listOf("nested.name"), commandFilter.execute(command(Second(Input("")))).validationResults.single().members)
        assertEquals(listOf("arg.input.name"), queryFilter.execute(query(mapOf("arg" to First(Input(""))))).validationResults.single().members)
        assertTrue(commandFilter.execute(command(First(Input("valid")))).isSuccess)
        assertEquals(listOf("input", "nested", "arg.input", "input"), calls)
    }

    @Test
    fun `root typed validators remain distinct and run once before exact node rules`() : Unit = runBlocking {
        val calls = mutableListOf<String>()
        val typed = object : CommandValidator<First> {
            override val commandType = First::class.java
            override suspend fun validate(command: First, context: CommandContext): List<ValidationResult> {
                calls.add("typed")
                return emptyList()
            }
        }
        val root = rule(First::class.java) { _, _ -> calls.add("root"); emptyList() }
        val base = rule(Base::class.java) { _, _ -> calls.add("base"); emptyList() }
        val exact = rule(Input::class.java) { _, _ -> calls.add("child"); emptyList() }
        DefaultCommandValidationFilter(listOf(typed), emptyList(), listOf(root, base, exact))
            .execute(command(First(Input("value"))))
        assertEquals(listOf("typed", "root", "child"), calls)
        calls.clear()
        val typedQuery = object : QueryValidator {
            override val queryName: FullyQualifiedQueryName? = null
            override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> {
                calls.add("query")
                return emptyList()
            }
        }
        DefaultQueryValidationFilter(listOf(typedQuery), emptyList(), listOf(root, base, exact))
            .execute(query(mapOf("arg" to First(Input("value")))))
        assertEquals(listOf("query", "root", "child"), calls)
    }

    @Test
    fun `depth first graph handles arrays maps cycles shared identities and independent query roots`() : Unit = runBlocking {
        val paths = mutableListOf<String>()
        val rule = rule(Graph::class.java) { _, context -> paths.add(context.memberPath); emptyList() }
        val shared = Graph()
        val root = Graph().apply {
            array = arrayOf(shared)
            children = listOf(shared, Graph())
            named = linkedMapOf("first" to Graph())
            next = this
        }
        DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rule)).execute(command(root))
        assertEquals(listOf("", "array[0]", "children[1]", "named.first"), paths)
        paths.clear()
        DefaultQueryValidationFilter(emptyList(), emptyList(), listOf(rule)).execute(query(linkedMapOf("a" to shared, "b" to shared)))
        assertEquals(listOf("a", "b"), paths)
    }

    @Test
    fun `explicit exact scalar enum and container rules run before terminal boundary`() : Unit = runBlocking {
        val calls = mutableListOf<String>()
        val text = rule(String::class.java) { _, context -> calls.add("text:${context.memberPath}"); emptyList() }
        val enum = rule(Choice::class.java) { _, context -> calls.add("enum:${context.memberPath}"); emptyList() }
        val array = rule(Array<String>::class.java) { _, context -> calls.add("array:${context.memberPath}"); emptyList() }
        val rules = listOf(text, enum, array)
        val filter = DefaultQueryValidationFilter(emptyList(), emptyList(), rules)
        filter.execute(query(linkedMapOf("string" to "root", "enum" to Choice.ONE, "array" to arrayOf("element"))))
        assertEquals(listOf("text:string", "enum:enum", "array:array", "text:array[0]"), calls)
        calls.clear()
        DefaultCommandValidationFilter(emptyList(), emptyList(), rules).execute(command("root"))
        assertEquals(listOf("text:"), calls)
    }

    @Test
    fun `model relative paths preserve value members while concept rules collapse them`() : Unit = runBlocking {
        val state = Any()
        val model = rule(Code::class.java) { _, _ -> listOf(
            ValidationResult.warning("model", listOf("", "value", "rawValue", "[0]", "child"), state, "custom", "detail")) }
        val baseConcept = object : ConceptValidator<Code> {
            override val conceptType = Code::class.java
            override fun validate(concept: Code): List<ValidationResult> = listOf(ValidationResult.error("concept", listOf("value", "rawValue")))
        }
        val derivedModel = rule(DerivedCode::class.java) { _, _ -> listOf(ValidationResult.error("derived")) }
        val filter = DefaultQueryValidationFilter(emptyList(), listOf(baseConcept), listOf(model, derivedModel))
        val feedback = filter.execute(query(linkedMapOf("code" to Code("x"), "derived" to DerivedCode("x")))).validationResults
        assertEquals(listOf("model", "concept", "derived", "concept"), feedback.map { it.message })
        assertEquals(listOf("code", "code.value", "code.rawValue", "code[0]", "code.child"), feedback[0].members)
        assertEquals(listOf("code", "code"), feedback[1].members)
        assertEquals(listOf("derived"), feedback[2].members)
        assertEquals(listOf("derived", "derived"), feedback[3].members)
        assertSame(state, feedback[0].state)
        assertEquals("custom", feedback[0].reason)
        assertEquals("detail", feedback[0].reasonDetail)
    }

    @Test
    fun `context delegates operation identity tenant and services without traversing infrastructure`() : Unit = runBlocking {
        val model = Input("supplied")
        val cmd = command(First(model))
        val qry = query(mapOf("input" to model))
        var seen = 0
        val rule = rule(Input::class.java) { value, context ->
            assertSame(model, value)
            assertSame(Services, context.serviceResolver)
            assertEquals("tenant", context.tenantId)
            assertEquals("namespace", context.tenantNamespace)
            assertSame(secret, context.serviceResolver.resolve(Input::class.java))
            if (context.commandContext != null) {
                assertSame(cmd, context.commandContext)
                assertNull(context.queryContext)
                assertEquals(cmd.correlationId, context.correlationId)
                assertSame(cmd.principal, context.principal)
            } else {
                assertSame(qry, context.queryContext)
                assertEquals(qry.correlationId, context.correlationId)
                assertSame(qry.principal, context.principal)
            }
            seen++
            emptyList()
        }
        DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rule)).execute(cmd)
        val filter = DefaultQueryValidationFilter(emptyList(), emptyList(), listOf(rule))
        filter.execute(qry)
        filter.execute(query(emptyMap())) // No default or service values manufactured for missing arguments.
        assertEquals(2, seen)
    }

    @Test
    fun `mutable feedback is snapshotted before later rules run`() : Unit = runBlocking {
        val mutable = mutableListOf(ValidationResult.error("first", listOf("name")))
        val first = rule(Input::class.java) { _, _ -> mutable }
        val second = rule(Input::class.java) { _, _ -> mutable.clear(); emptyList() }
        val feedback = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(first, second))
            .execute(command(First(Input("")))).validationResults
        assertEquals(listOf("first"), feedback.map { it.message })
        assertEquals(listOf("input.name"), feedback.single().members)
    }

    @Test
    fun `ordinary checked exceptions fail safely while cancellation and fatal errors escape`() : Unit = runBlocking {
        val failure = rule(Input::class.java) { _, _ -> throw Exception("secret") }
        val feedback = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(failure))
            .execute(command(First(Input("")))).validationResults.single()
        assertEquals(ValidationResultReasons.VALIDATOR_FAILED, feedback.reason)
        assertEquals("The value could not be validated.", feedback.message)
        assertEquals(listOf("input"), feedback.members)
        assertNull(feedback.state)
        assertNull(feedback.reasonDetail)
        val marker = object : RuntimeException("marker secret"), ValidationFailure {
            override val validationResults = listOf(ValidationResult.error("marker must not escape"))
        }
        val markedRule = rule(Input::class.java) { _, _ -> throw marker }
        val commandMarker = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(markedRule))
            .execute(command(First(Input("")))).validationResults.single()
        val queryMarker = DefaultQueryValidationFilter(emptyList(), emptyList(), listOf(markedRule))
            .execute(query(mapOf("input" to Input("")))).validationResults.single()
        listOf(commandMarker, queryMarker).forEach {
            assertEquals(ValidationResultReasons.VALIDATOR_FAILED, it.reason)
            assertEquals("The value could not be validated.", it.message)
        }
        val cancelled = CancellationException("cancelled")
        val fatal = AssertionError("fatal")
        for (thrown in listOf(cancelled, InvocationTargetException(cancelled), fatal, InvocationTargetException(fatal))) {
            val rule = rule(Input::class.java) { _, _ -> throw thrown }
            val filter = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rule))
            val expected = if (thrown is InvocationTargetException) thrown.targetException else thrown
            assertSame(expected, assertThrows(expected.javaClass) { runBlocking { filter.execute(command(First(Input("")))) } })
        }
        val rule = rule(Input::class.java) { _, _ -> emptyList() }
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            runBlocking { DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rule)).execute(command(FatalGetter(fatal))) }
        })
    }

    open class Base
    class Input(val name: String) : Base()
    class First(val input: Input)
    class Second(val nested: Input)
    class Graph {
        var array: Array<Graph> = emptyArray()
        var children: List<Graph> = emptyList()
        var named: Map<String, Graph> = emptyMap()
        var next: Graph? = null
    }
    enum class Choice { ONE }
    open class Code(private val raw: String) : ConceptAs<String> { override fun value(): String = raw }
    class DerivedCode(raw: String) : Code(raw)
    class FatalGetter(private val failure: Error) { val input: Input get() = throw failure }

    private fun <T : Any> rule(type: Class<T>, validate: suspend (T, ModelValidationContext) -> List<ValidationResult>): ModelValidator<T> =
        object : ModelValidator<T> {
            override val modelType = type
            override suspend fun validate(model: T, context: ModelValidationContext): List<ValidationResult> = validate(model, context)
        }
    private fun command(value: Any) = CommandContext(UUID.randomUUID(), value, value.javaClass, ArcPrincipal.anonymous(),
        "tenant", "namespace", serviceResolver = Services, values = mapOf("secret" to secret), providedValues = listOf(secret))
    private fun query(arguments: Map<String, Any?>): QueryContext {
        val request = QueryRequest(FullyQualifiedQueryName("model.query"), arguments)
        return QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), "tenant", "namespace", Services, null, false)
    }
    private companion object {
        val secret = Input("service")
    }
    private object Services : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = type.cast(secret) }
}
