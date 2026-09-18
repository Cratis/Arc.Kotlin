// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.observablecollectionwithguid;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Flow;

/**
 * An item in the UUID-keyed observable collection.
 *
 * A JVM {@link UUID} reaches the browser as a {@code Guid} from `@cratis/fundamentals`, not as a
 * bare string. Adding and removing items here is what proves the generated client still matches
 * rows by identity after that conversion.
 *
 * @param id The item identifier.
 * @param label The item label.
 */
@ReadModel
@AllowAnonymous
public record ObservableCollectionWithGuidItem(UUID id, String label) {
    /**
     * Observes the current collection, pushing updates when items are added or removed.
     *
     * @param source The collection state.
     * @return A publisher of the collection.
     */
    public static Flow.Publisher<List<ObservableCollectionWithGuidItem>> all(
        @FromServices ObservableCollectionWithGuidSource source) {
        return source.observe();
    }
}
