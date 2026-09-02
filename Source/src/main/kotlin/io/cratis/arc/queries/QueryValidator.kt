// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.results.ValidationResult

/** Host-neutral request validator for one query name, or every query when the name is null. */
public interface QueryValidator {
    /** Query matched by this validator; null matches every query. */
    public val queryName: FullyQualifiedQueryName?

    /** Validates [request] and returns feedback in declaration order. */
    public suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult>
}
