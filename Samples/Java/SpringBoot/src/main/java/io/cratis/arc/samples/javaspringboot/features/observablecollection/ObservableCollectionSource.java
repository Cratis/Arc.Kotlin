// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.observablecollection;

import io.cratis.arc.queries.ObservableState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** Holds the integer-keyed observable collection. */
@Component
public final class ObservableCollectionSource {
    private final Object monitor = new Object();
    private final ObservableState<List<ObservableCollectionItem>> items = new ObservableState<>(List.of(
        new ObservableCollectionItem(1, "One"),
        new ObservableCollectionItem(2, "Two")));

    /**
     * Observes the collection.
     *
     * @return A publisher of the collection.
     */
    public Flow.Publisher<List<ObservableCollectionItem>> observe() {
        return items;
    }

    /**
     * Appends an item.
     *
     * @param id The item identifier.
     * @param label The item label.
     */
    public void add(int id, String label) {
        synchronized (monitor) {
            var updated = new ArrayList<>(items.get());
            updated.add(new ObservableCollectionItem(id, label));
            items.set(List.copyOf(updated));
        }
    }

    /**
     * Removes the matching item.
     *
     * @param id The item identifier.
     */
    public void remove(int id) {
        synchronized (monitor) {
            items.set(items.get().stream().filter(item -> item.id() != id).toList());
        }
    }
}
