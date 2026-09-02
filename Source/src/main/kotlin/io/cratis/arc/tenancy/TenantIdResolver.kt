// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

/** Resolves a tenant from explicit host-neutral request context. */
public fun interface TenantIdResolver {
    /** Returns the resolved tenant, or `null` when this resolver has no answer. */
    public fun resolve(context: TenantResolutionContext): TenantId?
}

/**
 * Tries resolvers in declaration order and returns the first nonblank result.
 *
 * Declaration order is the complete precedence rule; no resolver or context is kept in ambient state.
 */
public class CompositeTenantIdResolver(resolvers: List<TenantIdResolver>) : TenantIdResolver {
    /** Immutable resolver chain in precedence order. */
    public val resolvers: List<TenantIdResolver> = java.util.List.copyOf(resolvers)

    override fun resolve(context: TenantResolutionContext): TenantId? = resolvers.asSequence()
        .mapNotNull { it.resolve(context) }
        .firstOrNull { it.value().isNotBlank() }
}
