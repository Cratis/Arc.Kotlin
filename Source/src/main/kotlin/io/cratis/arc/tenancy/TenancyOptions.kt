// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

/** Immutable host-neutral tenant resolution options. */
public data class TenancyOptions @JvmOverloads constructor(
    /** Header read by [HeaderTenantIdResolver] and used as the subdomain fallback. */
    public val headerName: String = DEFAULT_HEADER_NAME,
    /** Query parameter read by [QueryStringTenantIdResolver]. */
    public val queryParameterName: String = DEFAULT_QUERY_PARAMETER_NAME,
    /** Claim type read by [ClaimTenantIdResolver]. */
    public val claimType: String = DEFAULT_CLAIM_TYPE,
    /** Base domain matched by [SubdomainTenantIdResolver]. */
    public val baseDomain: String = "",
    /** Identifier returned by fixed and development resolvers. */
    public val fixedTenantId: TenantId = TenantId.DEVELOPMENT
) {
    public companion object {
        /** Default tenant header. */
        public const val DEFAULT_HEADER_NAME: String = "x-cratis-tenant-id"

        /** Default tenant query parameter. */
        public const val DEFAULT_QUERY_PARAMETER_NAME: String = "tenantId"

        /** Default tenant claim type. */
        public const val DEFAULT_CLAIM_TYPE: String = "tenant_id"
    }
}
