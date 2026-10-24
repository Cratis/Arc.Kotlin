// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:Suppress("DEPRECATION")

package io.cratis.arc.concepts

import io.cratis.arc.authentication.AuthenticationFailureReason
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.tenancy.TenantId
import io.cratis.arc.tenancy.TenantName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArcConceptCompatibilityTest {
    @Test
    fun `public Arc concepts retain the legacy value extension and supertype`() {
        val id = TenantId("id")
        val name = TenantName("name")
        val query = FullyQualifiedQueryName("query")
        val reason = AuthenticationFailureReason("reason")
        assertEquals("id", id.value)
        assertEquals("name", name.value)
        assertEquals("query", query.value)
        assertEquals("reason", reason.value)
        val legacy: List<ConceptAs<String>> = listOf(id, name, query, reason)
        assertEquals(listOf("id", "name", "query", "reason"), legacy.map { it.value })
        legacy.forEach { assertTrue(io.cratis.fundamentals.concepts.ConceptAs::class.java.isInstance(it)) }
    }

    @Test
    fun `legacy value method remains declared on its original interface`() {
        assertEquals(Any::class.java, ConceptAs::class.java.getDeclaredMethod("value").returnType)
    }
}
