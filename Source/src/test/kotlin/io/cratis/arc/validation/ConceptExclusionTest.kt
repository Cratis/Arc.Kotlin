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
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import java.util.concurrent.CompletionException
import java.util.concurrent.CancellationException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConceptExclusionTest {
    private val concept = object : ConceptValidator<Code> {
        override val conceptType = Code::class.java
        override fun validate(concept: Code) = listOf(ValidationResult.error("concept", listOf("value")))
    }

    @Test
    fun `ignored first shared concept still validates at later required edge`() : Unit = runBlocking {
        val shared = Code("")
        val filter = commands(listOf(ConceptValidationExclusion(Owner::class.java, "aIgnored")))
        assertEquals(listOf("zRequired"), filter.execute(command(Owner(shared, shared))).validationResults.flatMap { it.members })
    }

    @Test
    fun `separate equal instances and reverse shared edge order validate only required edges`() : Unit = runBlocking {
        val shared = Code("")
        for (other in listOf(shared, Code(""))) {
            assertEquals(listOf("zRequired"), commands(listOf(ConceptValidationExclusion(Owner::class.java, "aIgnored")))
                .execute(command(Owner(shared, other))).validationResults.flatMap { it.members })
            assertEquals(listOf("aIgnored"), commands(listOf(ConceptValidationExclusion(Owner::class.java, "zRequired")))
                .execute(command(Owner(shared, other))).validationResults.flatMap { it.members })
        }
        assertEquals(listOf("aIgnored", "zRequired"), commands(emptyList()).execute(command(Owner(Code(""), Code(""))))
            .validationResults.flatMap { it.members })
        assertEquals(listOf("aIgnored"), commands(emptyList()).execute(command(Owner(shared, shared))).validationResults.flatMap { it.members })
    }

    @Test
    fun `ignored concept model rule runs once and suspension cannot lose edge exclusion`() : Unit = runBlocking {
        val shared = Code("")
        val modelPaths = mutableListOf<String>()
        val model = object : ModelValidator<Code> {
            override val modelType = Code::class.java
            override suspend fun validate(model: Code, context: ModelValidationContext): List<ValidationResult> {
                assertSame(shared, model)
                withContext(Dispatchers.Default) { assertEquals("aIgnored", context.memberPath) }
                modelPaths.add(context.memberPath)
                return listOf(ValidationResult.error("model", listOf("value")))
            }
        }
        val filter = DefaultCommandValidationFilter(emptyList(), listOf(concept), listOf(model), listOf(ConceptValidationExclusion(Owner::class.java, "aIgnored")))
        val result = filter.execute(command(Owner(shared, shared))).validationResults
        assertEquals(listOf("model", "concept"), result.map { it.message })
        assertEquals(listOf("aIgnored.value", "zRequired"), result.flatMap { it.members })
        assertEquals(listOf("aIgnored"), modelPaths)
    }

    @Test
    fun `exact owner registration supports inherited readable property but not derived owner matching`() : Unit = runBlocking {
        val exclusions = listOf(ConceptValidationExclusion(Base::class.java, "code"))
        assertTrue(commands(exclusions).execute(command(Base(Code("")))).isSuccess)
        assertEquals(listOf("code"), commands(exclusions).execute(command(Derived(Code("")))).validationResults.flatMap { it.members })
        assertTrue(commands(listOf(ConceptValidationExclusion(Derived::class.java, "code"))).execute(command(Derived(Code("")))).isSuccess)
    }

    @Test
    fun `nested owner cycles sibling owner containers and independent query roots retain traversal`() : Unit = runBlocking {
        val shared = Code("")
        val root = Graph(Owner(shared, shared), Base(Code("")), listOf(Code(""))).apply { cycle = this }
        val exclusion = ConceptValidationExclusion(Owner::class.java, "aIgnored")
        assertEquals(listOf("input.zRequired", "other.code", "values[0]"), commands(listOf(exclusion)).execute(command(root)).validationResults.flatMap { it.members })
        val query = DefaultQueryValidationFilter(emptyList(), listOf(concept), emptyList(), listOf(exclusion))
        assertEquals(listOf("first.zRequired", "second.zRequired", "root"), query.execute(query(linkedMapOf(
            "first" to root.input, "second" to root.input, "root" to shared, "null" to null
        ))).validationResults.flatMap { it.members })
        assertTrue(query.execute(query(emptyMap())).isSuccess)
    }

    @Test
    fun `registration validates declared metadata without executing getters and snapshots registrations`() : Unit = runBlocking {
        val registration = ConceptValidationExclusion(ThrowingGetter::class.java, "code")
        assertEquals(ThrowingGetter::class.java, registration.ownerType)
        assertEquals("code", registration.member)
        val directName = ConceptValidationExclusion(EscapedName::class.java, "customer-code")
        assertTrue(commands(listOf(directName)).execute(command(EscapedName(Code("")))).isSuccess)
        val exclusions = mutableListOf(ConceptValidationExclusion(Base::class.java, "code"))
        val filter = commands(exclusions)
        exclusions.clear()
        assertTrue(filter.execute(command(Base(Code("")))).isSuccess)
        for ((member, reason) in listOf("" to "blank", " " to "blank", "a.b" to "direct", "a[0]" to "direct", "*" to "direct",
            "absent" to "public readable", "text" to "declared type", "any" to "declared type", "codes" to "declared type", "hidden" to "public readable")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { ConceptValidationExclusion(Invalid::class.java, member) }
            assertTrue(failure.message!!.contains(reason), failure.message)
        }
    }

    @Test
    fun `root typed and owner model rules remain independent of excluded concept category`() : Unit = runBlocking {
        val calls = mutableListOf<String>()
        val typed = object : CommandValidator<Base> {
            override val commandType = Base::class.java
            override suspend fun validate(command: Base, context: CommandContext): List<ValidationResult> {
                calls.add("typed")
                return listOf(ValidationResult.error("typed"))
            }
        }
        val model = object : ModelValidator<Base> {
            override val modelType = Base::class.java
            override suspend fun validate(model: Base, context: ModelValidationContext): List<ValidationResult> {
                calls.add("model")
                return listOf(ValidationResult.error("model"))
            }
        }
        val result = DefaultCommandValidationFilter(listOf(typed), listOf(concept), listOf(model),
            listOf(ConceptValidationExclusion(Base::class.java, "code"))).execute(command(Base(Code(""))))
        assertEquals(listOf("typed", "model"), calls)
        assertEquals(calls, result.validationResults.map { it.message })
    }

    @Test
    fun `excluded edge never runs throwing concept but required edge preserves failures cancellation and fatal errors`() : Unit = runBlocking {
        val exclusion = ConceptValidationExclusion(Owner::class.java, "aIgnored")
        val shared = Code("")
        val ordinary = IllegalStateException("secret")
        val cancelled = CancellationException("cancelled")
        val fatal = AssertionError("fatal")
        for (thrown in listOf(ordinary, cancelled, fatal, CompletionException(cancelled), CompletionException(fatal))) {
            val failing = object : ConceptValidator<Code> {
                override val conceptType = Code::class.java
                override fun validate(concept: Code): List<ValidationResult> { throw thrown }
            }
            val ignored = DefaultCommandValidationFilter(emptyList(), listOf(failing), emptyList(),
                listOf(ConceptValidationExclusion(Base::class.java, "code")))
            assertTrue(ignored.execute(command(Base(shared))).isSuccess)
            val filter = DefaultCommandValidationFilter(emptyList(), listOf(failing), emptyList(), listOf(exclusion))
            if (thrown === ordinary) {
                val result = filter.execute(command(Owner(shared, shared))).validationResults.single()
                assertEquals(listOf("zRequired"), result.members)
                assertEquals(ValidationResultReasons.VALIDATOR_FAILED, result.reason)
                assertEquals("The value could not be validated.", result.message)
            } else {
                val expected = if (thrown is CompletionException) requireNotNull(thrown.cause) else thrown
                assertSame(expected, assertThrows(expected.javaClass) { runBlocking { filter.execute(command(Owner(shared, shared))) } })
            }
        }
    }

    data class Code(val raw: String) : ConceptAs<String> { override fun value(): String = raw }
    class Owner(val aIgnored: Code, val zRequired: Code)
    open class Base(val code: Code)
    class Derived(code: Code) : Base(code)
    class Graph(val input: Owner, val other: Base, val values: List<Code>) { var cycle: Graph? = null }
    class ThrowingGetter { val code: Code get() = error("Must not execute registration getter") }
    class EscapedName(val `customer-code`: Code)
    class Invalid(val text: String, val any: Any, val codes: List<Code>, private val hidden: Code)
    private fun commands(exclusions: Iterable<ConceptValidationExclusion>) = DefaultCommandValidationFilter(emptyList(), listOf(concept), emptyList(), exclusions)
    private fun command(value: Any) = CommandContext(UUID.randomUUID(), value, value.javaClass, ArcPrincipal.anonymous(), serviceResolver = Services)
    private fun query(arguments: Map<String, Any?>): QueryContext {
        val request = QueryRequest(FullyQualifiedQueryName("exclusions.query"), arguments)
        return QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), null, null, Services, null, false)
    }
    private object Services : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
}
