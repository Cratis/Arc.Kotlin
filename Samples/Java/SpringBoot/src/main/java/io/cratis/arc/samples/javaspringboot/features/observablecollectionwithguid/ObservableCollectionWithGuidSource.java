// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.observablecollectionwithguid;

import io.cratis.arc.queries.ObservableState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** Holds the UUID-keyed observable collection. */
@Component
public final class ObservableCollectionWithGuidSource {
    private final Object monitor = new Object();
    private final ObservableState<List<ObservableCollectionWithGuidItem>> items = new ObservableState<>(List.of(
        new ObservableCollectionWithGuidItem(UUID.fromString("11111111-1111-1111-1111-111111111111"), "One"),
        new ObservableCollectionWithGuidItem(UUID.fromString("22222222-2222-2222-2222-222222222222"), "Two")));

    /**
     * Observes the collection.
     *
     * @return A publisher of the collection.
     */
    public Flow.Publisher<List<ObservableCollectionWithGuidItem>> observe() {
        return items;
    }

    /**
     * Appends an item.
     *
     * @param id The item identifier.
     * @param label The item label.
     */
    public void add(UUID id, String label) {
        synchronized (monitor) {
            var updated = new ArrayList<>(items.get());
            updated.add(new ObservableCollectionWithGuidItem(id, label));
            items.set(List.copyOf(updated));
        }
    }

    /**
     * Removes the matching item.
     *
     * @param id The item identifier.
     */
    public void remove(UUID id) {
        synchronized (monitor) {
            items.set(items.get().stream().filter(item -> !item.id().equals(id)).toList());
        }
    }
}
