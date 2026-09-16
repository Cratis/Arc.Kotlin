// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.contracts.fixtures.FluentContractInput
import io.cratis.arc.contracts.fixtures.IgnoreContractCommand
import io.cratis.arc.contracts.fixtures.IgnoreContractInput
import io.cratis.arc.contracts.fixtures.IgnoreContractRules
import io.cratis.arc.contracts.fixtures.IgnoreContractView
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.openapi.springboot.ArcOpenApiGenerator
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.node.ObjectNode
import io.cratis.arc.testing.CommandScenario
import io.cratis.arc.testing.QueryScenario
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class IgnoreValidationContractTest {
    private fun input(active: FluentContractInput? = null, sibling: String = "ok") = IgnoreContractInput(
        "", FluentContractInput(""), listOf(FluentContractInput("")), arrayOf(FluentContractInput("")), mapOf("wire" to ""), active, sibling)

    @Test
    fun `generated command and QUERY enforce active aliases while ignoring declared invalid members`() : Unit = runBlocking {
        val module = ContractTestsArcArtifactModule()
        val command = CommandScenario(module, IgnoreContractCommand::class.java)
        command.execute(IgnoreContractCommand(input())).shouldSucceed()
        val bad = input(FluentContractInput(""), "")
        val expected = listOf("input.sibling", "input.validated.name")
        assertEquals(expected, command.validate(IgnoreContractCommand(bad)).result.validationResults.flatMap { it.members })
        assertEquals(expected, command.execute(IgnoreContractCommand(bad)).result.validationResults.flatMap { it.members })
        val query = QueryScenario<IgnoreContractView>(module, FullyQualifiedQueryName("${IgnoreContractView::class.java.name}.checkIgnoredContract"))
        assertEquals(expected, query.perform(mapOf("input" to bad)).result.validationResults.flatMap { it.members })
        query.perform(mapOf("input" to input())).shouldSucceed()
    }

    @Test
    fun `generated OpenAPI keeps ignored wire properties and binding requirements without validation constraints`() {
        val schemas = ArcOpenApiGenerator().generate(listOf(ContractTestsArcArtifactModule())).openApi.components.schemas
        val schema = schemas.getValue("IgnoreContractInput")
        assertTrue(schema.required.containsAll(listOf("ignoredList", "ignoredArray", "ignoredMap")))
        assertTrue(schema.properties.keys.containsAll(listOf("ignored", "ignoredChild", "ignoredList", "ignoredArray", "ignoredMap", "validated", "sibling")))
        val ignored = schema.properties.getValue("ignoredList")
        assertEquals("array", ignored.type)
        assertNull(ignored.minItems)
        assertNull(ignored.maxItems)
        assertEquals(listOf("input"), schemas.getValue("IgnoreContractCommand").required)
        val mapper = ArcObjectMapper.create()
        val tree = mapper.readTree(mapper.writeValueAsString(input())) as ObjectNode
        tree.remove("ignoredList")
        assertThrows(MismatchedInputException::class.java) { mapper.treeToValue(tree, IgnoreContractInput::class.java) }
    }

    @Test
    fun `generated manifest factories serialization and declared fingerprints agree`() {
        val module = ContractTestsArcArtifactModule()
        val mapper = ArcObjectMapper.create()
        val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/cratis/arc/ContractTests.json")).use { it.readAllBytes() }
        val manifest = mapper.readValue(bytes, ArcArtifactManifest::class.java)
        assertEquals(8, manifest.formatVersion)
        val model = module.types.single { it.fullyQualifiedName == IgnoreContractInput::class.java.name }
        assertEquals(model.properties, manifest.types.single { it.fullyQualifiedName == model.fullyQualifiedName }.properties)
        assertEquals(setOf("ignored", "ignoredChild", "ignoredList", "ignoredArray", "ignoredMap"), model.properties.filter { it.ignoreValidation }.map { it.name }.toSet())
        model.properties.filter { it.ignoreValidation }.forEach { assertTrue(it.validationRules.isEmpty()); assertFalse(it.validateRecursively) }
        val registration = module.fluentValidators.single { it.validator is IgnoreContractRules }
        assertEquals(registration.expectedRules, registration.validator.rules)
        assertEquals(listOf("ignored", "ignoredList", "sibling"), registration.expectedRules.map { it.member })
        val json = mapper.writeValueAsString(input())
        assertEquals("", mapper.readTree(json).path("ignored").stringValue())
        assertTrue(mapper.readTree(json).has("ignoredMap"))
        assertEquals("", mapper.readValue(json, IgnoreContractInput::class.java).ignoredChild?.name)
        assertTrue(IgnoreContractRules().validate(input()).isEmpty())
    }
}
