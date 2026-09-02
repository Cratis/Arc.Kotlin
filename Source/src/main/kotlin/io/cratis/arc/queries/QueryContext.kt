// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.results.ValidationResultSeverity
import java.util.UUID

/** Immutable context carried explicitly through every query execution stage. */
public class QueryContext(
    public val correlationId: UUID,
    public val request: QueryRequest,
    public val queryName: FullyQualifiedQueryName,
    public val principal: ArcPrincipal,
    public val tenantId: String?,
    public val tenantNamespace: String?,
    public val serviceResolver: ServiceResolver,
    public val allowedValidationSeverity: ValidationResultSeverity?,
    public val exposeExceptionDetails: Boolean
) {
    init {
        require(queryName == request.queryName) { "queryName must match request.queryName" }
    }
}
