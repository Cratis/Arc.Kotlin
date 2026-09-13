// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ConceptValidator
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ScenarioModelValidationTests {
    @Test
    fun `all scenario registrations combine model and concept rules in one filter`() : Unit = runBlocking {
        var invocations = 0
        val modelRule = object : ModelValidator<Code> {
            override val modelType = Code::class.java
            override suspend fun validate(model: Code, context: ModelValidationContext): List<ValidationResult> =
                if (model.value().isEmpty()) listOf(ValidationResult.error("model", listOf("value"))) else emptyList()
        }
        val conceptRule = object : ConceptValidator<Code> {
            override val conceptType = Code::class.java
            override fun validate(concept: Code): List<ValidationResult> =
                if (concept.value().isEmpty()) listOf(ValidationResult.error("concept", listOf("value"))) else emptyList()
        }
        val handler = object : CommandHandler {
            override val commandType = Input::class.java
            override val metadata = CommandDescriptor("Input", Input::class.java.name)
            override suspend fun invoke(context: CommandContext): Any { invocations++; return "handled" }
        }
        fun performer(transport: QueryTransportType): QueryPerformer = object : QueryPerformer {
            override val fullyQualifiedName = FullyQualifiedQueryName("scenario.$transport")
            override val descriptor = QueryDescriptor(transport.name, "scenario", "kotlin.String",
                parameters = listOf(ParameterDescriptor("code", Code::class.java.name)), transport = transport)
            override suspend fun perform(context: QueryContext): Any {
                invocations++
                return if (transport == QueryTransportType.OBSERVABLE) flowOf("data") else "data"
            }
        }
        val commands = CommandScenario<Input>(handler).withPrincipal(ArcPrincipal("tester", true))
            .addModelValidator(modelRule).addConceptValidator(conceptRule)
        val queries = QueryScenario<Any>(performer(QueryTransportType.REQUEST_RESPONSE)).withPrincipal(ArcPrincipal("tester", true))
            .addModelValidator(modelRule).addConceptValidator(conceptRule)
        val observables = ObservableQueryScenario<Any>(performer(QueryTransportType.OBSERVABLE)).withPrincipal(ArcPrincipal("tester", true))
            .addModelValidator(modelRule).addConceptValidator(conceptRule)
        val feedback = listOf(commands.execute(Input(Code(""))).result.validationResults,
            commands.validate(Input(Code(""))).result.validationResults,
            queries.perform(mapOf("code" to Code(""))).result.validationResults,
            observables.collect(1, arguments = mapOf("code" to Code(""))).shouldFail().validationResults)
        feedback.forEach {
            assertEquals(listOf("model", "concept"), it.map { result -> result.message })
            assertEquals(listOf("code.value", "code"), it.flatMap { result -> result.members })
        }
        assertEquals(0, invocations)
        commands.execute(Input(Code("valid"))).shouldSucceed()
        commands.validate(Input(Code("valid"))).shouldSucceed()
        queries.perform(mapOf("code" to Code("valid"))).shouldSucceed()
        observables.collect(1, arguments = mapOf("code" to Code("valid"))).shouldSucceed()
        assertEquals(3, invocations)
    }
    data class Code(private val raw: String) : ConceptAs<String> { override fun value(): String = raw }
    data class Input(val code: Code)
}
