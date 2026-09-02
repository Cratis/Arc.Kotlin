// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.identity.IdentityClaim
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TenantResolutionTest {
    @Test
    fun `tenant concepts expose stable default semantics`() {
        assertTrue(TenantId.NOT_SET.isDefault)
        assertTrue(TenantId.DEFAULT.isDefault)
        assertFalse(TenantId.DEVELOPMENT.isDefault)
        assertEquals("tenant-one", TenantId("tenant-one").value())
        assertEquals("Tenant One", TenantName("Tenant One").value())
    }

    @Test
    fun `context snapshots values and applies explicit lookup semantics`() {
        val headers = linkedMapOf("X-Tenant" to "first", "x-tenant" to "second")
        val query = linkedMapOf("tenantId" to "query")
        val claims = mutableListOf(IdentityClaim("tenant_id", "explicit"))
        val principal = ArcPrincipal(
            name = "Ada",
            isAuthenticated = true,
            claims = listOf(IdentityClaim("tenant_id", "principal"))
        )
        val context = TenantResolutionContext(headers, query, "host", claims, principal)
        headers.clear()
        query.clear()
        claims.clear()

        assertEquals("first", context.header("x-TENANT"))
        assertEquals("query", context.queryParameter("tenantId"))
        assertNull(context.queryParameter("TENANTID"))
        assertEquals("explicit", context.claim("tenant_id"))
    }

    @Test
    fun `fixed and development resolvers return configured tenant without request state`() {
        val options = TenancyOptions(fixedTenantId = TenantId("configured"))
        val context = TenantResolutionContext()

        assertEquals(TenantId("configured"), FixedTenantIdResolver(options).resolve(context))
        assertEquals(TenantId("configured"), DevelopmentTenantIdResolver(options).resolve(context))
    }

    @Test
    fun `header resolver uses case insensitive custom name and treats blank or missing values as absent`() {
        val resolver = HeaderTenantIdResolver(TenancyOptions(headerName = "X-Custom-Tenant"))

        assertEquals(
            TenantId("header-tenant"),
            resolver.resolve(TenantResolutionContext(headers = mapOf("x-custom-tenant" to "header-tenant")))
        )
        assertNull(resolver.resolve(TenantResolutionContext()))
        assertNull(resolver.resolve(TenantResolutionContext(headers = mapOf("X-Custom-Tenant" to " "))))
    }

    @Test
    fun `query resolver uses custom exact parameter name`() {
        val resolver = QueryStringTenantIdResolver(TenancyOptions(queryParameterName = "tenant"))

        assertEquals(TenantId("query-tenant"), resolver.resolve(TenantResolutionContext(query = mapOf("tenant" to "query-tenant"))))
        assertNull(resolver.resolve(TenantResolutionContext(query = mapOf("Tenant" to "wrong-case"))))
    }

    @Test
    fun `claim resolver prefers explicit claims then principal claims`() {
        val resolver = ClaimTenantIdResolver(TenancyOptions(claimType = "organization"))
        val principal = ArcPrincipal(
            "Ada",
            true,
            claims = listOf(IdentityClaim("organization", "principal-tenant"))
        )

        assertEquals(
            TenantId("explicit-tenant"),
            resolver.resolve(
                TenantResolutionContext(
                    claims = listOf(IdentityClaim("organization", "explicit-tenant")),
                    principal = principal
                )
            )
        )
        assertEquals(TenantId("principal-tenant"), resolver.resolve(TenantResolutionContext(principal = principal)))
        assertNull(resolver.resolve(TenantResolutionContext()))
    }

    @Test
    fun `composite declaration order is precedence and resolution stops after first answer`() {
        val calls = mutableListOf<String>()
        val resolver = CompositeTenantIdResolver(
            listOf(
                TenantIdResolver { calls.add("missing"); null },
                TenantIdResolver { calls.add("blank"); TenantId(" ") },
                TenantIdResolver { calls.add("winner"); TenantId("resolved") },
                TenantIdResolver { calls.add("late"); TenantId("late") }
            )
        )

        assertEquals(TenantId("resolved"), resolver.resolve(TenantResolutionContext()))
        assertEquals(listOf("missing", "blank", "winner"), calls)
        assertNull(CompositeTenantIdResolver(emptyList()).resolve(TenantResolutionContext()))
    }

    @Test
    fun `subdomain resolver normalizes host and returns exactly one label`() {
        val resolver = SubdomainTenantIdResolver(TenancyOptions(baseDomain = "MyApp.COM"))

        assertEquals(TenantId("acme"), resolver.resolve(TenantResolutionContext(host = "ACME.myapp.com:8080")))
        assertEquals(TenantId("acme"), resolver.resolve(TenantResolutionContext(host = "acme.myapp.com.")))
        assertEquals(TenantId("xn--bcher-kva"), resolver.resolve(TenantResolutionContext(host = "bücher.myapp.com")))
    }

    @Test
    fun `subdomain resolver falls back for hosts that do not identify one valid tenant label`() {
        val resolver = SubdomainTenantIdResolver(
            TenancyOptions(baseDomain = "myapp.com", headerName = "X-Fallback")
        )
        val invalidHosts = listOf(
            "myapp.com",
            "one.two.myapp.com",
            "notmyapp.com",
            "myapp.com.example",
            "bad_label.myapp.com",
            "user@acme.myapp.com",
            "127.0.0.1",
            "[::1]",
            ""
        )

        invalidHosts.forEach { host ->
            assertEquals(
                TenantId("fallback"),
                resolver.resolve(TenantResolutionContext(mapOf("x-fallback" to "fallback"), host = host)),
                host
            )
        }
        assertNull(
            SubdomainTenantIdResolver(TenancyOptions(baseDomain = "myapp.com", headerName = ""))
                .resolve(TenantResolutionContext(host = "myapp.com"))
        )
    }

    @Test
    fun `subdomain resolver rejects unusable base domains immediately`() {
        listOf("", "localhost", "com", "127.0.0.1", "[::1]", "bad_label.com").forEach { baseDomain ->
            assertThrows(InvalidTenantBaseDomainException::class.java) {
                SubdomainTenantIdResolver(TenancyOptions(baseDomain = baseDomain))
            }
        }
    }
}
