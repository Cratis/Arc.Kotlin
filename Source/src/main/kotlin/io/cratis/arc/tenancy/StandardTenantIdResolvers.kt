// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

/** Resolves every context to one configured tenant. */
public open class FixedTenantIdResolver(
    private val options: TenancyOptions = TenancyOptions()
) : TenantIdResolver {
    override fun resolve(context: TenantResolutionContext): TenantId = options.fixedTenantId
}

/** Compatibility name for fixed tenancy used as a development convenience. */
public class DevelopmentTenantIdResolver(
    options: TenancyOptions = TenancyOptions()
) : FixedTenantIdResolver(options)

/** Resolves a tenant from a case-insensitive HTTP header name. */
public class HeaderTenantIdResolver(
    private val options: TenancyOptions = TenancyOptions()
) : TenantIdResolver {
    override fun resolve(context: TenantResolutionContext): TenantId? =
        context.header(options.headerName).toTenantIdOrNull()
}

/** Resolves a tenant from an exact query-string parameter name. */
public open class QueryStringTenantIdResolver(
    private val options: TenancyOptions = TenancyOptions()
) : TenantIdResolver {
    override fun resolve(context: TenantResolutionContext): TenantId? =
        context.queryParameter(options.queryParameterName).toTenantIdOrNull()
}

/** .NET-compatible short name for [QueryStringTenantIdResolver], also visible to Java callers. */
public class QueryTenantIdResolver(
    options: TenancyOptions = TenancyOptions()
) : QueryStringTenantIdResolver(options)

/** Resolves a tenant from explicit claims, then principal claims. */
public class ClaimTenantIdResolver(
    private val options: TenancyOptions = TenancyOptions()
) : TenantIdResolver {
    override fun resolve(context: TenantResolutionContext): TenantId? =
        context.claim(options.claimType).toTenantIdOrNull()
}

private fun String?.toTenantIdOrNull(): TenantId? = this?.takeIf(String::isNotBlank)?.let(::TenantId)
