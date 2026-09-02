// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.chronicle.EventsWithConcurrencyScopes
import io.cratis.arc.commands.ArcOneOf
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.HandlesCommandResponseValues
import io.cratis.arc.results.CommandResult
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import java.util.UUID

/** Client-visible response used by aggregate response metadata contracts. */
public data class AggregateClientResponse(public val value: String)

/** Contract implemented by values consumed by a declarative custom response handler. */
public interface HandledResponseContract

/** Custom response value covered by [AggregateResponseHandler]'s declarative contract. */
public data class HandledResponse(public val value: String) : HandledResponseContract

/** Declares and consumes scalar custom response values for compile-time aggregate classification. */
@HandlesCommandResponseValues(HandledResponseContract::class)
public class AggregateResponseHandler : CommandResponseValueHandler {
    override fun canHandle(context: CommandContext, value: Any): Boolean = value is HandledResponseContract

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
        CommandResult.success(context.correlationId)
}

/** Kotlin aggregate command with one client response and one Chronicle event. */
@Command
public class KotlinPairResponseCommand {
    public fun handle(): Pair<AggregateClientResponse, MetadataEvent> =
        Pair(AggregateClientResponse("client"), MetadataEvent("event"))
}

/** Kotlin command covering nested aggregate, custom scalar, and built-in event classification. */
@Command
public class KotlinNestedResponseCommand {
    public fun handle(): ArcOneOf<Triple<HandledResponse, Pair<List<AggregateClientResponse>, MetadataEvent>, HandledResponse>> =
        ArcOneOf.of(
            Triple(
                HandledResponse("first"),
                Pair(listOf(AggregateClientResponse("client")), MetadataEvent("event")),
                HandledResponse("second")
            )
        )
}

/** Kotlin aggregate whose scalar values are all consumed by the custom response value handler. */
@Command
public class KotlinHandledOnlyResponseCommand {
    public fun handle(): Pair<HandledResponse, HandledResponse> =
        Pair(HandledResponse("first"), HandledResponse("second"))
}

/** Kotlin command result whose nested aggregate is processed recursively by the runtime. */
@Command
public class KotlinCommandResultResponseCommand {
    public fun handle(): CommandResult<Pair<AggregateClientResponse, MetadataEvent>> = CommandResult.success(
        UUID.randomUUID(),
        Pair(AggregateClientResponse("client"), MetadataEvent("event"))
    )
}

/** Selects the routed-list response used by generated Chronicle pipeline contracts. */
public enum class RoutedEventListResponseMode {
    Valid,
    Malformed,
    Empty
}

/** Kotlin routed wrapper collection whose generated metadata is consumed by the Chronicle response handler. */
@Command
public data class KotlinRoutedEventListResponseCommand(public val mode: RoutedEventListResponseMode) {
    public fun handle(): List<EventForEventSourceId> = when (mode) {
        RoutedEventListResponseMode.Valid -> listOf(
            EventForEventSourceId("kotlin-list", MetadataEvent("kotlin-list"))
        )
        RoutedEventListResponseMode.Malformed -> listOf(
            EventForEventSourceId("kotlin-malformed", "not-a-chronicle-event")
        )
        RoutedEventListResponseMode.Empty -> emptyList()
    }
}

/** Known Chronicle routed-event wrapper consumed by the optional Chronicle runtime integration. */
@Command
public class RoutedEventResponseCommand {
    public fun handle(): EventForEventSourceId = EventForEventSourceId("source", MetadataEvent("event"))
}

/** Known Chronicle concurrency wrapper consumed despite dependency annotations not being discoverable by KSP. */
@Command
public class ChronicleScopedResponseCommand {
    public fun handle(): EventsWithConcurrencyScopes = EventsWithConcurrencyScopes.builder()
        .event("source", MetadataEvent("event"))
        .build()
}
