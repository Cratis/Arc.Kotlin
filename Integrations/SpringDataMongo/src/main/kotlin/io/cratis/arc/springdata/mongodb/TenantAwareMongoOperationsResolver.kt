// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

/**
 * Resolves a certified binding to an isolated MongoDB store from an explicit tenant identifier.
 *
 * Implementations must throw when the tenant is unknown. The returned certificate must name the exact requested
 * tenant; Arc validates it before exposing the operations to command read-model resolution or observation.
 */
public fun interface TenantAwareMongoOperationsResolver {
    /** Resolves [tenantId] to its certified isolated MongoDB operations. */
    public fun resolve(tenantId: String): TenantMongoOperations
}

internal class TenantAwareMongoOperationsAdapter(
    internal val resolver: TenantAwareMongoOperationsResolver
) : MongoOperationsResolver {
    override fun resolve(tenantId: String?): org.springframework.data.mongodb.core.MongoOperations {
        require(!tenantId.isNullOrBlank()) { "A nonblank tenant identifier is required." }
        val binding = requireNotNull(resolver.resolve(tenantId)) {
            "Tenant-aware MongoOperationsResolver returned no binding for tenant '$tenantId'."
        }
        check(binding.tenantId == tenantId) {
            "Tenant-aware MongoOperationsResolver returned binding '${binding.tenantId}' for requested tenant " +
                "'$tenantId'."
        }
        return binding.operations
    }
}
