// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.observablecollectionwithguid;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.UUID;

/**
 * Adds a new item to the UUID-keyed observable collection.
 *
 * @param id The new item identifier.
 * @param label The new item label.
 */
@Command
@AllowAnonymous
public record AddObservableCollectionWithGuidItem(UUID id, String label) {
    /**
     * Handles the command by appending a new item to the collection.
     *
     * @param source The collection state.
     */
    public void handle(ObservableCollectionWithGuidSource source) {
        source.add(id, label);
    }
}
