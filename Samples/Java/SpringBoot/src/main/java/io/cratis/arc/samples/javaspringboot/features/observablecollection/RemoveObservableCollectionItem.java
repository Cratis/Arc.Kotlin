// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.observablecollection;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;

/**
 * Removes an item from the observable collection.
 *
 * @param id The identifier of the item to remove.
 */
@Command
@AllowAnonymous
public record RemoveObservableCollectionItem(int id) {
    /**
     * Handles the command by removing the matching item from the collection.
     *
     * @param source The collection state.
     */
    public void handle(ObservableCollectionSource source) {
        source.remove(id);
    }
}
