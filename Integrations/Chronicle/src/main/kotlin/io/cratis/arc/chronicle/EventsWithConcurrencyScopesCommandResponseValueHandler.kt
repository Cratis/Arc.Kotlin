// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.events.EventType
import kotlinx.coroutines.CancellationException

/** Appends [EventsWithConcurrencyScopes] as one atomic Chronicle batch. */
public class EventsWithConcurrencyScopesCommandResponseValueHandler(
    private val eventStoreResolver: TenantEventStoreResolver,
    private val transactions: ChronicleCommandTransaction? = null
) : CommandResponseValueHandler {
    /** Creates a handler backed by one tenant-safe event store. */
    public constructor(eventStore: IEventStore) : this(tenantEventStoreResolver(eventStore))

    /** Creates a transactional handler backed by one tenant-safe event store. */
    public constructor(eventStore: IEventStore, transactions: ChronicleCommandTransaction) :
        this(tenantEventStoreResolver(eventStore), transactions)

    override fun canHandle(context: CommandContext, value: Any): Boolean = value is EventsWithConcurrencyScopes

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
        val response = value as EventsWithConcurrencyScopes
        if (response.events.any { event ->
                !event.eventSourceId.isValidEventSourceId() ||
                    !event.event.javaClass.isAnnotationPresent(EventType::class.java)
            }
        ) {
            return invalidResponse(context)
        }
        val eventStore = resolveEventStore(context) ?: return eventStoreUnavailable(context)
        if (transactions != null) {
            transactions.enroll(context, eventStore, response.events, response.concurrencyScopes)
            return CommandResult.success(context.correlationId)
        }
        val results = eventStore.eventLog.appendMany(
            context.withChronicleCausation(response.events),
            response.concurrencyScopes,
            context.correlationId
        )
        return appendResultsToCommandResult(context, results, response.events.size)
    }

    private fun resolveEventStore(context: CommandContext): IEventStore? = try {
        eventStoreResolver.resolve(context.tenantNamespace)?.takeIf { eventStore ->
            context.tenantNamespace == null || eventStore.namespace == context.tenantNamespace
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    private fun eventStoreUnavailable(context: CommandContext): CommandResult<*> = CommandResult.invalid(
        context.correlationId,
        listOf(
            ValidationResult(
                ValidationResultSeverity.Error,
                "A Chronicle event store is unavailable for the command tenant namespace.",
                members = listOf("tenantNamespace"),
                reason = ValidationResultReasons.DEPENDENCY_UNAVAILABLE,
                reasonDetail = context.tenantNamespace
            )
        )
    )

    private fun invalidResponse(context: CommandContext): CommandResult<*> = CommandResult.invalid(
        context.correlationId,
        listOf(
            ValidationResult(
                ValidationResultSeverity.Error,
                "Every scoped event requires a safe event-source identifier and a Chronicle @EventType value.",
                members = listOf("events"),
                reason = ValidationResultReasons.RULE,
                reasonDetail = "events"
            )
        )
    )

    private fun String.isValidEventSourceId(): Boolean = isNotBlank() && none(Char::isISOControl)
}
