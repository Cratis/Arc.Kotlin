// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class QueryArgumentResolverTest {
    @Test
    fun `required returns the exact named value`() {
        val value = Any()

        assertSame(value, QueryArgumentResolver.required(mapOf("value" to value), "value"))
    }

    @Test
    fun `nullable maps missing and explicit null to null`() {
        assertNull(QueryArgumentResolver.nullable(emptyMap(), "value"))
        assertNull(QueryArgumentResolver.nullable(mapOf("value" to null), "value"))
    }

    @Test
    fun `missing and wrong type failures are deterministic`() {
        val missing = assertThrows(QueryArgumentException::class.java) {
            QueryArgumentResolver.required(emptyMap(), "value")
        }
        val wrong = assertThrows(QueryArgumentException::class.java) {
            QueryArgumentResolver.wrongType("value", "kotlin.String")
        }

        assertEquals("value", missing.argumentName)
        assertEquals("Query argument 'value' is required.", missing.message)
        assertEquals("value", wrong.argumentName)
        assertEquals("Query argument 'value' must be of type 'kotlin.String'.", wrong.message)
    }
}
