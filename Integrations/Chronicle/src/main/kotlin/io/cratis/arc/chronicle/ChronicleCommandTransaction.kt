// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandExecutionToken
import io.cratis.arc.results.CommandResult
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/**
 * Owns Chronicle events staged by an Arc command-execution root without ambient or thread-local state.
 *
 * Arc keeps an explicit root unit of work and commits one atomic `appendMany` batch through
 * Chronicle's cross-source event-sequence API.
 */
public class ChronicleCommandTransaction {
    private val transactions = ConcurrentHashMap<CommandExecutionToken, StagedChronicleRootUnitOfWork>()

    /** Creates a root transaction or joins an existing root from a nested execution frame. */
    public fun begin(context: CommandContext) {
        val token = requireNotNull(context.executionToken) {
            "A Chronicle transaction requires a pipeline-owned command execution token."
        }
        val rootToken = token.rootToken
        if (token === rootToken) {
            val transaction = StagedChronicleRootUnitOfWork(context, token)
            check(transactions.putIfAbsent(rootToken, transaction) == null) {
                "A Chronicle transaction is already active for this command execution root."
            }
        } else {
            val transaction = checkNotNull(transactions[rootToken]) {
                "The Chronicle transaction for this nested command execution root is not active."
            }
            transaction.join(context, token)
        }
    }

    internal fun requireActive(context: CommandContext) {
        val token = requireNotNull(context.executionToken) {
            "Transactional Chronicle handling requires a pipeline-owned command execution token."
        }
        val transaction = checkNotNull(transactions[token.rootToken]) {
            "No active Chronicle transaction exists for this command execution root."
        }
        transaction.requireActive(context, token)
    }

    /** Enrolls events in the open transaction owned by [context]'s execution root. */
    public fun enroll(
        context: CommandContext,
        eventStore: IEventStore,
        events: List<EventForEventSourceId>,
        concurrencyScopes: Map<String, ConcurrencyScope> = emptyMap()
    ): Boolean {
        val token = requireNotNull(context.executionToken) {
            "Transactional Chronicle enrollment requires a pipeline-owned command execution token."
        }
        val transaction = checkNotNull(transactions[token.rootToken]) {
            "No active Chronicle transaction exists for this command execution root."
        }
        transaction.enroll(context, token, eventStore, events, concurrencyScopes)
        return true
    }

    /** Completes one frame; only the root frame may commit the shared batch. */
    public suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
        val token = requireNotNull(context.executionToken) {
            "A Chronicle transaction requires a pipeline-owned command execution token."
        }
        val rootToken = token.rootToken
        val transaction = checkNotNull(transactions[rootToken]) {
            "No active Chronicle transaction exists for this command execution root."
        }
        if (token !== rootToken) {
            transaction.completeChild(context, token, result)
            return null
        }

        val completion = transaction.completeRoot(context, token, result)
        check(transactions.remove(rootToken, transaction)) {
            "The Chronicle transaction for this command execution root was completed concurrently."
        }
        return when (completion) {
            RootCompletion.Empty,
            RootCompletion.RolledBack -> null
            RootCompletion.RollbackOnly -> CommandResult.error(
                context.correlationId,
                "A nested command execution failed; the Chronicle transaction root is rollback-only."
            )
            is RootCompletion.Commit -> {
                val results = completion.eventStore.eventLog.appendMany(
                    completion.events,
                    completion.concurrencyScopes,
                    completion.correlationId
                )
                appendResultsToCommandResult(context, results, completion.events.size)
            }
        }
    }
}

