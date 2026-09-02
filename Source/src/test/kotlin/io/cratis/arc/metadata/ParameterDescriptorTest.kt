// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ParameterDescriptorTest {
    private val shape = TypeShapeDescriptor.value("tests.Value")

    @Test
    fun `canonical shape constructor preserves every explicit source`() {
        QueryParameterSource.entries.forEach { source ->
            val descriptor = ParameterDescriptor("value", shape, source)

            assertEquals(source, descriptor.source)
            assertEquals(source == QueryParameterSource.SERVICE, descriptor.isFromServices)
            assertEquals(shape, descriptor.shape)
        }
    }

    @Test
    fun `legacy constructors project service boolean to canonical source`() {
        val client = ParameterDescriptor("client", "tests.Value")
        val service = ParameterDescriptor("service", shape, isFromServices = true)
        val full = ParameterDescriptor(
            "full",
            "tests.Value",
            false,
            true,
            false,
            null,
            emptyList(),
            false
        )

        assertEquals(QueryParameterSource.CLIENT, client.source)
        assertFalse(client.isFromServices)
        assertEquals(QueryParameterSource.SERVICE, service.source)
        assertTrue(service.isFromServices)
        assertEquals(QueryParameterSource.SERVICE, full.source)
    }

    @Test
    fun `client default metadata is canonical and participates in value semantics`() {
        val required = ParameterDescriptor("value", shape, QueryParameterSource.CLIENT)
        val defaulted = ParameterDescriptor("value", shape, QueryParameterSource.CLIENT, true)
        val equalDefaulted = ParameterDescriptor("value", shape, QueryParameterSource.CLIENT, true)

        assertFalse(required.hasDefault)
        assertTrue(defaulted.hasDefault)
        assertNotEquals(required, defaulted)
        assertEquals(defaulted, equalDefaulted)
        assertEquals(defaulted.hashCode(), equalDefaulted.hashCode())
        assertTrue(defaulted.toString().contains("hasDefault=true"))
    }

    @Test
    fun `only client parameters may declare a default`() {
        QueryParameterSource.entries.filterNot { it == QueryParameterSource.CLIENT }.forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                ParameterDescriptor("value", shape, source, true)
            }
        }
    }

    @Test
    fun `source participates in equality hash code and string representation`() {
        val client = ParameterDescriptor("value", shape, QueryParameterSource.CLIENT)
        val adapter = ParameterDescriptor("value", shape, QueryParameterSource.HOST_ADAPTER)
        val equalAdapter = ParameterDescriptor("value", shape, QueryParameterSource.HOST_ADAPTER)

        assertNotEquals(client, adapter)
        assertEquals(adapter, equalAdapter)
        assertEquals(adapter.hashCode(), equalAdapter.hashCode())
        assertTrue(adapter.toString().contains("source=HOST_ADAPTER"))
    }

    @Test
    fun `validation rules are defensively copied and immutable`() {
        val rule = ValidationRuleDescriptor("required")
        val sourceRules = mutableListOf(rule)
        val descriptor = ParameterDescriptor(
            "value",
            shape,
            QueryParameterSource.QUERY_REQUEST,
            sourceRules
        )

        sourceRules.clear()

        assertEquals(listOf(rule), descriptor.validationRules)
        assertThrows(UnsupportedOperationException::class.java) {
            (descriptor.validationRules as MutableList).add(ValidationRuleDescriptor("other"))
        }
    }
}
