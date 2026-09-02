// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.identity.IdentityClaim
import jakarta.servlet.http.HttpServletRequest
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import org.springframework.security.core.Authentication

/** Captures a request identity once, before command work leaves the servlet entry thread. */
public fun interface ArcPrincipalFactory {
    /** Creates an immutable Arc principal and resolves the identity information available at request entry. */
    public fun create(request: HttpServletRequest, requiredRoles: Collection<String>): ArcPrincipal
}

internal class ServletArcPrincipalFactory : ArcPrincipalFactory {
    override fun create(request: HttpServletRequest, requiredRoles: Collection<String>): ArcPrincipal {
        authenticatedArcPrincipal(request)?.let { return it.withRequiredServletRoles(request, requiredRoles) }
        val principal = request.userPrincipal
        val name = principal?.name
        val claims = claimsFrom(principal)
        return ArcPrincipal(
            name,
            principal != null,
            requiredRoles.filter(request::isUserInRole).toCollection(linkedSetOf()),
            identityId(claims, name),
            claims
        )
    }
}

internal class SpringSecurityArcPrincipalFactory : ArcPrincipalFactory {
    override fun create(request: HttpServletRequest, requiredRoles: Collection<String>): ArcPrincipal {
        authenticatedArcPrincipal(request)?.let { return it.withRequiredServletRoles(request, requiredRoles) }
        val principal = request.userPrincipal
        val authentication = principal as? Authentication
        val name = principal?.name
        val claims = claimsFrom(authentication?.principal ?: principal)
        val roles = linkedSetOf<String>()
        authentication?.authorities.orEmpty().forEach { authority ->
            roles.add(authority.authority.removePrefix(SPRING_ROLE_PREFIX))
        }
        requiredRoles.filter(request::isUserInRole).forEach(roles::add)
        return ArcPrincipal(
            name,
            authentication?.isAuthenticated ?: (principal != null),
            roles,
            identityId(claims, name),
            claims,
            authenticationScheme(authentication)
        )
    }
}

private fun authenticatedArcPrincipal(request: HttpServletRequest): ArcPrincipal? =
    request.getAttribute(ArcAuthenticationAttributes.PRINCIPAL) as? ArcPrincipal

private fun ArcPrincipal.withRequiredServletRoles(
    request: HttpServletRequest,
    requiredRoles: Collection<String>
): ArcPrincipal {
    val capturedRoles = LinkedHashSet(roles)
    requiredRoles.filter(request::isUserInRole).forEach(capturedRoles::add)
    return if (capturedRoles == roles) this else ArcPrincipal(
        name,
        isAuthenticated,
        capturedRoles,
        id,
        claims,
        authenticationScheme
    )
}

private fun authenticationScheme(authentication: Authentication?): String? {
    if (authentication == null) return null
    explicitAuthenticationScheme(authentication.details)?.let { return it }
    val simpleName = authentication.javaClass.simpleName
    return simpleName
        .removeSuffix("AuthenticationToken")
        .removeSuffix("Authentication")
        .removeSuffix("Token")
        .takeIf(String::isNotBlank)
}

private fun explicitAuthenticationScheme(details: Any?): String? {
    if (details == null) return null
    if (details is String) return details.trim().takeIf(String::isNotEmpty)
    if (details is Map<*, *>) {
        return details.entries.firstNotNullOfOrNull { (key, value) ->
            if (AUTHENTICATION_SCHEME_KEYS.any { it.equals(key?.toString(), ignoreCase = true) }) {
                value?.toString()?.trim()?.takeIf(String::isNotEmpty)
            } else {
                null
            }
        }
    }
    val methods = details.javaClass.methods.associateByTo(LinkedHashMap()) { method -> method.name }
    return AUTHENTICATION_SCHEME_ACCESSORS.firstNotNullOfOrNull { accessor ->
        val method = methods[accessor]?.takeIf { it.parameterCount == 0 } ?: return@firstNotNullOfOrNull null
        runCatching { method.invoke(details)?.toString()?.trim()?.takeIf(String::isNotEmpty) }.getOrNull()
    }
}

private fun identityId(claims: List<IdentityClaim>, principalName: String?): String =
    claims.firstOrNull { claim -> claim.type == SUBJECT_CLAIM }?.value
        ?.takeIf(String::isNotBlank)
        ?: principalName?.takeIf(String::isNotBlank)
        ?: UNKNOWN_IDENTITY

private fun claimsFrom(source: Any?): List<IdentityClaim> {
    if (source == null) return emptyList()
    val values = when (source) {
        is Map<*, *> -> source
        else -> extractClaimMap(source)
    }
    return values?.entries.orEmpty().flatMap { (type, value) ->
        val claimType = type?.toString() ?: return@flatMap emptyList()
        when (value) {
            is Iterable<*> -> value.mapNotNull { item -> item?.toString()?.let { IdentityClaim(claimType, it) } }
            is Array<*> -> value.mapNotNull { item -> item?.toString()?.let { IdentityClaim(claimType, it) } }
            null -> emptyList()
            else -> listOf(IdentityClaim(claimType, value.toString()))
        }
    }
}

private fun extractClaimMap(source: Any): Map<*, *>? {
    val methods = source.javaClass.methods.associateByTo(LinkedHashMap()) { method -> method.name }
    return CLAIM_ACCESSORS.firstNotNullOfOrNull { accessor ->
        val method = methods[accessor]?.takeIf { it.parameterCount == 0 } ?: return@firstNotNullOfOrNull null
        runCatching { method.invoke(source) as? Map<*, *> }.getOrNull()
    }
}

private const val SUBJECT_CLAIM = "sub"
private const val UNKNOWN_IDENTITY = "unknown"
private const val SPRING_ROLE_PREFIX = "ROLE_"
private val CLAIM_ACCESSORS = listOf("getClaims", "getAttributes")
private val AUTHENTICATION_SCHEME_KEYS = listOf("authenticationScheme", "scheme", "mechanism")
private val AUTHENTICATION_SCHEME_ACCESSORS = listOf("getAuthenticationScheme", "getScheme", "getMechanism")
