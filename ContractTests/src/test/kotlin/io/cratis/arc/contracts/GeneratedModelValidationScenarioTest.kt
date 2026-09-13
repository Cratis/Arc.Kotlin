// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.contracts.fixtures.FixtureCircle
import io.cratis.arc.contracts.fixtures.JavaFixtureImplementation
import io.cratis.arc.contracts.fixtures.JavaOrderId
import io.cratis.arc.contracts.fixtures.ScenarioShapeCommand
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.testing.CommandScenario
import io.cratis.arc.testing.ObservableQueryScenario
import io.cratis.arc.testing.QueryScenario
import io.cratis.arc.validation.ConceptValidator
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GeneratedModelValidationScenarioTest {
    private val module = ContractTestsArcArtifactModule()
    private val principal = ArcPrincipal("tester", true, setOf("viewer"), authenticationScheme = "bearer")
    private val strings = object : ModelValidator<String> {
        override val modelType = String::class.java
        override suspend fun validate(model: String, context: ModelValidationContext): List<ValidationResult> =
            if (model == "invalid") listOf(ValidationResult.error("model")) else emptyList()
    }

    @Test
    fun `generated command validates a reused polymorphic nested model after default JSON round trip`() : Unit = runBlocking {
        val rule = object : ModelValidator<FixtureCircle> {
            override val modelType = FixtureCircle::class.java
            override suspend fun validate(model: FixtureCircle, context: ModelValidationContext): List<ValidationResult> =
                if (model.radius < 0) listOf(ValidationResult.error("radius", listOf("radius"))) else emptyList()
        }
        val scenario = CommandScenario(module, ScenarioShapeCommand::class.java).withPrincipal(principal).addModelValidator(rule)
        val invalid = FixtureCircle("circle", -1.0, Instant.EPOCH)
        val command = ScenarioShapeCommand(invalid, invalid, JavaFixtureImplementation("record"))
        // JSON duplicates shared source identities into two distinct typed model instances.
        assertEquals(listOf("base.radius", "shape.radius"), scenario.validate(command).result.validationResults.flatMap { it.members })
        assertEquals(listOf("base.radius", "shape.radius"), scenario.execute(command).result.validationResults.flatMap { it.members })
        val valid = FixtureCircle("circle", 1.0, Instant.EPOCH)
        scenario.execute(ScenarioShapeCommand(valid, valid, JavaFixtureImplementation("record"))).shouldSucceed()
    }

    @Test
    fun `generated query and observable scenarios use model registration before actual invocations`() : Unit = runBlocking {
        val query = QueryScenario<Any>(module, name("JavaQueryReadModel.contextualJava"))
            .addModelValidator(strings)
        assertEquals(listOf("label"), query.perform(mapOf("label" to "invalid")).result.validationResults.single().members)
        query.perform(mapOf("label" to "valid")).shouldSucceed()
        val observable = ObservableQueryScenario<Any>(module, name("KotlinQueryReadModel.observeDefaulted"))
            .withPrincipal(principal).addPolicy("catalog", AuthorizationPolicy { AuthorizationResult.success() })
            .addModelValidator(strings)
        assertEquals(listOf("label"), observable.collect(1, arguments = mapOf("label" to "invalid")).shouldFail().validationResults.single().members)
        observable.collect(1, arguments = mapOf("label" to "valid")).shouldSucceed().shouldHaveEmissionCount(1)
        observable.collect(1).shouldSucceed().shouldHaveEmissionCount(1)
    }

    @Test
    fun `generated one-shot and observable concept arguments use additive concept registration`() : Unit = runBlocking {
        val concept = object : ConceptValidator<JavaOrderId> {
            override val conceptType = JavaOrderId::class.java
            override fun validate(concept: JavaOrderId): List<ValidationResult> = listOf(ValidationResult.error("concept", listOf("value")))
        }
        val query = QueryScenario<Any>(module, name("ConceptTemporalReadModel.findConceptTemporal"))
            .withPrincipal(principal).addConceptValidator(concept)
        // Rejected input prevents the generated performer from requiring its other arguments.
        assertEquals(listOf("javaIdentifier"), query.perform(mapOf("javaIdentifier" to JavaOrderId(UUID.randomUUID())))
            .result.validationResults.single().members)
        val observable = ObservableQueryScenario<Any>(module, name("KotlinObservableSnapshot.observeKotlinSnapshot"))
            .addConceptValidator(concept)
        assertEquals(listOf("concept"), observable.collect(1, arguments = mapOf("concept" to JavaOrderId(UUID.randomUUID())))
            .shouldFail().validationResults.single().members)
        assertTrue(module.commandHandlers.isNotEmpty())
    }
    private fun name(value: String) = FullyQualifiedQueryName("io.cratis.arc.contracts.fixtures.$value")
}
