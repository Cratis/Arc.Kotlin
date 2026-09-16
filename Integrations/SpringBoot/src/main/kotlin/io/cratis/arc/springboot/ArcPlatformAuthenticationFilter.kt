// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.AuthenticationConverter
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Security-chain-only bridge. Install before AnonymousAuthenticationFilter, after existing mechanisms.
 * Invalid submissions fail even with existing authentication; valid submissions never replace it.
 * No context is saved to the session. Arc captures an immutable principal before coroutine dispatch.
 */
public class ArcPlatformAuthenticationFilter(private val converter: AuthenticationConverter) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val candidate = try {
            converter.convert(request)
        } catch (_: AuthenticationException) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "text/plain"
            response.writer.write("Unauthorized")
            return
        }
        val previous = SecurityContextHolder.getContext()
        val existing = previous.authentication
        val install = candidate != null && (existing == null || !existing.isAuthenticated || existing is AnonymousAuthenticationToken)
        if (install) {
            val context = SecurityContextHolder.createEmptyContext().also { it.authentication = candidate }
            SecurityContextHolder.setContext(context)
            RequestAttributeSecurityContextRepository().saveContext(context, request, response)
        }
        try {
            filterChain.doFilter(request, response)
        } finally {
            if (install) SecurityContextHolder.setContext(previous)
        }
    }
}
