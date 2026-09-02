// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.tenancy.ClaimTenantIdResolver
import io.cratis.arc.tenancy.CompositeTenantIdResolver
import io.cratis.arc.tenancy.DevelopmentTenantIdResolver
import io.cratis.arc.tenancy.FixedTenantIdResolver
import io.cratis.arc.tenancy.HeaderTenantIdResolver
import io.cratis.arc.tenancy.QueryStringTenantIdResolver
import io.cratis.arc.tenancy.SubdomainTenantIdResolver
import io.cratis.arc.tenancy.TenancyOptions
import io.cratis.arc.tenancy.TenantId
import io.cratis.arc.tenancy.TenantIdResolver
import io.cratis.arc.tenancy.TenantResolutionContext
import jakarta.servlet.http.HttpServletRequest
import java.util.LinkedHashMap

/** Decides whether a resolved tenant may be used by the captured caller. */
public fun interface TenantAccessEvaluator {
    /** Returns true when [tenantId] may be used for [context]. */
    public fun isAllowed(tenantId: TenantId, context: TenantResolutionContext): Boolean
}

internal class ArcTenantResolutionService(
    private val resolver: TenantIdResolver,
    private val accessEvaluator: TenantAccessEvaluator,
    private val properties: ArcProperties
) {
    fun resolve(request: HttpServletRequest, principal: ArcPrincipal): ResolvedTenant {
        val context = TenantResolutionContext(
            captureHeaders(request),
            captureFirstQueryValues(request),
            request.getHeader("Host") ?: request.serverName,
            principal.claims,
            principal
        )
        val tenant = resolver.resolve(context)
        if (tenant == null) {
            if (properties.tenancy.isRequired) throw TenantResolutionRequiredException()
            return ResolvedTenant(null, context)
        }
        if (!accessEvaluator.isAllowed(tenant, context)) throw TenantAccessDeniedException()
        return ResolvedTenant(tenant.value(), context)
    }

    private fun captureHeaders(request: HttpServletRequest): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        request.headerNames?.toList().orEmpty().forEach { name ->
            request.getHeader(name)?.let { value -> headers.putIfAbsent(name, value) }
        }
        return headers
    }

    private fun captureFirstQueryValues(request: HttpServletRequest): Map<String, String> =
        LinkedHashMap<String, String>().also { values ->
            request.parameterMap.forEach { (name, candidates) ->
                candidates.firstOrNull()?.let { value -> values.putIfAbsent(name, value) }
            }
        }
}

internal data class ResolvedTenant(val value: String?, val context: TenantResolutionContext)
internal class TenantResolutionRequiredException : IllegalArgumentException("A tenant is required.")
internal class TenantAccessDeniedException : SecurityException("Tenant access was denied.")

internal fun configuredTenantIdResolver(properties: ArcProperties): TenantIdResolver {
    val tenancy = properties.tenancy
    val options = tenancy.toOptions(properties.tenantHeader)
    val resolvers = tenancy.resolvers.map { strategy -> resolverFor(strategy, options) }
    return if (resolvers.size == 1) resolvers.single() else CompositeTenantIdResolver(resolvers)
}

internal fun defaultTenantAccessEvaluator(properties: ArcProperties): TenantAccessEvaluator {
    val tenancy = properties.tenancy
    tenancy.validate(properties.tenantHeader)
    if (!tenancy.isConstrainToAuthenticatedClaims) return TenantAccessEvaluator { _, _ -> true }
    val claimType = tenancy.claimType
    val options = tenancy.toOptions(properties.tenantHeader)
    val strategyResolvers = tenancy.resolvers.map { strategy -> strategy to resolverFor(strategy, options) }
    return TenantAccessEvaluator { tenantId, context ->
        val principal = context.principal
        val selectedStrategy = strategyResolvers.firstOrNull { (_, resolver) ->
            resolver.resolve(context)?.value()?.isNotBlank() == true
        }?.first
        if (principal?.isAuthenticated != true ||
            selectedStrategy == TenantResolverStrategy.FIXED ||
            selectedStrategy == TenantResolverStrategy.DEVELOPMENT) {
            true
        } else {
            val memberships = (context.claims + principal.claims)
                .asSequence()
                .filter { claim -> claim.type == claimType }
                .map { claim -> claim.value }
                .filter(String::isNotBlank)
                .toSet()
            memberships.isEmpty() || tenantId.value() in memberships
        }
    }
}

private fun resolverFor(strategy: TenantResolverStrategy, options: TenancyOptions): TenantIdResolver = when (strategy) {
    TenantResolverStrategy.FIXED -> FixedTenantIdResolver(options)
    TenantResolverStrategy.HEADER -> HeaderTenantIdResolver(options)
    TenantResolverStrategy.QUERY -> QueryStringTenantIdResolver(options)
    TenantResolverStrategy.CLAIM -> ClaimTenantIdResolver(options)
    TenantResolverStrategy.SUBDOMAIN -> SubdomainTenantIdResolver(options)
    TenantResolverStrategy.DEVELOPMENT -> DevelopmentTenantIdResolver(options)
}
