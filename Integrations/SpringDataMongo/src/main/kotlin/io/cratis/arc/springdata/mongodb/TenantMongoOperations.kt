// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import org.springframework.data.mongodb.core.MongoOperations

/**
 * Immutable certificate binding one exact tenant identifier to its isolated MongoDB operations.
 *
 * The identifier is preserved exactly as supplied; it is validated but never normalized.
 */
public class TenantMongoOperations(tenantId: String?, operations: MongoOperations?) {
    /** Exact nonblank tenant identifier certified by the resolver. */
    public val tenantId: String = requireNotNull(tenantId) { "A tenant identifier is required." }.also {
        require(it.isNotBlank()) { "A nonblank tenant identifier is required." }
    }

    /** MongoDB operations isolated for [tenantId]. */
    public val operations: MongoOperations = requireNotNull(operations) {
        "MongoOperations are required for tenant '$tenantId'."
    }
}
