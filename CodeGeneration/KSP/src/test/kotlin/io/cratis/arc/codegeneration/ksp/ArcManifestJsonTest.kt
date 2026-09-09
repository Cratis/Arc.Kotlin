// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.fasterxml.jackson.databind.JsonNode
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.CommandResponseValueDescriptor
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.EnumMemberDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryTransportType
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ArcManifestJsonTest {
    private val runtimeMapper = ArcObjectMapper.create()

    @Test
    fun `empty manifest retains all collections format order and exactly one trailing LF`() {
        val manifest = ArcArtifactManifest("Empty")
        assertEquals(
            """{"formatVersion":${ArcArtifactManifest.CURRENT_FORMAT_VERSION},"moduleName":"Empty","commands":[],"queries":[],"types":[],"interfaces":[],"enums":[],"concepts":[]}""" + "\n",
            ArcManifestJson.serialize(manifest)
        )
        assertEquivalent(manifest)
    }

    @Test
    fun `default metadata retains false booleans and empty collections while omitting absent optionals`() {
        val manifest = ArcArtifactManifest(
            "Defaults",
            commands = listOf(CommandDescriptor("Command", "fixture.Command")),
            queries = listOf(QueryDescriptor("all", "fixture.Model", "fixture.Model")),
            types = listOf(TypeDescriptor("Model", "fixture.Model", properties = listOf(PropertyDescriptor("name", "kotlin.String")))),
            enums = listOf(EnumDescriptor("State", "fixture.State"))
        )
        val root = assertEquivalent(manifest)
        val command = root["commands"][0]
        assertFalse(command.has("explicitPath"))
        assertFalse(command["routeOptions"].has("path"))
        assertFalse(command["authorization"].has("policy"))
        assertEquals(false, command["authorization"]["allowAnonymous"].booleanValue())
        assertTrue(command["authorization"]["roles"].isEmpty)
        assertTrue(command["authorization"]["schemes"].isEmpty)
        assertTrue(command["responseValues"].isEmpty)
        assertEquals(false, command["treatWarningsAsErrors"].booleanValue())
        assertFalse(root["types"][0].has("baseTypeName"))
        assertFalse(root["types"][0].has("derivedTypeId"))
        assertEquals(false, root["types"][0]["properties"][0]["isCommandKey"].booleanValue())
        assertEquals(false, root["enums"][0]["isFlags"].booleanValue())
        assertEquals("", root["enums"][0]["allFlagsExpression"].textValue())
    }

    @Test
    fun `full optional metadata retains Kotlin wire names and constructor property ordering`() {
        val root = assertEquivalent(fullManifest())
        assertEquals(
            listOf("name", "typeName", "properties", "routeOptions", "location", "authorization", "explicitPath", "treatWarningsAsErrors", "responseValues"),
            keys(root["commands"][0])
        )
        assertEquals(
            listOf("name", "fullyQualifiedName", "location", "properties", "baseTypeName", "derivedTypeId"),
            keys(root["types"][0])
        )
        assertEquals(
            listOf("name", "fullyQualifiedName", "location", "members", "isFlags", "allFlagsExpression"),
            keys(root["enums"][0])
        )
        val property = root["commands"][0]["properties"][0]
        assertEquals(listOf("name", "shape", "isCommandKey", "validationRules", "validateRecursively", "derivatives"), keys(property))
        assertEquals(listOf("ruleName", "arguments", "message"), keys(property["validationRules"][0]))
        assertEquals("ID", property["name"].textValue())
        assertEquals(true, property["isCommandKey"].booleanValue())
        assertFalse(property.has("commandKey"))
        assertFalse(root["enums"][0].has("flags"))
        assertEquals("policy", root["commands"][0]["authorization"]["policy"].textValue())
        assertEquals("/command", root["commands"][0]["explicitPath"].textValue())
        assertEquals("Flags.read | Flags.write", root["enums"][0]["allFlagsExpression"].textValue())
    }

    @Test
    fun `canonical nested shapes named protocol enums and numeric transport enums retain exact representations`() {
        val manifest = fullManifest()
        val root = assertEquivalent(manifest)
        val map = root["commands"][0]["properties"][1]["shape"]
        assertEquals("MAP", map["kind"].textValue())
        assertEquals(true, map["nullable"].booleanValue())
        assertEquals("STRING", map["keyCodec"].textValue())
        assertEquals(false, map["keyShape"]["nullable"].booleanValue())
        assertEquals("MAP", map["valueShape"]["kind"].textValue())
        assertEquals("ARRAY", map["valueShape"]["valueShape"]["sequenceKind"].textValue())
        SequenceKind.entries.forEachIndexed { index, kind ->
            val shape = root["commands"][0]["properties"][index + 2]["shape"]
            assertEquals(kind.name, shape["sequenceKind"].textValue())
            assertEquals(true, shape["nullable"].booleanValue())
        }
        manifest.queries.forEachIndexed { index, query ->
            val node = root["queries"][index]
            assertEquals(query.queryHttpMethod.ordinal, node["queryHttpMethod"].intValue())
            assertEquals(query.transport.ordinal, node["transport"].intValue())
            assertEquals(query.routeOptions.transport.ordinal, node["routeOptions"]["transport"].intValue())
            query.parameters.forEachIndexed { parameterIndex, parameter ->
                val parameterNode = node["parameters"][parameterIndex]
                assertEquals(parameter.source.name, parameterNode["source"].textValue())
                assertEquals(parameter.source == QueryParameterSource.CLIENT, parameterNode["hasDefault"].booleanValue())
            }
        }
        CommandResponseValueDisposition.entries.forEachIndexed { index, disposition ->
            assertEquals(disposition.ordinal, root["commands"][0]["responseValues"][index]["disposition"].intValue())
        }
        assertEquals(-3, root["enums"][1]["members"][0]["value"].intValue())
    }

    @Test
    fun `canonical manifest never emits legacy compatibility projections`() {
        val root = assertEquivalent(fullManifest())
        val command = root["commands"][0]
        listOf("responseTypeName", "responseIsEnumerable").forEach { assertFalse(command.has(it), it) }
        val properties = command["properties"].toList() + root["types"][0]["properties"].toList() +
            root["interfaces"][0]["properties"].toList()
        val parameters = root["queries"].flatMap { it["parameters"].toList() }
        (properties + parameters + command["responseValues"].toList()).forEach { node ->
            listOf("typeName", "isNullable", "isEnumerable", "elementTypeName", "isFromServices").forEach {
                assertFalse(node.has(it), "$it in $node")
            }
            assertTrue(node.has("shape"))
        }
        root["queries"].forEach {
            assertFalse(it.has("returnTypeName"))
            assertFalse(it.has("isEnumerable"))
            assertTrue(it.has("returnShape"))
        }
    }

    @Test
    fun `validation arguments messages escaping and Arc time serialization remain byte equivalent`() {
        val arguments = listOf(
            1, Long.MAX_VALUE, BigDecimal("1.250"), true, "quoted \" value\\\nÆ", Double.NaN,
            LocalDate.of(2025, 1, 2), LocalTime.of(3, 4, 5, 123456700),
            Instant.parse("2025-01-02T03:04:05Z"), Duration.ofSeconds(90)
        )
        val rules = listOf(
            ValidationRuleDescriptor("control", arguments, "quotes \" slash\\ newline\nÆ"),
            ValidationRuleDescriptor("absent"),
            ValidationRuleDescriptor("empty", message = "")
        )
        val manifest = ArcArtifactManifest("Validation", types = listOf(
            TypeDescriptor("Model", "fixture.Model", properties = listOf(
                PropertyDescriptor("value", "kotlin.String", validationRules = rules)
            ))
        ))
        val serialized = assertEquivalent(manifest)["types"][0]["properties"][0]["validationRules"]
        assertEquals(rules[0].message, serialized[0]["message"].textValue())
        assertEquals("NaN", serialized[0]["arguments"][5].textValue())
        assertEquals("2025-01-02", serialized[0]["arguments"][6].textValue())
        assertEquals("03:04:05.1234567", serialized[0]["arguments"][7].textValue())
        assertFalse(serialized[1].has("message"))
        assertTrue(serialized[1]["arguments"].isEmpty)
        assertEquals("", serialized[2]["message"].textValue())
    }

    @Test
    fun `reusing the writer across manifests does not alter ordering or bytes`() {
        val full = fullManifest()
        val expected = ArcManifestJson.serialize(full)
        repeat(3) {
            assertEquivalent(ArcArtifactManifest("Empty"))
            assertEquals(expected, ArcManifestJson.serialize(full))
        }
    }

    private fun assertEquivalent(manifest: ArcArtifactManifest): JsonNode {
        val expected = runtimeMapper.writeValueAsString(manifest) + "\n"
        val actual = ArcManifestJson.serialize(manifest)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
        assertTrue(actual.endsWith("\n"))
        assertFalse(actual.endsWith("\n\n"))
        assertFalse(actual.contains('\r'))
        return runtimeMapper.readTree(actual)
    }

    private fun keys(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    private fun fullManifest(): ArcArtifactManifest {
        val string = TypeShapeDescriptor.value("kotlin.String")
        val array = TypeShapeDescriptor.sequence(SequenceKind.ARRAY, TypeShapeDescriptor.value("kotlin.Int"))
        val map = TypeShapeDescriptor.map(string, TypeShapeDescriptor.map(string, array), MapKeyCodec.STRING, true)
        val rules = listOf(ValidationRuleDescriptor("length", listOf(2, 40), "Required"))
        val properties = listOf(
            PropertyDescriptor("ID", string, true, rules),
            PropertyDescriptor("optionalMap", map, false, rules, true, listOf("fixture.Derived"))
        ) + SequenceKind.entries.map { PropertyDescriptor(it.name, TypeShapeDescriptor.sequence(it, string, true)) } +
            PropertyDescriptor("optionalValue", TypeShapeDescriptor.value("kotlin.String", true))
        val authorization = AuthorizationMetadata(true, "policy", listOf("admin", "editor"), listOf("scheme"))
        val route = RouteOptions("/explicit", QueryTransportType.OBSERVABLE)
        val command = CommandDescriptor(
            name = "Command", typeName = "fixture.Command", properties = properties, routeOptions = route,
            authorization = authorization, explicitPath = "/command", treatWarningsAsErrors = true,
            responseValues = listOf(
                CommandResponseValueDescriptor(array, CommandResponseValueDisposition.CLIENT),
                CommandResponseValueDescriptor(string, CommandResponseValueDisposition.HANDLED)
            )
        )
        val parameters = QueryParameterSource.entries.map {
            ParameterDescriptor(it.name, string, it, it == QueryParameterSource.CLIENT, rules, true)
        }
        val queries = QueryHttpMethodType.entries.flatMap { method ->
            QueryTransportType.entries.map { transport ->
                val name = method.name + transport.name
                QueryDescriptor(
                    name, "fixture.Model", array, parameters, route, "fixture.Model.$name", listOf("fixture"),
                    authorization, "/$name", method, transport, true, true, true
                )
            }
        }
        return ArcArtifactManifest(
            "Full", commands = listOf(command), queries = queries,
            types = listOf(TypeDescriptor("Derived", "fixture.Derived", listOf("fixture"), properties, "fixture.Base", "id")),
            interfaces = listOf(InterfaceDescriptor("Base", "fixture.Base", properties = properties)),
            enums = listOf(
                EnumDescriptor("Flags", "fixture.Flags", members = listOf(
                    EnumMemberDescriptor("None", 0), EnumMemberDescriptor("Read", 1), EnumMemberDescriptor("Write", 8)
                ), isFlags = true),
                EnumDescriptor("Ordinary", "fixture.Ordinary", members = listOf(EnumMemberDescriptor("Negative", -3)))
            ),
            concepts = listOf(ConceptDescriptor("ID", "fixture.ID", "java.util.UUID"))
        )
    }
}
