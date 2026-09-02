// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TypeShapeManifestJsonTest {
    private val mapper = ArcObjectMapper.create()

    @Test
    fun `nested shapes have deterministic canonical JSON property order`() {
        val shape = nestedShape()

        assertEquals(
            """{"kind":"MAP","nullable":false,"keyShape":{"kind":"VALUE","nullable":false,"typeName":"kotlin.String"},"valueShape":{"kind":"SEQUENCE","nullable":true,"sequenceKind":"LIST","elementShape":{"kind":"VALUE","nullable":true,"typeName":"tests.Result"}},"keyCodec":"STRING"}""",
            mapper.writeValueAsString(shape)
        )
    }

    @Test
    fun `format 5 manifest round trip uses only canonical shape metadata`() {
        val shape = nestedShape()
        val manifest = ArcArtifactManifest(
            moduleName = "shapes",
            commands = listOf(
                CommandDescriptor(
                    name = "Run",
                    typeName = "tests.Run",
                    properties = listOf(PropertyDescriptor("payload", shape)),
                    responseValues = listOf(
                        CommandResponseValueDescriptor(shape, CommandResponseValueDisposition.CLIENT)
                    )
                )
            ),
            queries = listOf(
                QueryDescriptor(
                    name = "all",
                    declaringTypeName = "tests.Queries",
                    returnShape = shape,
                    parameters = listOf(
                        ParameterDescriptor("filter", shape, QueryParameterSource.QUERY_CONTEXT)
                    ),
                    supportsPaging = true,
                    supportsSorting = true
                )
            )
        )

        val json = mapper.writeValueAsString(manifest)
        val tree = mapper.readTree(json)
        val property = tree.path("commands").path(0).path("properties").path(0)
        val response = tree.path("commands").path(0).path("responseValues").path(0)
        val query = tree.path("queries").path(0)
        val parameter = query.path("parameters").path(0)

        assertEquals(5, tree.path("formatVersion").intValue())
        assertTrue(property.has("shape"))
        assertFalse(property.has("typeName"))
        assertFalse(property.has("isNullable"))
        assertFalse(property.has("isEnumerable"))
        assertFalse(property.has("elementTypeName"))
        assertTrue(parameter.has("shape"))
        assertEquals("QUERY_CONTEXT", parameter.path("source").textValue())
        assertFalse(parameter.path("hasDefault").booleanValue())
        assertFalse(parameter.has("isFromServices"))
        assertFalse(parameter.has("typeName"))
        assertTrue(response.has("shape"))
        assertFalse(response.has("typeName"))
        assertFalse(response.has("isEnumerable"))
        assertTrue(query.has("returnShape"))
        assertTrue(query.path("supportsPaging").booleanValue())
        assertTrue(query.path("supportsSorting").booleanValue())
        assertFalse(query.has("returnTypeName"))
        assertFalse(query.has("isEnumerable"))

        val roundTripped = mapper.readValue(json, ArcArtifactManifest::class.java)
        assertEquals(shape, roundTripped.commands.single().properties.single().shape)
        assertEquals(shape, roundTripped.commands.single().responseValues.single().shape)
        assertEquals(shape, roundTripped.queries.single().parameters.single().shape)
        assertEquals(
            QueryParameterSource.QUERY_CONTEXT,
            roundTripped.queries.single().parameters.single().source
        )
        assertEquals(shape, roundTripped.queries.single().returnShape)
        assertTrue(roundTripped.queries.single().supportsPaging)
        assertTrue(roundTripped.queries.single().supportsSorting)
        assertEquals(json, mapper.writeValueAsString(roundTripped))
    }

    @Test
    fun `legacy flat JSON deserializes to canonical compatibility projections`() {
        val property = mapper.readValue(
            """
            {
              "name": "values",
              "typeName": "kotlin.collections.List",
              "isNullable": true,
              "isEnumerable": true,
              "elementTypeName": "tests.Value"
            }
            """.trimIndent(),
            PropertyDescriptor::class.java
        )
        val parameter = mapper.readValue(
            """
            {
              "name": "value",
              "typeName": "tests.Value",
              "isFromServices": true
            }
            """.trimIndent(),
            ParameterDescriptor::class.java
        )
        val query = mapper.readValue(
            """
            {
              "name": "all",
              "declaringTypeName": "tests.Queries",
              "returnTypeName": "tests.Value",
              "isEnumerable": true
            }
            """.trimIndent(),
            QueryDescriptor::class.java
        )

        assertEquals(TypeShapeKind.SEQUENCE, property.shape.kind)
        assertEquals(SequenceKind.LIST, property.shape.sequenceKind)
        assertEquals("tests.Value", property.elementTypeName)
        assertEquals(TypeShapeDescriptor.value("tests.Value"), parameter.shape)
        assertEquals(QueryParameterSource.SERVICE, parameter.source)
        assertEquals(true, parameter.isFromServices)
        assertEquals(TypeShapeKind.SEQUENCE, query.returnShape.kind)
        assertEquals("tests.Value", query.returnTypeName)
        assertEquals(true, query.isEnumerable)
        assertFalse(query.supportsPaging)
        assertFalse(query.supportsSorting)
    }

    @Test
    fun `parameter JSON has deterministic canonical property order and defaults missing metadata`() {
        val descriptor = ParameterDescriptor(
            "value",
            TypeShapeDescriptor.value("tests.Value"),
            QueryParameterSource.CLIENT,
            true
        )

        val json = mapper.writeValueAsString(descriptor)
        assertEquals(
            """{"name":"value","shape":{"kind":"VALUE","nullable":false,"typeName":"tests.Value"},"source":"CLIENT","hasDefault":true,"validationRules":[],"validateRecursively":false}""",
            json
        )
        assertEquals(descriptor, mapper.readValue(json, ParameterDescriptor::class.java))

        val withoutSourceOrDefault = mapper.readValue(
            """{"name":"value","shape":{"kind":"VALUE","nullable":false,"typeName":"tests.Value"}}""",
            ParameterDescriptor::class.java
        )
        assertEquals(QueryParameterSource.CLIENT, withoutSourceOrDefault.source)
        assertFalse(withoutSourceOrDefault.isFromServices)
        assertFalse(withoutSourceOrDefault.hasDefault)
    }

    @Test
    fun `deserialization rejects contradictory parameter source projections`() {
        listOf(
            QueryParameterSource.CLIENT to true,
            QueryParameterSource.SERVICE to false,
            QueryParameterSource.QUERY_REQUEST to true,
            QueryParameterSource.QUERY_CONTEXT to true,
            QueryParameterSource.HOST_ADAPTER to true
        ).forEach { (source, isFromServices) ->
            assertThrows(ValueInstantiationException::class.java) {
                mapper.readValue(
                    """
                    {
                      "name": "value",
                      "shape": {"kind": "VALUE", "nullable": false, "typeName": "tests.Value"},
                      "source": "$source",
                      "isFromServices": $isFromServices
                    }
                    """.trimIndent(),
                    ParameterDescriptor::class.java
                )
            }
        }
    }

    @Test
    fun `deserialization rejects defaults on infrastructure and service parameters`() {
        QueryParameterSource.entries.filterNot { it == QueryParameterSource.CLIENT }.forEach { source ->
            assertThrows(ValueInstantiationException::class.java) {
                mapper.readValue(
                    """
                    {
                      "name": "value",
                      "shape": {"kind": "VALUE", "nullable": false, "typeName": "tests.Value"},
                      "source": "$source",
                      "hasDefault": true
                    }
                    """.trimIndent(),
                    ParameterDescriptor::class.java
                )
            }
        }
    }

    @Test
    fun `deserialization rejects contradictory legacy and canonical shape metadata`() {
        assertThrows(ValueInstantiationException::class.java) {
            mapper.readValue(
                """
                {
                  "name": "value",
                  "typeName": "tests.Other",
                  "shape": {"kind": "VALUE", "nullable": false, "typeName": "tests.Value"}
                }
                """.trimIndent(),
                PropertyDescriptor::class.java
            )
        }
        assertThrows(ValueInstantiationException::class.java) {
            mapper.readValue(
                """
                {
                  "name": "all",
                  "declaringTypeName": "tests.Queries",
                  "returnTypeName": "tests.Value",
                  "isEnumerable": true,
                  "returnShape": {"kind": "VALUE", "nullable": false, "typeName": "tests.Value"}
                }
                """.trimIndent(),
                QueryDescriptor::class.java
            )
        }
    }

    private fun nestedShape(): TypeShapeDescriptor = TypeShapeDescriptor.map(
        TypeShapeDescriptor.value("kotlin.String"),
        TypeShapeDescriptor.sequence(
            SequenceKind.LIST,
            TypeShapeDescriptor.value("tests.Result", nullable = true),
            nullable = true
        )
    )
}
