// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.authorization.AuthorizationEvaluator
import io.cratis.arc.results.CommandResult

/** Built-in host-neutral command authorization filter. */
public class CommandAuthorizationFilter(
    private val handlers: CommandHandlerRegistry,
    private val evaluator: AuthorizationEvaluator
) : AuthorizationCommandFilter {
    override suspend fun execute(context: CommandContext): CommandResult<*> {
        val handler = handlers.find(context.commandType)
            ?: return CommandResult.missingHandler(context.correlationId, context.commandType.name)
        val result = evaluator.evaluate(handler.metadata.authorization, context.principal)
        return if (result.isAuthorized) {
            CommandResult.success(context.correlationId)
        } else {
            CommandResult.unauthorized(context.correlationId, result.failureReason)
        }
    }
}
