// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.commands.AuthorizationCommandFilter
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandKeyProvider
import io.cratis.arc.commands.CommandPreparation
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.await
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.results.CommandResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Synchronous Java implementation surface for a command filter. */
public fun interface BlockingCommandFilter {
    public fun execute(context: CommandContext): CommandResult<*>
}

/** CompletionStage-based Java implementation surface for a command filter. */
public fun interface AsyncCommandFilter {
    public fun execute(context: CommandContext): CompletionStage<CommandResult<*>>
}

/** Synchronous Java marker for authorization command filters. */
public fun interface BlockingAuthorizationCommandFilter : BlockingCommandFilter

/** CompletionStage-based Java marker for authorization command filters. */
public fun interface AsyncAuthorizationCommandFilter : AsyncCommandFilter

/** Adapts a blocking command filter to Arc's suspending SPI. */
public class BlockingCommandFilterAdapter(private val filter: BlockingCommandFilter) : CommandFilter {
    override suspend fun execute(context: CommandContext): CommandResult<*> = filter.execute(context)
}

/** Adapts an asynchronous command filter to Arc's suspending SPI. */
public class AsyncCommandFilterAdapter(private val filter: AsyncCommandFilter) : CommandFilter {
    override suspend fun execute(context: CommandContext): CommandResult<*> = filter.execute(context).await()
}

/** Adapts a blocking authorization command filter while retaining marker ordering. */
public class BlockingAuthorizationCommandFilterAdapter(
    private val filter: BlockingAuthorizationCommandFilter
) : AuthorizationCommandFilter {
    override suspend fun execute(context: CommandContext): CommandResult<*> = filter.execute(context)
}

/** Adapts an asynchronous authorization command filter while retaining marker ordering. */
public class AsyncAuthorizationCommandFilterAdapter(
    private val filter: AsyncAuthorizationCommandFilter
) : AuthorizationCommandFilter {
    override suspend fun execute(context: CommandContext): CommandResult<*> = filter.execute(context).await()
}

/** Synchronous Java implementation surface for a command execution scope. */
public interface BlockingCommandExecutionScope {
    public fun begin(context: CommandContext)
    public fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>?
}

/** CompletionStage Java surface for scope completion; begin stays synchronous to match the primary SPI. */
public interface AsyncCommandExecutionScope {
    public fun begin(context: CommandContext)
    public fun complete(context: CommandContext, result: CommandResult<*>): CompletionStage<CommandResult<*>?>
}

/** Adapts a blocking command execution scope to Arc's suspending SPI. */
public class BlockingCommandExecutionScopeAdapter(
    private val scope: BlockingCommandExecutionScope
) : CommandExecutionScope {
    override fun begin(context: CommandContext): Unit = scope.begin(context)
    override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? =
        scope.complete(context, result)
}

/** Adapts asynchronous command scope completion to Arc's suspending SPI. */
public class AsyncCommandExecutionScopeAdapter(private val scope: AsyncCommandExecutionScope) : CommandExecutionScope {
    override fun begin(context: CommandContext): Unit = scope.begin(context)
    override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? =
        scope.complete(context, result).await()
}

/** Synchronous Java implementation surface for command response value handling. */
public interface BlockingCommandResponseValueHandler {
    public fun canHandle(context: CommandContext, value: Any): Boolean
    public fun handle(context: CommandContext, value: Any): CommandResult<*>
}

/** CompletionStage-based Java implementation surface for command response value handling. */
public interface AsyncCommandResponseValueHandler {
    public fun canHandle(context: CommandContext, value: Any): Boolean
    public fun handle(context: CommandContext, value: Any): CompletionStage<CommandResult<*>>
}

/** Adapts a blocking command response value handler to Arc's suspending SPI. */
public class BlockingCommandResponseValueHandlerAdapter(
    private val handler: BlockingCommandResponseValueHandler
) : CommandResponseValueHandler {
    override fun canHandle(context: CommandContext, value: Any): Boolean = handler.canHandle(context, value)
    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> = handler.handle(context, value)
}

/** Adapts an asynchronous command response value handler to Arc's suspending SPI. */
public class AsyncCommandResponseValueHandlerAdapter(
    private val handler: AsyncCommandResponseValueHandler
) : CommandResponseValueHandler {
    override fun canHandle(context: CommandContext, value: Any): Boolean = handler.canHandle(context, value)
    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> = handler.handle(context, value).await()
}

/** Synchronous Java implementation surface for a manually registered command handler. */
public interface BlockingCommandHandler {
    public val commandType: Class<*>
    public val metadata: CommandDescriptor

    public fun resolveCommandKey(command: Any): Any? = (command as? CommandKeyProvider)?.commandKey()
    public fun prepare(context: CommandContext): CommandPreparation = CommandPreparation.empty(context.correlationId)
    public fun invoke(context: CommandContext): Any?
}

/** CompletionStage-based Java implementation surface for a manually registered command handler. */
public interface AsyncCommandHandler {
    public val commandType: Class<*>
    public val metadata: CommandDescriptor

    public fun resolveCommandKey(command: Any): Any? = (command as? CommandKeyProvider)?.commandKey()
    public fun prepare(context: CommandContext): CompletionStage<CommandPreparation> =
        CompletableFuture.completedFuture(CommandPreparation.empty(context.correlationId))
    public fun invoke(context: CommandContext): CompletionStage<*>
}

/** Adapts a blocking manual command handler to Arc's suspending SPI. */
public class BlockingCommandHandlerAdapter(private val handler: BlockingCommandHandler) : CommandHandler {
    override val commandType: Class<*> get() = handler.commandType
    override val metadata: CommandDescriptor get() = handler.metadata
    override fun resolveCommandKey(command: Any): Any? = handler.resolveCommandKey(command)
    override suspend fun prepare(context: CommandContext): CommandPreparation = handler.prepare(context)
    override suspend fun invoke(context: CommandContext): Any? = handler.invoke(context)
}

/** Adapts an asynchronous manual command handler to Arc's suspending SPI. */
public class AsyncCommandHandlerAdapter(private val handler: AsyncCommandHandler) : CommandHandler {
    override val commandType: Class<*> get() = handler.commandType
    override val metadata: CommandDescriptor get() = handler.metadata
    override fun resolveCommandKey(command: Any): Any? = handler.resolveCommandKey(command)
    override suspend fun prepare(context: CommandContext): CommandPreparation = handler.prepare(context).await()
    override suspend fun invoke(context: CommandContext): Any? = handler.invoke(context).await()
}
