// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.changestream;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;

/**
 * Adds a new item to the change-stream showcase collection.
 *
 * @param id The identifier for the new item.
 * @param label The label for the new item.
 * @param value The numeric value for the new item.
 */
@Command
@AllowAnonymous
public record AddChangeStreamItem(int id, String label, int value) {
    /**
     * Handles the command by appending the new item to the collection.
     *
     * @param source The collection state.
     */
    public void handle(ChangeStreamSource source) {
        source.add(id, label, value);
    }
}
