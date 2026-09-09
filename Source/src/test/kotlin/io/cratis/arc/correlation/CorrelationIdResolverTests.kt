// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.correlation

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class CorrelationIdResolverTests {
    @Test
    fun `parses a correlation identifier and ignores surrounding whitespace`() {
        val identifier = UUID.randomUUID()

        assertEquals(identifier, CorrelationIdResolver.parse(identifier.toString()))
        assertEquals(identifier, CorrelationIdResolver.parse("\t $identifier \n"))
    }

    @Test
    fun `rejects everything that is not a correlation identifier`() {
        assertNull(CorrelationIdResolver.parse(null))
        assertNull(CorrelationIdResolver.parse(""))
        assertNull(CorrelationIdResolver.parse("   "))
        assertNull(CorrelationIdResolver.parse("not-a-correlation-id"))
        assertNull(CorrelationIdResolver.parse("<script>alert(1)</script>"))
    }

    @Test
    fun `creates a distinct identifier when a value represents none`() {
        val first = CorrelationIdResolver.resolveOrCreate("not-a-correlation-id")
        val second = CorrelationIdResolver.resolveOrCreate(null)

        assertNotEquals(first, second)
    }

    @Test
    fun `keeps the identifier a value represents`() {
        val identifier = UUID.randomUUID()

        assertEquals(identifier, CorrelationIdResolver.resolveOrCreate(identifier.toString()))
    }
}
