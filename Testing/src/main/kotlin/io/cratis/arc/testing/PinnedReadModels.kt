// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.queries.BlockingReadModelForCommandResolver
import io.cratis.arc.queries.ReadModelForCommandOwnership

/** One command-side read model pinned to a known state, optionally restricted to an exact command key. */
internal class PinnedReadModel(
    val readModelType: Class<*>,
    val hasKey: Boolean,
    val key: Any?,
    val readModel: Any?
) {
    fun matches(normalizedCommandKey: Any?): Boolean = !hasKey || key == normalizedCommandKey
}

/**
 * Resolves pinned command-side read models without a store, a query pipeline, or a storage technology.
 *
 * Pins claim [ReadModelForCommandOwnership.DECLARED] so a pinned state wins over a fallback provider, exactly as a
 * store that owns the type would.
 */
internal class PinnedReadModelsForCommand(pins: List<PinnedReadModel>) : BlockingReadModelForCommandResolver {
    private val pinsByType: Map<Class<*>, List<PinnedReadModel>> = pins.groupBy(PinnedReadModel::readModelType)
    private val types: Set<Class<*>> = java.util.Collections.unmodifiableSet(LinkedHashSet(pinsByType.keys))

    override fun readModelTypes(): Set<Class<*>> = types

    override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED

    override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any? {
        val candidates = pinsByType[readModelType] ?: return null
        val normalizedKey = normalizePinKey(key)
        return candidates.firstOrNull { pin -> pin.matches(normalizedKey) }?.readModel
    }
}

/** Unwraps [ConceptAs] wrappers so a pin written with a scalar matches a concept-typed command key. */
internal tailrec fun normalizePinKey(key: Any?): Any? = when (key) {
    is ConceptAs<*> -> normalizePinKey(key.value())
    else -> key
}
