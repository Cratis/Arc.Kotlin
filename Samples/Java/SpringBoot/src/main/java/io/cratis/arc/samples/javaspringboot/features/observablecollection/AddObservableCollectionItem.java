// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.observablecollection;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;

/**
 * Adds a new item to the observable collection.
 *
 * @param id The new item identifier.
 * @param label The new item label.
 */
@Command
@AllowAnonymous
public record AddObservableCollectionItem(int id, String label) {
    /**
     * Handles the command by appending a new item to the collection.
     *
     * @param source The collection state.
     */
    public void handle(ObservableCollectionSource source) {
        source.add(id, label);
    }
}
