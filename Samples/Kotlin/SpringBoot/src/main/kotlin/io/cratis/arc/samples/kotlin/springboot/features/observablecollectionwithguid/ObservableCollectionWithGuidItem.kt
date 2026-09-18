// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.observablecollectionwithguid

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.stereotype.Component

/** Holds the UUID-keyed observable collection. */
@Component
public class ObservableCollectionWithGuidSource {
    private val items: MutableStateFlow<List<ObservableCollectionWithGuidItem>> = MutableStateFlow(
        listOf(
            ObservableCollectionWithGuidItem(UUID.fromString("11111111-1111-1111-1111-111111111111"), "One"),
            ObservableCollectionWithGuidItem(UUID.fromString("22222222-2222-2222-2222-222222222222"), "Two")
        )
    )

    /** Observes the collection. */
    public fun observe(): Flow<List<ObservableCollectionWithGuidItem>> = items

    /** Appends an item. */
    public fun add(id: UUID, label: String): Unit = synchronized(this) {
        items.value = items.value + ObservableCollectionWithGuidItem(id, label)
    }

    /** Removes the matching item. */
    public fun remove(id: UUID): Unit = synchronized(this) {
        items.value = items.value.filterNot { item -> item.id == id }
    }
}

/**
 * An item in the UUID-keyed observable collection.
 *
 * A JVM [UUID] reaches the browser as a `Guid` from `@cratis/fundamentals`, not as a bare string.
 * Adding and removing items here is what proves the generated client still matches rows by
 * identity after that conversion.
 *
 * @property id The item identifier.
 * @property label The item label.
 */
@ReadModel
@AllowAnonymous
public data class ObservableCollectionWithGuidItem(public val id: UUID, public val label: String) {
    public companion object {
        /** Observes the current collection, pushing updates when items are added or removed. */
        @JvmStatic
        public fun all(
            @FromServices source: ObservableCollectionWithGuidSource
        ): Flow<List<ObservableCollectionWithGuidItem>> = source.observe()
    }
}

/**
 * Adds a new item to the UUID-keyed observable collection.
 *
 * @property id The new item identifier.
 * @property label The new item label.
 */
@Command
@AllowAnonymous
public data class AddObservableCollectionWithGuidItem(public val id: UUID, public val label: String) {
    /** Handles the command by appending a new item to the collection. */
    public fun handle(source: ObservableCollectionWithGuidSource) {
        source.add(id, label)
    }
}

/**
 * Removes an item from the UUID-keyed observable collection.
 *
 * @property id The identifier of the item to remove.
 */
@Command
@AllowAnonymous
public data class RemoveObservableCollectionWithGuidItem(public val id: UUID) {
    /** Handles the command by removing the matching item from the collection. */
    public fun handle(source: ObservableCollectionWithGuidSource) {
        source.remove(id)
    }
}
