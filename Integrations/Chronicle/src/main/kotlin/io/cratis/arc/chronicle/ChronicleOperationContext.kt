// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.ServiceResolver
import io.cratis.chronicle.auditing.Causation
import io.cratis.chronicle.auditing.CausationType
import io.cratis.chronicle.eventSequences.AppendOptions
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope
import java.time.Instant

private const val COMMAND_CAUSATION_TYPE = "Command"
private const val COMMAND_TYPE_PROPERTY = "commandType"
private const val COMMAND_TYPE_FULL_NAME_PROPERTY = "commandTypeFullName"
private const val COMMAND_KEY_PROPERTY = "commandKey"

internal data class ChronicleEventCausationLineage(val causation: List<Causation>)

internal class ChronicleEventCausationServiceResolver(
    private val delegate: ServiceResolver,
    causation: List<Causation>
) : ServiceResolver {
    private val lineage = ChronicleEventCausationLineage(causation.toList())

    override fun <T : Any> resolve(type: Class<T>): T? = when (type) {
        ChronicleEventCausationLineage::class.java -> type.cast(lineage)
        else -> delegate.resolve(type)
    }
}

/** Creates Chronicle 4 append options from Arc's explicitly carried command metadata. */
internal fun CommandContext.toChronicleAppendOptions(
    concurrencyScope: ConcurrencyScope? = null
): AppendOptions = AppendOptions(
    correlationId = correlationId,
    concurrencyScope = concurrencyScope,
    causation = chronicleCausation()
)

/** Adds explicit Arc causation to a Chronicle 4 cross-source append shape. */
internal fun CommandContext.withChronicleCausation(
    events: List<EventForEventSourceId>
): List<EventForEventSourceId> {
    val causation = chronicleCausation()
    return events.map { event -> event.copy(causation = event.causation + causation) }
}

private fun CommandContext.chronicleCausation(): List<Causation> {
    val properties = linkedMapOf(
        COMMAND_TYPE_PROPERTY to commandType.simpleName,
        COMMAND_TYPE_FULL_NAME_PROPERTY to commandType.name
    )
    commandKey?.let { key ->
        properties[COMMAND_KEY_PROPERTY] = key.toChronicleKey() ?: key.toString()
    }
    val inherited = serviceResolver.resolve(ChronicleEventCausationLineage::class.java)?.causation.orEmpty()
    return inherited + Causation(Instant.now(), CausationType(COMMAND_CAUSATION_TYPE), properties)
}
