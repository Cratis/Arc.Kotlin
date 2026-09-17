// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TenantCoroutineContextTest {

    // ─── TenantCoroutineContext + top-level suspend helpers ──────────────────

    @Test
    fun `currentTenant returns null when no TenantCoroutineContext is in the coroutine context`(): Unit = runBlocking {
        assertNull(currentTenant())
    }

    @Test
    fun `currentTenantOrNotSet returns NOT_SET when no TenantCoroutineContext is in the coroutine context`(): Unit = runBlocking {
        assertEquals(TenantId.NOT_SET, currentTenantOrNotSet())
    }

    @Test
    fun `withTenant installs the tenant and it is visible via currentTenant`(): Unit = runBlocking {
        withTenant(TenantId("acme")) {
            assertEquals(TenantId("acme"), currentTenant())
        }
    }

    @Test
    fun `withTenant restores the previous context after the block exits`(): Unit = runBlocking {
        withTenant(TenantId("outer")) {
            assertEquals(TenantId("outer"), currentTenant())
        }
        // The outer runBlocking scope has no TenantCoroutineContext; it must be null again.
        assertNull(currentTenant())
    }

    @Test
    fun `nested withTenant shadows the outer tenant inside the block and restores it on exit`(): Unit = runBlocking {
        withTenant(TenantId("outer")) {
            withTenant(TenantId("inner")) {
                assertEquals(TenantId("inner"), currentTenant())
            }
            // Restored to outer after inner block exits.
            assertEquals(TenantId("outer"), currentTenant())
        }
    }

    @Test
    fun `tenant propagates into child coroutines launched inside withTenant`(): Unit = runBlocking {
        withTenant(TenantId("parent")) {
            val child = async {
                currentTenant()
            }
            assertEquals(TenantId("parent"), child.await())
        }
    }

    @Test
    fun `tenant survives a switch to Dispatchers Default`(): Unit = runBlocking {
        withTenant(TenantId("dispatcher-tenant")) {
            val result = withContext(Dispatchers.Default) {
                currentTenant()
            }
            assertEquals(TenantId("dispatcher-tenant"), result)
        }
    }

    @Test
    fun `tenant is not visible in a coroutine launched outside the withTenant scope`(): Unit = runBlocking {
        // A coroutine launched from the surrounding runBlocking scope (no TenantCoroutineContext)
        // must not see the tenant set by a sibling withTenant block.
        var outsideTenant: TenantId? = TenantId("sentinel") // sentinel: must be overwritten
        val sibling = async {
            // This coroutine is launched from the enclosing runBlocking scope which has no tenant.
            outsideTenant = currentTenant()
        }
        withTenant(TenantId("isolated")) {
            assertEquals(TenantId("isolated"), currentTenant())
        }
        sibling.join()
        assertNull(outsideTenant)
    }

    @Test
    fun `currentTenantOrNotSet returns the installed tenant when present`(): Unit = runBlocking {
        withTenant(TenantId("known")) {
            assertEquals(TenantId("known"), currentTenantOrNotSet())
        }
    }

    // ─── TenantContextBridge ─────────────────────────────────────────────────

    @Test
    fun `TenantContextBridge currentTenant returns null when outside a withTenant scope`() {
        assertNull(TenantContextBridge.currentTenant())
    }

    @Test
    fun `TenantContextBridge withTenant Callable sets the tenant during the scope`() {
        val result = TenantContextBridge.withTenant(
            TenantId("bridge-callable"),
            Callable { TenantContextBridge.currentTenant() }
        )
        assertEquals(TenantId("bridge-callable"), result)
    }

    @Test
    fun `TenantContextBridge withTenant Runnable sets the tenant during the scope`() {
        var observed: TenantId? = null
        TenantContextBridge.withTenant(TenantId("bridge-runnable"), Runnable {
            observed = TenantContextBridge.currentTenant()
        })
        assertEquals(TenantId("bridge-runnable"), observed)
    }

    @Test
    fun `TenantContextBridge currentTenant returns null after the withTenant scope exits`() {
        TenantContextBridge.withTenant(TenantId("ephemeral"), Runnable { /* no-op */ })
        assertNull(TenantContextBridge.currentTenant())
    }

    @Test
    fun `TenantContextBridge withTenant Callable returns the value produced by the block`() {
        val result = TenantContextBridge.withTenant(TenantId("t"), Callable { "returned" })
        assertEquals("returned", result)
    }
}
