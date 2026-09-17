// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.observablecollection

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.stereotype.Component

/** Holds the integer-keyed observable collection. */
@Component
public class ObservableCollectionSource {
    private val items: MutableStateFlow<List<ObservableCollectionItem>> = MutableStateFlow(
        listOf(ObservableCollectionItem(1, "One"), ObservableCollectionItem(2, "Two"))
    )

    /** Observes the collection. */
    public fun observe(): Flow<List<ObservableCollectionItem>> = items

    /** Appends an item. */
    public fun add(id: Int, label: String): Unit = synchronized(this) {
        items.value = items.value + ObservableCollectionItem(id, label)
    }

    /** Removes the matching item. */
    public fun remove(id: Int): Unit = synchronized(this) {
        items.value = items.value.filterNot { item -> item.id == id }
    }
}

/**
 * An item in the integer-keyed observable collection.
 *
 * The identifier type decides how Arc tracks set identity across emissions. This feature and its
 * UUID twin exist so the difference between a numeric key and a `Guid` key is visible rather than
 * assumed.
 *
 * @property id The item identifier.
 * @property label The item label.
 */
@ReadModel
@AllowAnonymous
public data class ObservableCollectionItem(public val id: Int, public val label: String) {
    public companion object {
        /** Observes the current collection, pushing updates when items are added or removed. */
        @JvmStatic
        public fun all(@FromServices source: ObservableCollectionSource): Flow<List<ObservableCollectionItem>> =
            source.observe()
    }
}

/**
 * Adds a new item to the observable collection.
 *
 * @property id The new item identifier.
 * @property label The new item label.
 */
@Command
@AllowAnonymous
public data class AddObservableCollectionItem(public val id: Int, public val label: String) {
    /** Handles the command by appending a new item to the collection. */
    public fun handle(source: ObservableCollectionSource) {
        source.add(id, label)
    }
}

/**
 * Removes an item from the observable collection.
 *
 * @property id The identifier of the item to remove.
 */
@Command
@AllowAnonymous
public data class RemoveObservableCollectionItem(public val id: Int) {
    /** Handles the command by removing the matching item from the collection. */
    public fun handle(source: ObservableCollectionSource) {
        source.remove(id)
    }
}
