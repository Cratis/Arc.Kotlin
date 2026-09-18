// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.changestream;

import io.cratis.arc.queries.ObservableState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** Holds the mutable showcase collection behind the change-stream feature. */
@Component
public final class ChangeStreamSource {
    private final Object monitor = new Object();
    private final ObservableState<List<ChangeStreamItem>> items = new ObservableState<>(List.of(
        new ChangeStreamItem(1, "Alpha", 10),
        new ChangeStreamItem(2, "Beta", 20),
        new ChangeStreamItem(3, "Gamma", 30)));

    /**
     * Observes the collection.
     *
     * @return A publisher of the collection.
     */
    public Flow.Publisher<List<ChangeStreamItem>> observe() {
        return items;
    }

    /**
     * Appends an item.
     *
     * @param id The item identifier.
     * @param label The item label.
     * @param value The item value.
     */
    public void add(int id, String label, int value) {
        synchronized (monitor) {
            var updated = new ArrayList<>(items.get());
            updated.add(new ChangeStreamItem(id, label, value));
            items.set(List.copyOf(updated));
        }
    }

    /**
     * Replaces the matching item.
     *
     * @param id The item identifier.
     * @param label The new label.
     * @param value The new value.
     */
    public void update(int id, String label, int value) {
        synchronized (monitor) {
            items.set(items.get().stream()
                .map(item -> item.id() == id ? new ChangeStreamItem(id, label, value) : item)
                .toList());
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
