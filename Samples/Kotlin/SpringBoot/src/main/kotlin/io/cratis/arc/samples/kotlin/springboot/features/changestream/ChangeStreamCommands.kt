// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.changestream

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous

/**
 * Adds a new item to the change-stream showcase collection.
 *
 * @property id The identifier for the new item.
 * @property label The label for the new item.
 * @property value The numeric value for the new item.
 */
@Command
@AllowAnonymous
public data class AddChangeStreamItem(
    public val id: Int,
    public val label: String,
    public val value: Int
) {
    /** Handles the command by appending the new item to the collection. */
    public fun handle(source: ChangeStreamSource) {
        source.add(id, label, value)
    }
}

/**
 * Updates an existing item in the change-stream showcase collection.
 *
 * @property id The identifier of the item to update.
 * @property label The new label value.
 * @property value The new numeric value.
 */
@Command
@AllowAnonymous
public data class UpdateChangeStreamItem(
    public val id: Int,
    public val label: String,
    public val value: Int
) {
    /** Handles the command by replacing the matching item in the collection. */
    public fun handle(source: ChangeStreamSource) {
        source.update(id, label, value)
    }
}

/**
 * Removes an item from the change-stream showcase collection.
 *
 * @property id The identifier of the item to remove.
 */
@Command
@AllowAnonymous
public data class RemoveChangeStreamItem(public val id: Int) {
    /** Handles the command by filtering out the matching item from the collection. */
    public fun handle(source: ChangeStreamSource) {
        source.remove(id)
    }
}