/**
 * Commits Chronicle after local command scopes have completed successfully.
 *
 * This ordering is a commit barrier that prevents an append after a local failure; it is not a distributed transaction.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 300)
public class ChronicleCommandExecutionScope(
    private val transactions: ChronicleCommandTransaction
) : CommandExecutionScope {
    override fun begin(context: CommandContext) {
        transactions.begin(context)
    }

    override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? =
        transactions.complete(context, result)
}

private class StagedChronicleRootUnitOfWork(
    rootContext: CommandContext,
    rootToken: CommandExecutionToken
) {
    private val correlationId = rootContext.correlationId
    private val tenantNamespace = rootContext.tenantNamespace
    private val rootToken = rootToken
    private val activeTokens = Collections.newSetFromMap(IdentityHashMap<CommandExecutionToken, Boolean>()).apply {
        add(rootToken)
    }
    private var lifecycle = Lifecycle.OPEN
    private var rollbackOnly = false
    private var eventStore: IEventStore? = null
    private var eventStoreNamespace: String? = null
    private val events = mutableListOf<EventForEventSourceId>()
    private val concurrencyScopes = linkedMapOf<String, ConcurrencyScope>()

    @Synchronized
    fun join(context: CommandContext, token: CommandExecutionToken) {
        validateContext(context, token)
        check(lifecycle == Lifecycle.OPEN) {
            "The Chronicle transaction root is no longer open for nested command execution."
        }
        check(activeTokens.add(token)) {
            "This nested command execution has already joined the Chronicle transaction root."
        }
    }

    @Synchronized
    fun requireActive(context: CommandContext, token: CommandExecutionToken) {
        validateContext(context, token)
        check(lifecycle == Lifecycle.OPEN) { "The Chronicle transaction root is closed." }
        check(activeTokens.contains(token)) {
            "The command execution frame is not active in the Chronicle transaction root."
        }
    }

    @Synchronized
    fun enroll(
        context: CommandContext,
        token: CommandExecutionToken,
        eventStore: IEventStore,
        events: List<EventForEventSourceId>,
        concurrencyScopes: Map<String, ConcurrencyScope>
    ) {
        validateContext(context, token)
        check(lifecycle == Lifecycle.OPEN) {
            "The Chronicle transaction root is closed to event enrollment."
        }
        check(activeTokens.contains(token)) {
            "The command execution frame is not active in the Chronicle transaction root."
        }

        val namespace = eventStore.namespace
        val establishedStore = this.eventStore
        check(establishedStore == null || establishedStore === eventStore) {
            "One Arc command execution root cannot enroll events in more than one Chronicle event store."
        }
        val establishedNamespace = eventStoreNamespace
        check(establishedNamespace == null || establishedNamespace == namespace) {
            "One Arc command execution root cannot enroll events in more than one Chronicle namespace."
        }
        check(tenantNamespace == null || namespace == tenantNamespace) {
            "The Chronicle event-store namespace does not match the command execution root tenant namespace."
        }

        concurrencyScopes.forEach { (eventSourceId, scope) ->
            val existing = this.concurrencyScopes[eventSourceId]
            check(existing == null || existing == scope) {
                "Conflicting concurrency scopes were supplied for event source '$eventSourceId'."
            }
        }

        this.eventStore = eventStore
        eventStoreNamespace = namespace
        this.events.addAll(context.withChronicleCausation(events))
        concurrencyScopes.forEach { (eventSourceId, scope) ->
            this.concurrencyScopes[eventSourceId] = scope
        }
    }

    @Synchronized
    fun completeChild(context: CommandContext, token: CommandExecutionToken, result: CommandResult<*>) {
        validateContext(context, token)
        check(lifecycle == Lifecycle.OPEN) {
            "The Chronicle transaction root was closed before its nested command completed."
        }
        check(activeTokens.remove(token)) {
            "This nested command execution is not active in the Chronicle transaction root."
        }
        if (!result.isSuccess) rollbackOnly = true
    }

    @Synchronized
    fun completeRoot(
        context: CommandContext,
        token: CommandExecutionToken,
        result: CommandResult<*>
    ): RootCompletion {
        validateContext(context, token)
        check(lifecycle == Lifecycle.OPEN) { "The Chronicle transaction root has already completed." }
        check(activeTokens.remove(token)) { "The Chronicle transaction root execution is not active." }
        if (activeTokens.isNotEmpty()) rollbackOnly = true
        lifecycle = Lifecycle.CLOSED

        if (!result.isSuccess) {
            clear()
            return RootCompletion.RolledBack
        }
        if (rollbackOnly) {
            clear()
            return RootCompletion.RollbackOnly
        }
        val store = eventStore
        if (store == null || events.isEmpty()) {
            clear()
            return RootCompletion.Empty
        }
        return RootCompletion.Commit(
            store,
            events.toList(),
            correlationId,
            concurrencyScopes.toMap()
        ).also { clear() }
    }

    private fun validateContext(context: CommandContext, token: CommandExecutionToken) {
        check(token.rootToken === rootToken) {
            "The command execution frame does not belong to this Chronicle transaction root."
        }
        require(context.correlationId == correlationId) {
            "A nested Chronicle transaction must use the root correlation identifier."
        }
        require(context.tenantNamespace == tenantNamespace) {
            "A nested Chronicle transaction must use the root tenant namespace."
        }
    }

    private fun clear() {
        activeTokens.clear()
        events.clear()
        concurrencyScopes.clear()
        eventStore = null
        eventStoreNamespace = null
    }

    private enum class Lifecycle {
        OPEN,
        CLOSED
    }
}

private sealed interface RootCompletion {
    data object Empty : RootCompletion
    data object RolledBack : RootCompletion
    data object RollbackOnly : RootCompletion
    data class Commit(
        val eventStore: IEventStore,
        val events: List<EventForEventSourceId>,
        val correlationId: java.util.UUID,
        val concurrencyScopes: Map<String, ConcurrencyScope>
    ) : RootCompletion
}
