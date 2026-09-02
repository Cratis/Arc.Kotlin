// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.results.ValidationResultSeverity
import java.util.UUID

/** Explicit host values used for one query execution. */
public class QueryExecutionOptions @JvmOverloads constructor(
    public val correlationId: UUID,
    public val principal: ArcPrincipal,
    public val serviceResolver: ServiceResolver,
    public val tenantId: String? = null,
    public val tenantNamespace: String? = null,
    public val allowedValidationSeverity: ValidationResultSeverity? = null,
    public val exposeExceptionDetails: Boolean = false
)
