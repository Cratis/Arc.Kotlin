// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.results.CommandResult
import java.util.concurrent.ConcurrentHashMap
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.DefaultTransactionDefinition

/**
 * Opt-in imperative MongoDB command transaction scope.
 *
 * Spring binds this transaction to the opening thread. It is not coroutine-safe: command code must not suspend or
 * execute persistence work on another thread. Auto-configuration leaves this scope disabled unless explicitly enabled.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class MongoCommandExecutionScope(
    private val transactionManager: MongoTransactionManager
) : CommandExecutionScope {
    private val transactions = ConcurrentHashMap<ExecutionKey, TransactionState>()

    override fun begin(context: CommandContext) {
        val definition = DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED).apply {
            name = "Arc command ${context.commandType.name}"
        }
        val state = TransactionState(transactionManager.getTransaction(definition), Thread.currentThread().id)
        val previous = transactions.putIfAbsent(ExecutionKey(context), state)
        if (previous != null) {
            transactionManager.rollback(state.status)
            error("A MongoDB transaction is already active for Arc command ${context.commandType.name}.")
        }
    }

    override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
        val state = transactions.remove(ExecutionKey(context)) ?: return null
        if (state.threadId != Thread.currentThread().id) {
            val failure = IllegalStateException(
                "Arc command ${context.commandType.name} changed threads while a thread-bound MongoDB transaction was active."
            )
            try {
                transactionManager.rollback(state.status)
            } catch (rollbackFailure: RuntimeException) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }

        if (result.isSuccess) {
            transactionManager.commit(state.status)
        } else {
            transactionManager.rollback(state.status)
        }
        return null
    }

    private class TransactionState(val status: TransactionStatus, val threadId: Long)

    private class ExecutionKey(context: CommandContext) {
        private val identity: Any = context.executionToken ?: context

        override fun equals(other: Any?): Boolean = other is ExecutionKey && identity === other.identity

        override fun hashCode(): Int = System.identityHashCode(identity)
    }
}
