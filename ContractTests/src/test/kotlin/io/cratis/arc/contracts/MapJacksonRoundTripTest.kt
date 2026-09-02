// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import com.fasterxml.jackson.databind.JsonMappingException
import io.cratis.arc.contracts.fixtures.JavaMapMetadataCommand
import io.cratis.arc.contracts.fixtures.KotlinMapMetadataCommand
import io.cratis.arc.json.ArcObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class MapJacksonRoundTripTest {
    private val objectMapper = ArcObjectMapper.create()

    @Test
    fun `Kotlin string keyed maps roundtrip as JSON objects with recursive arrays and maps`() {
        val value = KotlinMapMetadataCommand(
            strings = linkedMapOf("language" to "kotlin"),
            numbers = linkedMapOf("values" to listOf(1, 2)),
            nested = linkedMapOf("flags" to linkedMapOf("ready" to true)),
            optional = null
        )

        val json = objectMapper.writeValueAsString(value)
        val roundtrip = objectMapper.readValue(json, KotlinMapMetadataCommand::class.java)

        assertEquals(value, roundtrip)
        assertObjectWire(json)
        assertFalse(json.contains("optional"))
    }

    @Test
    fun `Java string keyed maps roundtrip as JSON objects including empty maps and omitted null map`() {
        val value = JavaMapMetadataCommand(
            emptyMap(),
            mapOf("values" to listOf(3, 4)),
            mapOf("flags" to mapOf("ready" to true)),
            null
        )

        val json = objectMapper.writeValueAsString(value)
        val roundtrip = objectMapper.readValue(json, JavaMapMetadataCommand::class.java)

        assertEquals(value, roundtrip)
        assertTrue(json.contains("\"strings\":{}"))
        assertObjectWire(json)
        assertFalse(json.contains("optional"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["__proto__", "prototype", "constructor"])
    fun `Kotlin maps reject reserved keys during serialization and deserialization`(key: String) {
        val value = KotlinMapMetadataCommand(strings = mapOf(key to "unsafe"))
        val json = """{"strings":{"$key":"unsafe"},"numbers":{},"nested":{}}"""

        assertThrows(JsonMappingException::class.java) { objectMapper.writeValueAsString(value) }
        assertThrows(JsonMappingException::class.java) {
            objectMapper.readValue(json, KotlinMapMetadataCommand::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["__proto__", "prototype", "constructor"])
    fun `Java maps reject reserved keys during serialization and deserialization`(key: String) {
        val value = JavaMapMetadataCommand(mapOf(key to "unsafe"), emptyMap(), emptyMap(), null)
        val json = """{"strings":{"$key":"unsafe"},"numbers":{},"nested":{}}"""

        assertThrows(JsonMappingException::class.java) { objectMapper.writeValueAsString(value) }
        assertThrows(JsonMappingException::class.java) {
            objectMapper.readValue(json, JavaMapMetadataCommand::class.java)
        }
    }

    @Test
    fun `Kotlin and Java nested maps reject reserved keys recursively`() {
        val kotlinValue = KotlinMapMetadataCommand(nested = mapOf("safe" to mapOf("constructor" to true)))
        val javaValue = JavaMapMetadataCommand(
            emptyMap(),
            emptyMap(),
            mapOf("safe" to mapOf("prototype" to true)),
            null
        )

        assertThrows(JsonMappingException::class.java) { objectMapper.writeValueAsString(kotlinValue) }
        assertThrows(JsonMappingException::class.java) { objectMapper.writeValueAsString(javaValue) }
        assertThrows(JsonMappingException::class.java) {
            objectMapper.readValue(
                """{"strings":{},"numbers":{},"nested":{"safe":{"__proto__":true}}}""",
                KotlinMapMetadataCommand::class.java
            )
        }
        assertThrows(JsonMappingException::class.java) {
            objectMapper.readValue(
                """{"strings":{},"numbers":{},"nested":{"safe":{"constructor":true}}}""",
                JavaMapMetadataCommand::class.java
            )
        }
    }

    @Test
    fun `Kotlin and Java maps accept normal near miss keys`() {
        val nearMisses = linkedMapOf(
            "__proto" to "one",
            "Prototype" to "two",
            "constructor_" to "three"
        )
        val kotlinValue = KotlinMapMetadataCommand(strings = nearMisses)
        val javaValue = JavaMapMetadataCommand(nearMisses, emptyMap(), emptyMap(), null)

        assertEquals(
            kotlinValue,
            objectMapper.readValue(objectMapper.writeValueAsString(kotlinValue), KotlinMapMetadataCommand::class.java)
        )
        assertEquals(
            javaValue,
            objectMapper.readValue(objectMapper.writeValueAsString(javaValue), JavaMapMetadataCommand::class.java)
        )
    }

    private fun assertObjectWire(json: String) {
        val tree = objectMapper.readTree(json)
        assertTrue(tree.path("strings").isObject)
        assertTrue(tree.path("numbers").isObject)
        assertTrue(tree.path("numbers").path("values").isArray)
        assertTrue(tree.path("nested").isObject)
        assertTrue(tree.path("nested").path("flags").isObject)
        assertFalse(json.contains("_entries"))
        assertFalse(json.contains("\"entries\""))
        assertFalse(tree.path("strings").isArray)
    }
}
