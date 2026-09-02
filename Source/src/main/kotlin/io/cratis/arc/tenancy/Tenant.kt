// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

/** Immutable tenant exposed to development tooling. */
public data class Tenant(
    /** Stable tenant identifier. */
    public val id: TenantId,
    /** Display name. */
    public val name: TenantName
)
