// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.observablecollectionwithguid;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.UUID;

/**
 * Removes an item from the UUID-keyed observable collection.
 *
 * @param id The identifier of the item to remove.
 */
@Command
@AllowAnonymous
public record RemoveObservableCollectionWithGuidItem(UUID id) {
    /**
     * Handles the command by removing the matching item from the collection.
     *
     * @param source The collection state.
     */
    public void handle(ObservableCollectionWithGuidSource source) {
        source.remove(id);
    }
}
