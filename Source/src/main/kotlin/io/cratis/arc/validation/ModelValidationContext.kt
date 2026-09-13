// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.queries.QueryContext
import java.util.UUID

/** Immutable view of one operation and the current graph member. Never retain it beyond validation. */
public class ModelValidationContext private constructor(
    /** Command operation, or null when validating query arguments. */
    public val commandContext: CommandContext?,
    /** Query operation, or null when validating a command. */
    public val queryContext: QueryContext?,
    /** Path of this node; empty for the command root. */
    public val memberPath: String
) {
    /** Creates a command-scoped node context. */
    public constructor(context: CommandContext, memberPath: String) : this(context, null, memberPath)

    /** Creates a query-scoped node context. */
    public constructor(context: QueryContext, memberPath: String) : this(null, context, memberPath)

    /** Correlation identifier of the enclosing operation. */
    public val correlationId: UUID get() = commandContext?.correlationId ?: queryContext!!.correlationId
    /** Explicit principal of the enclosing operation. */
    public val principal: ArcPrincipal get() = commandContext?.principal ?: queryContext!!.principal
    /** Explicit tenant identifier, including deliberate absence. */
    public val tenantId: String? get() = if (commandContext != null) commandContext.tenantId else queryContext!!.tenantId
    /** Explicit tenant namespace, including deliberate absence. */
    public val tenantNamespace: String? get() = if (commandContext != null) commandContext.tenantNamespace else queryContext!!.tenantNamespace
    /** Operation-scoped resolver; its services are not graph validation inputs. */
    public val serviceResolver: ServiceResolver get() = commandContext?.serviceResolver ?: queryContext!!.serviceResolver
}
