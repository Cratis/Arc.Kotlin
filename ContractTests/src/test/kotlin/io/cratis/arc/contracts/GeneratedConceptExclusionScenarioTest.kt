// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.contracts.fixtures.JavaCustomerCode
import io.cratis.arc.contracts.fixtures.KotlinExclusionCommand
import io.cratis.arc.contracts.fixtures.KotlinExclusionInput
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.testing.CommandScenario
import io.cratis.arc.testing.ObservableQueryScenario
import io.cratis.arc.testing.QueryScenario
import io.cratis.arc.validation.ConceptValidationExclusion
import io.cratis.arc.validation.ConceptValidator
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class GeneratedConceptExclusionScenarioTest {
    private val module = ContractTestsArcArtifactModule()
    private val exclusion = ConceptValidationExclusion(KotlinExclusionInput::class.java, "ignored")
    private val concept = object : ConceptValidator<JavaCustomerCode> {
        override val conceptType = JavaCustomerCode::class.java
        override fun validate(concept: JavaCustomerCode): List<ValidationResult> =
            if (concept.value() == "RULE") listOf(ValidationResult.error("concept")) else emptyList()
    }
    private val model = object : ModelValidator<JavaCustomerCode> {
        override val modelType = JavaCustomerCode::class.java
        override suspend fun validate(model: JavaCustomerCode, context: ModelValidationContext): List<ValidationResult> =
            if (model.value() == "MODEL") listOf(ValidationResult.error("model", listOf("value"))) else emptyList()
    }

    @Test
    fun `generated Kotlin commands honor direct exclusions after JSON and with actual shared identities`() : Unit = runBlocking {
        val scenario = CommandScenario(module, KotlinExclusionCommand::class.java)
            .addConceptValidator(concept).addModelValidator(model).addConceptExclusion(exclusion)
        val shared = JavaCustomerCode("RULE")
        val invalid = KotlinExclusionCommand(KotlinExclusionInput(shared, shared))
        for (roundTrip in listOf(true, false)) {
            scenario.withSerializationRoundTrip(roundTrip)
            assertEquals(listOf("input.required"), scenario.execute(invalid).result.validationResults.flatMap { it.members })
            assertEquals(listOf("input.required"), scenario.validate(invalid).result.validationResults.flatMap { it.members })
            scenario.execute(KotlinExclusionCommand(KotlinExclusionInput(shared, JavaCustomerCode("VALID")))).shouldSucceed()
            assertEquals(listOf("input.ignored.value"), scenario.execute(KotlinExclusionCommand(KotlinExclusionInput(JavaCustomerCode("MODEL"), null)))
                .result.validationResults.flatMap { it.members })
        }
    }

    @Test
    fun `generated Kotlin query and observable registrations keep nullable and omitted inputs independent`() : Unit = runBlocking {
        val query = QueryScenario<Any>(module, name("findKotlinExclusions"))
            .addConceptValidator(concept).addModelValidator(model).addConceptExclusion(exclusion)
        val observable = ObservableQueryScenario<Any>(module, name("observeKotlinExclusions"))
            .addConceptValidator(concept).addModelValidator(model).addConceptExclusion(exclusion)
        val shared = JavaCustomerCode("RULE")
        val invalid = mapOf("input" to KotlinExclusionInput(shared, shared))
        for (roundTrip in listOf(true, false)) {
            query.withArgumentSerializationRoundTrip(roundTrip)
            assertEquals(listOf("input.required"), query.perform(invalid).result.validationResults.flatMap { it.members })
        }
        assertEquals(listOf("input.required"), observable.collect(1, arguments = invalid).shouldFail().validationResults.flatMap { it.members })
        for (arguments in listOf(emptyMap(), mapOf("input" to null), mapOf("input" to KotlinExclusionInput(shared, null)))) {
            query.perform(arguments).shouldSucceed()
            observable.collect(1, arguments = arguments).shouldSucceed().shouldHaveEmissionCount(1)
        }
        val modelInvalid = mapOf("input" to KotlinExclusionInput(JavaCustomerCode("MODEL"), null))
        assertEquals(listOf("input.ignored.value"), query.perform(modelInvalid).result.validationResults.flatMap { it.members })
        assertEquals(listOf("input.ignored.value"), observable.collect(1, arguments = modelInvalid).shouldFail().validationResults.flatMap { it.members })
    }
    private fun name(method: String) = FullyQualifiedQueryName("io.cratis.arc.contracts.fixtures.KotlinExclusionView.$method")
}
