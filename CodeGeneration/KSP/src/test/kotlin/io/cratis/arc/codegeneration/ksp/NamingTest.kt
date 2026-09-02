// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class NamingTest {
    @Test
    fun `module names accept only deterministic safe identifiers`() {
        assertEquals("Orders", validateModuleName("Orders"))
        assertEquals("orders_2", validateModuleName("orders_2"))
        assertNull(validateModuleName(null))
        assertNull(validateModuleName(""))
        assertNull(validateModuleName("orders-api"))
        assertNull(validateModuleName("2orders"))
        assertNull(validateModuleName("class"))
    }

    @Test
    fun `generated names are stable and include the qualified command identity`() {
        assertEquals("OrdersArcArtifactModule", moduleClassName("Orders"))
        assertEquals(
            "CreateOrderArcCommandHandler_df7a0115b948",
            commandHandlerClassName("io.cratis.orders.CreateOrder")
        )
        assertEquals(
            "CreateOrderArcCommandHandler_48d902fa1bc8",
            commandHandlerClassName("a.b.CreateOrder")
        )
        assertEquals(
            "allArcQueryPerformer_0ae2ae1153a5",
            queryPerformerClassName("io.cratis.orders.Order.all")
        )
    }

    @Test
    fun `class literal types map Java primitives and escape Kotlin keywords`() {
        assertEquals("kotlin.Int", renderClassLiteralType("int"))
        assertEquals("kotlin.Boolean", renderClassLiteralType("boolean"))
        assertEquals("io.cratis.`when`.Dependency", renderClassLiteralType("io.cratis.when.Dependency"))
        assertEquals("java.lang.String", renderClassLiteralType("java.lang.String"))
    }
}
