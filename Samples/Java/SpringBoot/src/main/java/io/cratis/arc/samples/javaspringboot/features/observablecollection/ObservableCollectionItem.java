// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.observablecollection;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * An item in the integer-keyed observable collection.
 *
 * The identifier type decides how Arc tracks set identity across emissions. This feature and its
 * UUID twin exist so the difference between a numeric key and a {@code Guid} key is visible rather
 * than assumed.
 *
 * @param id The item identifier.
 * @param label The item label.
 */
@ReadModel
@AllowAnonymous
public record ObservableCollectionItem(int id, String label) {
    /**
     * Observes the current collection, pushing updates when items are added or removed.
     *
     * @param source The collection state.
     * @return A publisher of the collection.
     */
    public static Flow.Publisher<List<ObservableCollectionItem>> all(@FromServices ObservableCollectionSource source) {
        return source.observe();
    }
}
