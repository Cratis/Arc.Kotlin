// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.changestream

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.stereotype.Component

/** Holds the mutable showcase collection behind the change-stream feature. */
@Component
public class ChangeStreamSource {
    private val items: MutableStateFlow<List<ChangeStreamItem>> = MutableStateFlow(
        listOf(
            ChangeStreamItem(1, "Alpha", 10),
            ChangeStreamItem(2, "Beta", 20),
            ChangeStreamItem(3, "Gamma", 30)
        )
    )

    /** Observes the collection. */
    public fun observe(): Flow<List<ChangeStreamItem>> = items

    /** Appends an item. */
    public fun add(id: Int, label: String, value: Int): Unit = synchronized(this) {
        items.value = items.value + ChangeStreamItem(id, label, value)
    }

    /** Replaces the matching item. */
    public fun update(id: Int, label: String, value: Int): Unit = synchronized(this) {
        items.value = items.value.map { item -> if (item.id == id) ChangeStreamItem(id, label, value) else item }
    }

    /** Removes the matching item. */
    public fun remove(id: Int): Unit = synchronized(this) {
        items.value = items.value.filterNot { item -> item.id == id }
    }
}

/**
 * An item in the change-stream showcase collection.
 *
 * Add, update and remove an item and watch what arrives on the wire. Arc does not resend the whole
 * list for a one-item edit — it computes a change set against the previous emission, so the client
 * receives the delta and reconstructs the collection locally.
 *
 * @property id The unique identifier of the item.
 * @property label A descriptive label for the item.
 * @property value A numeric value associated with the item.
 */
@ReadModel
@AllowAnonymous
public data class ChangeStreamItem(public val id: Int, public val label: String, public val value: Int) {
    public companion object {
        /** Observes the full collection, pushing every change to subscribers. */
        @JvmStatic
        public fun all(@FromServices source: ChangeStreamSource): Flow<List<ChangeStreamItem>> = source.observe()
    }
}
