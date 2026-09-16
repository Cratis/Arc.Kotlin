// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

internal class ArcPlatformAuthenticationFilterTests {
    @AfterEach
    fun clear() { SecurityContextHolder.clearContext() }

    @Test
    fun `filter restores context on downstream exception and runs once on nested invocation`() {
        var calls = 0
        val converter = ArcPlatformAuthenticationConverter { calls++; true }
        val filter = ArcPlatformAuthenticationFilter(converter)
        val previous = SecurityContextHolder.getContext()
        assertThrows(IllegalStateException::class.java) {
            filter.doFilter(platformRequest(), MockHttpServletResponse(), FilterChain { request, response ->
                filter.doFilter(request, response, FilterChain { _, _ ->
                    assertEquals("Ada", SecurityContextHolder.getContext().authentication?.name)
                    throw IllegalStateException("downstream")
                })
            })
        }
        assertEquals(1, calls)
        assertSame(previous, SecurityContextHolder.getContext())
        assertNull(previous.authentication)
    }

    @Test
    fun `anonymous and unauthenticated tokens can be replaced but authenticated ones cannot`() {
        val converter = ArcPlatformAuthenticationConverter { true }
        val filter = ArcPlatformAuthenticationFilter(converter)
        val tokens = listOf(
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS"))),
            UsernamePasswordAuthenticationToken.unauthenticated("pending", ""),
            UsernamePasswordAuthenticationToken.authenticated("strong", "", emptyList())
        )
        for (token in tokens) {
            SecurityContextHolder.getContext().authentication = token
            filter.doFilter(platformRequest(), MockHttpServletResponse(), FilterChain { _, _ ->
                assertEquals(if (token.name == "strong") "strong" else "Ada", SecurityContextHolder.getContext().authentication?.name)
            })
            assertSame(token, SecurityContextHolder.getContext().authentication)
        }
    }
}
