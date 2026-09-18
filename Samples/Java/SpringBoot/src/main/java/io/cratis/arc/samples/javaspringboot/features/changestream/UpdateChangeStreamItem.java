// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.changestream;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;

/**
 * Updates an existing item in the change-stream showcase collection.
 *
 * @param id The identifier of the item to update.
 * @param label The new label value.
 * @param value The new numeric value.
 */
@Command
@AllowAnonymous
public record UpdateChangeStreamItem(int id, String label, int value) {
    /**
     * Handles the command by replacing the matching item in the collection.
     *
     * @param source The collection state.
     */
    public void handle(ChangeStreamSource source) {
        source.update(id, label, value);
    }
}
