// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.results.ValidationResultSeverity
import java.util.UUID

/** Explicit host-provided values used to build a command context. */
public class CommandExecutionOptions @JvmOverloads constructor(
    /** Correlation identifier for the execution. */
    public val correlationId: UUID,
    /** Identity on whose behalf the command runs. */
    public val principal: ArcPrincipal,
    /** Resolver scoped to this command execution. */
    public val serviceResolver: ServiceResolver,
    /** Host tenant identifier, when the host is tenant-aware. */
    public val tenantId: String? = null,
    /** Host tenant namespace, when the host distinguishes it from the tenant identifier. */
    public val tenantNamespace: String? = null,
    /** Maximum validation severity allowed without rejecting execution. */
    public val allowedValidationSeverity: ValidationResultSeverity? = null,
    /** Whether the host may expose exception details after logging. Core always retains full details. */
    public val exposeExceptionDetails: Boolean = false
) {
    internal var parentExecutionToken: CommandExecutionToken? = null
        private set

    internal fun join(token: CommandExecutionToken): CommandExecutionOptions = apply {
        parentExecutionToken = token
    }

    public companion object {
        /**
         * Creates options for a nested execution that joins the live execution represented by [parentContext].
         *
         * The parent's host metadata is preserved. The pipeline creates a distinct child execution token.
         */
        @JvmStatic
        public fun nested(parentContext: CommandContext): CommandExecutionOptions {
            val token = requireNotNull(parentContext.executionToken) {
                "The parent command context does not belong to a pipeline execution."
            }
            return CommandExecutionOptions(
                correlationId = parentContext.correlationId,
                principal = parentContext.principal,
                serviceResolver = parentContext.serviceResolver,
                tenantId = parentContext.tenantId,
                tenantNamespace = parentContext.tenantNamespace,
                allowedValidationSeverity = parentContext.allowedValidationSeverity,
                exposeExceptionDetails = parentContext.exposeExceptionDetails
            ).join(token)
        }
    }
}
