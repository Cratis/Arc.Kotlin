// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.fundamentals.concepts.ConceptAs
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FundamentalsConceptExclusionTest {
    private val fundamentals = object : FundamentalsConceptValidator<Code> {
        override val conceptType = Code::class.java
        override fun validate(concept: Code) = listOf(ValidationResult.error("concept"))
    }

    @Test
    fun `separate instances validate only nonexcluded edges`() : Unit = runBlocking {
        val owner = Owner(Code(""), Code(""))
        assertEquals(listOf("zRequired"), commands("aIgnored").execute(command(owner)).validationResults.flatMap { it.members })
        assertEquals(listOf("aIgnored"), commands("zRequired").execute(command(owner)).validationResults.flatMap { it.members })
        assertEquals(listOf("aIgnored", "zRequired"), commands().execute(command(owner)).validationResults.flatMap { it.members })
    }

    @Test
    fun `ignored first shared alias validates at required edge`() : Unit = runBlocking {
        val shared = Code("")
        val owner = Owner(shared, shared)
        assertEquals(listOf("zRequired"), commands("aIgnored").execute(command(owner)).validationResults.flatMap { it.members })
        assertEquals(listOf("aIgnored"), commands("zRequired").execute(command(owner)).validationResults.flatMap { it.members })
        assertEquals(listOf("aIgnored"), commands().execute(command(owner)).validationResults.flatMap { it.members })
    }

    @Test
    fun `plain model rule keeps first visit while concept rule uses required alias`() : Unit = runBlocking {
        val model = object : ModelValidator<Code> {
            override val modelType = Code::class.java
            override suspend fun validate(model: Code, context: ModelValidationContext) =
                listOf(ValidationResult.error("model", listOf("value")))
        }
        val shared = Code("")
        val filter = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(model, fundamentals),
            listOf(ConceptValidationExclusion(Owner::class.java, "aIgnored")))
        val results = filter.execute(command(Owner(shared, shared))).validationResults
        assertEquals(listOf("model", "concept"), results.map { it.message })
        assertEquals(listOf("aIgnored.value", "zRequired"), results.flatMap { it.members })
    }

    @Suppress("DEPRECATION") // Exercise both concept-rule categories on the legacy concept contract.
    @Test
    fun `legacy and fundamentals rules share the required concept identity claim`() : Unit = runBlocking {
        val legacy = object : ConceptValidator<LegacyCode> {
            override val conceptType = LegacyCode::class.java
            override fun validate(concept: LegacyCode) = listOf(ValidationResult.error("legacy"))
        }
        val modern = object : FundamentalsConceptValidator<LegacyCode> {
            override val conceptType = LegacyCode::class.java
            override fun validate(concept: LegacyCode) = listOf(ValidationResult.error("fundamentals"))
        }
        val shared = LegacyCode("")
        val filter = DefaultCommandValidationFilter(emptyList(), listOf(legacy), listOf(modern),
            listOf(ConceptValidationExclusion(Owner2::class.java, "aIgnored")))
        val results = filter.execute(command(Owner2(shared, shared))).validationResults
        assertEquals(listOf("fundamentals", "legacy"), results.map { it.message })
        assertEquals(listOf("zRequired", "zRequired"), results.flatMap { it.members })
    }

    @Test
    fun `query arguments track identity independently and root concepts remain required`() : Unit = runBlocking {
        val shared = Code("")
        val filter = DefaultQueryValidationFilter(emptyList(), emptyList(), listOf(fundamentals),
            listOf(ConceptValidationExclusion(Owner::class.java, "aIgnored")))
        val results = filter.execute(query(linkedMapOf("first" to Owner(shared, shared), "root" to shared))).validationResults
        assertEquals(listOf("first.zRequired", "root"), results.flatMap { it.members })
    }

    @Test
    fun `excluded throwing rule is not invoked and required edge preserves failure feedback`() : Unit = runBlocking {
        val throwing = object : FundamentalsConceptValidator<Code> {
            override val conceptType = Code::class.java
            override fun validate(concept: Code): List<ValidationResult> = throw IllegalStateException("secret")
        }
        val shared = Code("")
        val ignored = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(throwing),
            listOf(ConceptValidationExclusion(Base::class.java, "code")))
        assertTrue(ignored.execute(command(Base(shared))).isSuccess)
        val required = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(throwing),
            listOf(ConceptValidationExclusion(Owner::class.java, "aIgnored")))
        val result = required.execute(command(Owner(shared, shared))).validationResults.single()
        assertEquals(listOf("zRequired"), result.members)
        assertEquals(ValidationResultReasons.VALIDATOR_FAILED, result.reason)
    }

    data class Code(val raw: String) : ConceptAs<String> { override fun value(): String = raw }
    class Owner(val aIgnored: Code, val zRequired: Code)
    class Base(val code: Code)
    @Suppress("DEPRECATION")
    data class LegacyCode(val raw: String) : io.cratis.arc.concepts.ConceptAs<String> { override fun value(): String = raw }
    class Owner2(val aIgnored: LegacyCode, val zRequired: LegacyCode)
    private fun commands(member: String? = null) = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(fundamentals),
        member?.let { listOf(ConceptValidationExclusion(Owner::class.java, it)) }.orEmpty())
    private fun command(value: Any) = CommandContext(UUID.randomUUID(), value, value.javaClass, ArcPrincipal.anonymous(), serviceResolver = Services)
    private fun query(arguments: Map<String, Any?>): QueryContext {
        val request = QueryRequest(FullyQualifiedQueryName("exclusions.query"), arguments)
        return QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), null, null, Services, null, false)
    }
    private object Services : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
}
