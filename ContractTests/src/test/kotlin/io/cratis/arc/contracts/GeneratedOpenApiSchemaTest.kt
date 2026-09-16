// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.contracts.fixtures.BodyOutput
import io.cratis.arc.contracts.fixtures.CustomerName
import io.cratis.arc.contracts.fixtures.FixtureCircle
import io.cratis.arc.contracts.fixtures.FixtureFilter
import io.cratis.arc.contracts.fixtures.JavaFixtureImplementation
import io.cratis.arc.contracts.fixtures.KotlinBodyCommand
import io.cratis.arc.contracts.fixtures.OpenApiNamingView
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.openapi.springboot.ArcOpenApiGenerator
import io.swagger.v3.oas.models.media.Schema
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GeneratedOpenApiSchemaTest {
    private val module = ContractTestsArcArtifactModule()
    private val mapper = ArcObjectMapper.create()
    private val schemas = ArcOpenApiGenerator().generate(listOf(module)).openApi.components.schemas

    @Test
    fun `generated Kotlin interface declares fields and resolves single and collection references`() {
        assertEquals(listOf("name"), module.interfaces.single { it.name == "FixtureShape" }.properties.map { it.name })
        val contract = schema("FixtureShape")
        assertEquals("object", contract.type)
        assertEquals(setOf("name"), contract.properties.keys)
        assertEquals(listOf("name"), contract.required)
        assertEquals("string", contract.properties.getValue("name").type)
        val circle = mapper.readTree(mapper.writeValueAsString(FixtureCircle("circle", 2.0, Instant.EPOCH)))
        assertEquals("circle", circle["name"].stringValue())
        assertTrue(circle.propertyNames().containsAll(contract.required))
        assertEquals("#/components/schemas/FixtureShape", schema("FixtureResponse").properties["shape"]?.`$ref`)
        assertEquals("#/components/schemas/FixtureShape", schema("FixtureResponse").properties["shapes"]?.items?.`$ref`)
        assertEquals("#/components/schemas/FixtureShape", schema("ScenarioShapeCommand").properties["shape"]?.`$ref`)
        assertNull(contract.oneOf)
        assertNull(contract.discriminator)
        assertFalse(contract.properties.containsKey("radius"))
    }

    @Test
    fun `generated Java interface exposes its declared record contract rather than an unknown object`() {
        assertEquals(listOf("label"), module.interfaces.single { it.name == "JavaFixtureContract" }.properties.map { it.name })
        val contract = schema("JavaFixtureContract")
        assertEquals(setOf("label"), contract.properties.keys)
        assertEquals(listOf("label"), contract.required)
        assertEquals("string", contract.properties.getValue("label").type)
        val wire = mapper.readTree(mapper.writeValueAsString(JavaFixtureImplementation("java-label")))
        assertEquals("java-label", wire["label"].stringValue())
        assertEquals("#/components/schemas/JavaFixtureContract", schema("ScenarioShapeView").properties["javaContract"]?.`$ref`)
        assertNull(contract.oneOf)
        assertNull(contract.discriminator)
    }

    @Test
    fun `generated source names become the actual Arc JSON and TypeScript property and required names`() {
        assertEquals(
            listOf("Title", "OptionalTitle", "URLValue"),
            module.types.single { it.name == "OpenApiNamingView" }.properties.map { it.name }
        )
        val present = mapper.readTree(mapper.writeValueAsString(OpenApiNamingView("title", "optional", "url")))
        val absent = mapper.readTree(mapper.writeValueAsString(OpenApiNamingView("title", null, "url")))
        assertEquals(setOf("title", "optionalTitle", "URLValue"), present.propertyNames().toSet())
        assertEquals(setOf("title", "URLValue"), absent.propertyNames().toSet())
        val model = schema("OpenApiNamingView")
        assertEquals(present.propertyNames().toSet(), model.properties.keys)
        assertEquals(absent.propertyNames().toSet(), model.required.toSet())
        assertEquals(listOf("string", "null"), model.properties.getValue("optionalTitle").anyOf.map { it.type })
        assertNull(model.properties.getValue("optionalTitle").default)
        val proxy = Files.readString(Path.of(requireNotNull(System.getProperty("arc.contractTests.generatedProxies")), "OpenApiNamingView.ts"))
        assertTrue(proxy.contains("title!: string;"), proxy)
        assertTrue(proxy.contains("optionalTitle?: string;"), proxy)
        assertTrue(proxy.contains("URLValue!: string;"), proxy)
        assertFalse(proxy.contains("Title!:"), proxy)
    }

    @Test
    fun `body properties keep acronym naming and base model composition without flattening inherited state`() {
        val command = KotlinBodyCommand("z", "a")
        val wire = mapper.readTree(mapper.writeValueAsString(command))
        val model = schema("KotlinBodyCommand")
        assertEquals(wire.propertyNames().toSet(), model.properties.keys)
        assertEquals(wire.propertyNames().toSet(), model.required.toSet())
        assertTrue(model.properties.containsKey("URLValue"))
        assertEquals("#/components/schemas/BodyChild", model.properties["child"]?.`$ref`)
        val output = schema("BodyOutput")
        assertEquals(2, output.allOf.size)
        assertEquals("#/components/schemas/BodyBase", output.allOf.first().`$ref`)
        val own = output.allOf.last()
        assertEquals(setOf("id", "detail", "label"), own.properties.keys)
        assertFalse(own.properties.containsKey("baseBody"))
        assertNull(output.properties)
        val outputWire = mapper.readTree(mapper.writeValueAsString(BodyOutput("id")))
        assertEquals(outputWire.propertyNames().toSet(), own.properties.keys + schema("BodyBase").properties.keys)
    }

    @Test
    fun `generated concept array nullable enum and derived model schemas retain metadata boundaries`() {
        assertEquals("string", schema("CustomerName").type)
        assertEquals("\"customer\"", mapper.writeValueAsString(CustomerName("customer")))
        assertEquals("#/components/schemas/CustomerName", schema("MetadataCommand").properties["customerNames"]?.items?.`$ref`)
        val filter = schema("FixtureFilter")
        val id = UUID.randomUUID()
        val wire = mapper.readTree(mapper.writeValueAsString(FixtureFilter(listOf(id), null)))
        assertEquals(setOf("ids"), wire.propertyNames().toSet())
        assertEquals(listOf("ids"), filter.required)
        assertEquals("array", filter.properties["ids"]?.type)
        assertEquals("uuid", filter.properties["ids"]?.items?.format)
        assertEquals(id.toString(), wire["ids"][0].stringValue())
        assertEquals("#/components/schemas/FixtureState", filter.properties["state"]?.anyOf?.first()?.`$ref`)
        assertEquals(listOf(0, 1), schema("FixtureState").enum)
        val circle = schema("FixtureCircle")
        assertEquals("#/components/schemas/FixtureShapeBase", circle.allOf.first().`$ref`)
        val own = circle.allOf.last()
        val descriptor = module.types.single { it.name == "FixtureCircle" }
        assertEquals(descriptor.properties.map { it.name }.toSet() + "_derivedTypeId", own.properties.keys)
        assertEquals(listOf("circle"), own.properties["_derivedTypeId"]?.enum)
        assertTrue(own.required.contains("_derivedTypeId"))
        assertFalse(own.properties.containsKey("createdAt"))
        assertNull(circle.oneOf)
        assertNull(circle.discriminator)
    }

    private fun schema(name: String): Schema<*> = requireNotNull(schemas[name]) { "Missing generated schema $name" }
}
