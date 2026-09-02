// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TypeShapeDescriptorTest {
    @Test
    fun `factories create immutable nested structural shapes`() {
        val stringKey = TypeShapeDescriptor.value("kotlin.String")
        val nullableValue = TypeShapeDescriptor.value("tests.Result", nullable = true)
        val nested = TypeShapeDescriptor.map(
            stringKey,
            TypeShapeDescriptor.sequence(SequenceKind.LIST, nullableValue, nullable = true),
            MapKeyCodec.STRING
        )
        val equal = TypeShapeDescriptor.map(
            TypeShapeDescriptor.value("kotlin.String"),
            TypeShapeDescriptor.sequence(
                SequenceKind.LIST,
                TypeShapeDescriptor.value("tests.Result", nullable = true),
                nullable = true
            )
        )

        assertEquals(TypeShapeKind.MAP, nested.kind)
        assertEquals(stringKey, nested.keyShape)
        assertEquals(MapKeyCodec.STRING, nested.keyCodec)
        assertEquals(equal, nested)
        assertEquals(equal.hashCode(), nested.hashCode())
        assertTrue(nested.toString().contains("valueShape=TypeShapeDescriptor"))
        assertNotEquals(nested, TypeShapeDescriptor.value("tests.Result"))
        assertEquals(listOf(MapKeyCodec.STRING), MapKeyCodec.entries)
    }

    @Test
    fun `constructor validates kind-specific invariants`() {
        assertThrows(IllegalArgumentException::class.java) {
            TypeShapeDescriptor(TypeShapeKind.VALUE, false, null, null, null, null, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypeShapeDescriptor(
                TypeShapeKind.VALUE,
                false,
                "tests.Value",
                SequenceKind.LIST,
                TypeShapeDescriptor.value("tests.Element"),
                null,
                null,
                null
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypeShapeDescriptor(TypeShapeKind.SEQUENCE, false, null, null, null, null, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypeShapeDescriptor(
                TypeShapeKind.MAP,
                false,
                null,
                null,
                null,
                TypeShapeDescriptor.value("kotlin.String", nullable = true),
                TypeShapeDescriptor.value("tests.Value"),
                MapKeyCodec.STRING
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypeShapeDescriptor.map(
                TypeShapeDescriptor.sequence(SequenceKind.LIST, TypeShapeDescriptor.value("kotlin.String")),
                TypeShapeDescriptor.value("tests.Value")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PropertyDescriptor("values", "kotlin.collections.List", isEnumerable = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParameterDescriptor("value", "tests.Value", elementTypeName = "tests.Element")
        }
    }

    @Test
    fun `legacy and canonical descriptor constructors project one consistent shape`() {
        val legacyProperty = PropertyDescriptor(
            "items",
            "kotlin.collections.List",
            isNullable = true,
            isEnumerable = true,
            elementTypeName = "tests.Item"
        )
        val propertyShape = TypeShapeDescriptor.sequence(
            SequenceKind.LIST,
            TypeShapeDescriptor.sequence(
                SequenceKind.ARRAY,
                TypeShapeDescriptor.value("tests.Item", nullable = true)
            ),
            nullable = true
        )
        val property = PropertyDescriptor("items", propertyShape)
        val parameter = ParameterDescriptor("lookup", propertyShape, isFromServices = true)
        val response = CommandResponseValueDescriptor(propertyShape, CommandResponseValueDisposition.CLIENT)
        val query = QueryDescriptor("all", "tests.Queries", propertyShape)

        assertEquals(TypeShapeKind.SEQUENCE, legacyProperty.shape.kind)
        assertEquals(SequenceKind.LIST, legacyProperty.shape.sequenceKind)
        assertEquals("tests.Item", legacyProperty.shape.elementShape?.typeName)
        assertEquals(propertyShape, property.shape)
        assertEquals("kotlin.collections.List<kotlin.Array<tests.Item>>", property.typeName)
        assertEquals(true, property.isNullable)
        assertEquals(true, property.isEnumerable)
        assertEquals("kotlin.Array<tests.Item>", property.elementTypeName)
        assertEquals(propertyShape, parameter.shape)
        assertEquals(true, parameter.isFromServices)
        assertEquals(propertyShape, response.shape)
        assertEquals(propertyShape, response.copy().shape)
        assertEquals(response, response.copy())
        assertEquals("tests.Item", response.typeName)
        assertEquals(true, response.isEnumerable)
        assertEquals(propertyShape, query.returnShape)
        assertEquals("tests.Item", query.returnTypeName)
        assertEquals(true, query.isEnumerable)
    }
}
