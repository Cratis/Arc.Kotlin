// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

internal class ArcPrincipalFactoryTests {
    @Test
    fun `security authorities without a string representation are ignored`() {
        val request = MockHttpServletRequest()
        request.userPrincipal = UsernamePasswordAuthenticationToken.authenticated(
            "alice",
            "credentials",
            listOf(GrantedAuthority { null }, SimpleGrantedAuthority("ROLE_admin"))
        )

        val principal = SpringSecurityArcPrincipalFactory().create(request, emptyList())

        assertEquals("alice", principal.name)
        assertEquals(setOf("admin"), principal.roles)
    }
}
