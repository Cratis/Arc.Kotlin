// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.json.JsonMapper
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Characterizes how the host-agnostic Arc mapper treats fields the target type does not declare.
 *
 * `ArcObjectMapper` never touches `FAIL_ON_UNKNOWN_PROPERTIES`, so the standalone mapper keeps Jackson's own
 * strict default while a host that relaxes the feature keeps its own choice. These tests pin that split so a
 * later policy decision is made against recorded behavior rather than an assumption.
 */
class ArcObjectMapperUnknownFieldTest {
    private val mapper = ArcObjectMapper.create()

    @Test
    fun `created mapper leaves Jackson's strict unknown-field default in place`() {
        assertTrue(mapper.deserializationConfig.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
    }

    @Test
    fun `created mapper rejects an unknown field`() {
        val exception = assertThrows(UnrecognizedPropertyException::class.java) {
            mapper.readValue("""{"value":"hello","unexpected":"extra"}""", UnknownFieldPayload::class.java)
        }

        assertEquals("unexpected", exception.propertyName)
    }

    @Test
    fun `created mapper rejects an unknown field nested inside a declared property`() {
        assertThrows(UnrecognizedPropertyException::class.java) {
            mapper.readValue(
                """{"value":"hello","nested":{"inner":"one","unexpected":"extra"}}""",
                NestingPayload::class.java
            )
        }
    }

    @Test
    fun `created mapper rejects an unknown field inside a derived type payload`() {
        val registry = ConcurrentDerivedTypeRegistry()
        registry.register(UnknownFieldBase::class.java, UnknownFieldDerived::class.java)
        val derivedMapper = ArcObjectMapper.create(registry)

        assertThrows(UnrecognizedPropertyException::class.java) {
            derivedMapper.readValue(
                """{"_derivedTypeId":"unknown-field-derived","value":"hello","unexpected":"extra"}""",
                UnknownFieldBase::class.java
            )
        }
    }

    @Test
    fun `configure preserves a relaxed unknown-field choice made by the caller`() {
        val relaxed = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

        val configured: ObjectMapper = ArcObjectMapper.configure(relaxed)

        assertEquals(
            UnknownFieldPayload("hello"),
            configured.readValue("""{"value":"hello","unexpected":"extra"}""", UnknownFieldPayload::class.java)
        )
    }
}

data class UnknownFieldPayload(val value: String)

data class NestingPayload(val value: String, val nested: NestedPayload)

data class NestedPayload(val inner: String)

interface UnknownFieldBase

@DerivedType("unknown-field-derived")
data class UnknownFieldDerived(val value: String) : UnknownFieldBase
