// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ConceptValidationExclusion
import io.cratis.arc.validation.ConceptValidator
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class ScenarioConceptExclusionTests {
    @Test
    fun `all three fluent exclusions retain unrelated pipeline filters`() : Unit = runBlocking {
        val concept = object : ConceptValidator<Code> {
            override val conceptType = Code::class.java
            override fun validate(concept: Code) = listOf(ValidationResult.error("concept"))
        }
        val handler = object : CommandHandler {
            override val commandType = Input::class.java
            override val metadata = CommandDescriptor("Input", Input::class.java.name)
            override suspend fun invoke(context: CommandContext): Any = error("Other filter must block handler")
        }
        fun performer(observable: Boolean): QueryPerformer = object : QueryPerformer {
            override val fullyQualifiedName = FullyQualifiedQueryName("scenario.exclusion.$observable")
            override val descriptor = QueryDescriptor("find", "scenario", "kotlin.String",
                parameters = listOf(ParameterDescriptor("input", Input::class.java.name)),
                transport = if (observable) QueryTransportType.OBSERVABLE else QueryTransportType.REQUEST_RESPONSE)
            override suspend fun perform(context: QueryContext): Any = if (observable) flowOf("data") else "data"
        }
        val command = CommandScenario<Input>(handler).withPrincipal(ArcPrincipal("tester", true)).addConceptValidator(concept)
            .addFilter(CommandFilter { CommandResult.invalid(it.correlationId, listOf(ValidationResult.error("other"))) })
        val queryFilter = QueryFilter { QueryResult.invalid<Any>(it.correlationId, listOf(ValidationResult.error("other"))) }
        val query = QueryScenario<Any>(performer(false)).withPrincipal(ArcPrincipal("tester", true)).addConceptValidator(concept).addFilter(queryFilter)
        val observable = ObservableQueryScenario<Any>(performer(true)).withPrincipal(ArcPrincipal("tester", true)).addConceptValidator(concept).addFilter(queryFilter)
        val exclusion = ConceptValidationExclusion(Input::class.java, "code")
        assertSame(command, command.addConceptExclusion(exclusion))
        assertSame(query, query.addConceptExclusion(exclusion))
        assertSame(observable, observable.addConceptExclusion(exclusion))
        val input = Input(Code("invalid"))
        val results = listOf(command.execute(input).result.validationResults, command.validate(input).result.validationResults,
            query.perform(mapOf("input" to input)).result.validationResults,
            observable.collect(1, arguments = mapOf("input" to input)).shouldFail().validationResults)
        results.forEach { assertEquals(listOf("other"), it.map { result -> result.message }) }
    }
    data class Code(val raw: String) : ConceptAs<String> { override fun value(): String = raw }
    data class Input(val code: Code)
}
