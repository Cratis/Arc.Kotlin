// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.openapi.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.EnumMemberDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class ArcOpenApiSchemaFidelityTests {
    @Test
    fun `model schema uses the default Arc wire names for both properties and required entries`() {
        val module = object : ArcArtifactModule(emptyList(), emptyList(), types = listOf(
            TypeDescriptor("NamedModel", "sample.NamedModel", emptyList(), namedProperties())
        )) {}
        val wire = ArcObjectMapper.create().readTree(ArcObjectMapper.create().writeValueAsString(NamedModel("title", null, "url")))
        val schema = ArcOpenApiGenerator().generate(listOf(module)).openApi.components.schemas.getValue("NamedModel")
        assertEquals(setOf("title", "optionalTitle", "URLValue"), schema.properties.keys)
        assertEquals(wire.propertyNames().toSet(), schema.required.toSet())
        assertEquals(listOf("string", "null"), schema.properties.getValue("optionalTitle").anyOf.map { it.type })
    }

    @Test
    fun `command without a type descriptor also uses Arc property names`() {
        val handler = object : CommandHandler {
            override val commandType: Class<*> = NamedModel::class.java
            override val metadata = CommandDescriptor("NamedCommand", "sample.NamedCommand", namedProperties())
            override suspend fun invoke(context: CommandContext): Any? = error("Schema generation must not invoke a command")
        }
        val module = object : ArcArtifactModule(listOf(handler), emptyList()) {}
        val schema = ArcOpenApiGenerator().generate(listOf(module)).openApi.components.schemas.getValue("NamedCommand")
        assertEquals(setOf("title", "optionalTitle", "URLValue"), schema.properties.keys)
        assertEquals(setOf("title", "URLValue"), schema.required.toSet())
    }

    @Test
    fun `interface schemas preserve declared fields optionality and recursive references without inventing polymorphism`() {
        val module = object : ArcArtifactModule(emptyList(), emptyList(), interfaces = listOf(
            InterfaceDescriptor("NamedContract", "sample.NamedContract", properties = namedProperties() +
                PropertyDescriptor("Next", "sample.NamedContract", isNullable = true))
        )) {}
        val schema = ArcOpenApiGenerator().generate(listOf(module)).openApi.components.schemas.getValue("NamedContract")
        assertEquals(setOf("title", "optionalTitle", "URLValue", "next"), schema.properties.keys)
        assertEquals(setOf("title", "URLValue"), schema.required.toSet())
        assertEquals("#/components/schemas/NamedContract", schema.properties.getValue("next").anyOf.first().`$ref`)
        assertEquals("null", schema.properties.getValue("next").anyOf.last().type)
        assertEquals(listOf("string", "null"), schema.properties.getValue("optionalTitle").anyOf.map { it.type })
        assertNull(schema.oneOf)
        assertNull(schema.allOf)
        assertNull(schema.discriminator)
    }

    @Test
    fun `interfaces share deterministic collision and reserved builtin naming with every descriptor category`() {
        val builtins = listOf("CommandResult", "QueryResult", "ValidationResult", "PagingInfo", "ChangeSet", "Identity")
        val contracts = listOf("sample.a.Shared", "sample.b.Shared", "sample.c.Shared", "sample.d.Shared")
        val interfaces = object : ArcArtifactModule(emptyList(), emptyList(), interfaces =
            (builtins.map { "sample.$it" } + contracts.first()).map { name ->
                InterfaceDescriptor(name.substringAfterLast('.'), name, properties = listOf(PropertyDescriptor("Title", "kotlin.String")))
            }
        ) {}
        val values = object : ArcArtifactModule(emptyList(), emptyList(),
            types = listOf(
                TypeDescriptor("Shared", contracts[1], emptyList(), listOf(PropertyDescriptor("count", "kotlin.Int"))),
                TypeDescriptor("Holder", "sample.Holder", emptyList(),
                    (builtins.map { "sample.$it" } + contracts).mapIndexed { index, name -> PropertyDescriptor("value$index", name) })
            ),
            enums = listOf(EnumDescriptor("Shared", contracts[2], emptyList(), listOf(EnumMemberDescriptor("Active", 17)))),
            concepts = listOf(ConceptDescriptor("Shared", contracts[3], "kotlin.String"))
        ) {}
        val generator = ArcOpenApiGenerator()
        val document = generator.generate(listOf(interfaces, values, interfaces))
        val schemas = document.openApi.components.schemas
        val fullNames = builtins.map { "sample.$it" } + contracts
        assertEquals((builtins + fullNames + "Holder").toSet(), schemas.keys)
        fullNames.forEachIndexed { index, name ->
            assertEquals("#/components/schemas/$name", schemas.getValue("Holder").properties["value$index"]?.`$ref`)
        }
        assertEquals(setOf("title"), schemas.getValue(contracts.first()).properties.keys)
        assertEquals(setOf("count"), schemas.getValue(contracts[1]).properties.keys)
        assertEquals(listOf(17), schemas.getValue(contracts[2]).enum)
        assertEquals("string", schemas.getValue(contracts[3]).type)
        builtins.forEach { name ->
            assertFalse(schemas.getValue(name).properties.containsKey("title"))
            assertEquals(setOf("title"), schemas.getValue("sample.$name").properties.keys)
        }
        assertArrayEquals(document.json(), generator.generate(listOf(values, interfaces)).json())
    }

    private fun namedProperties(): List<PropertyDescriptor> = listOf(
        PropertyDescriptor("Title", "kotlin.String"),
        PropertyDescriptor("OptionalTitle", "kotlin.String", isNullable = true),
        PropertyDescriptor("URLValue", "kotlin.String")
    )

    private data class NamedModel(val Title: String, val OptionalTitle: String?, val URLValue: String)
}
