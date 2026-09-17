// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.changestream;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;

/**
 * Removes an item from the change-stream showcase collection.
 *
 * @param id The identifier of the item to remove.
 */
@Command
@AllowAnonymous
public record RemoveChangeStreamItem(int id) {
    /**
     * Handles the command by filtering out the matching item from the collection.
     *
     * @param source The collection state.
     */
    public void handle(ChangeStreamSource source) {
        source.remove(id);
    }
}
