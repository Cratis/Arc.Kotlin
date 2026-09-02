// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.merge
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Opaque identity for one command-execution frame.
 *
 * Tokens are created by the command pipeline and remain stable when a [CommandContext] is copied.
 */
public sealed interface CommandExecutionToken {
    /** Stable identity shared by every execution frame owned by the same root command. */
    public val rootToken: CommandExecutionToken
}

internal class OwnedCommandExecutionToken(
    val owner: CommandExecutionOwner,
    val root: Boolean,
    private val ownedRootToken: CommandExecutionToken? = null
) : CommandExecutionToken {
    override val rootToken: CommandExecutionToken
        get() = ownedRootToken ?: this
}

internal val CommandExecutionToken.executionOwner: CommandExecutionOwner
    get() = (this as OwnedCommandExecutionToken).owner

internal val CommandExecutionToken.isRootExecution: Boolean
    get() = (this as OwnedCommandExecutionToken).root

internal class CommandExecutionOwner(
    private val correlationId: UUID,
    private val tenantNamespace: String?
) {
    private val monitor = Any()
    private val liveTokens = Collections.newSetFromMap(IdentityHashMap<CommandExecutionToken, Boolean>())
    private var rootToken: CommandExecutionToken? = null
    private var rootClosed = false
    private var rollbackOnly = false

    fun createRoot(): CommandExecutionToken = synchronized(monitor) {
        check(liveTokens.isEmpty()) { "The command execution root has already been created." }
        OwnedCommandExecutionToken(this, true).also { token ->
            rootToken = token
            liveTokens.add(token)
        }
    }

    fun createChild(
        parent: CommandExecutionToken,
        childCorrelationId: UUID,
        childTenantNamespace: String?
    ): CommandExecutionToken = synchronized(monitor) {
        check(!rootClosed && liveTokens.contains(parent)) {
            "The supplied command execution token is closed."
        }
        require(childCorrelationId == correlationId) {
            "A nested command execution must use the root correlation identifier."
        }
        require(childTenantNamespace == tenantNamespace) {
            "A nested command execution must use the root tenant namespace."
        }
        OwnedCommandExecutionToken(this, false, requireNotNull(rootToken)).also(liveTokens::add)
    }

    /** Seals the root before any execution scope can commit. */
    fun sealRoot(token: CommandExecutionToken) {
        synchronized(monitor) {
            check(token.isRootExecution && token === rootToken) {
                "Only the command execution root can seal its owner."
            }
            check(!rootClosed) { "The command execution root is already closed." }
            rootClosed = true
            if (liveTokens.any { live -> live !== token }) rollbackOnly = true
        }
    }

    fun close(token: CommandExecutionToken) {
        synchronized(monitor) {
            liveTokens.remove(token)
            if (token.isRootExecution) rootClosed = true
        }
    }

    fun markRollbackOnly(token: CommandExecutionToken) {
        if (token.isRootExecution) return
        synchronized(monitor) {
            rollbackOnly = true
        }
    }

    fun applyRollbackOnly(result: CommandResult<*>): CommandResult<*> = synchronized(monitor) {
        if (!rollbackOnly || !result.isSuccess) {
            result
        } else {
            result.mergeNestedFailure(correlationId)
        }
    }
}

private fun CommandResult<*>.mergeNestedFailure(correlationId: UUID): CommandResult<*> =
    merge(
        CommandResult.error(
            correlationId,
            "A nested command execution failed; the root execution is rollback-only."
        )
    )

internal class CommandExecutionContext(
    val token: CommandExecutionToken
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CommandExecutionContext>
}
