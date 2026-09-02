// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe query performer registry with exact-name lookup. */
public class ConcurrentQueryPerformerRegistry : QueryPerformerRegistry {
    private val performers = ConcurrentHashMap<FullyQualifiedQueryName, QueryPerformer>()
    private val registryVersion = AtomicLong()

    override val version: Long
        get() = registryVersion.get()

    override fun register(performer: QueryPerformer) {
        val existing = performers.putIfAbsent(performer.fullyQualifiedName, performer)
        if (existing != null) throw DuplicateQueryPerformerException(performer.fullyQualifiedName)
        registryVersion.incrementAndGet()
    }

    override fun find(queryName: FullyQualifiedQueryName): QueryPerformer? = performers[queryName]

    override fun snapshot(): List<QueryPerformer> =
        java.util.List.copyOf(performers.values.sortedBy { it.fullyQualifiedName.value })
}
