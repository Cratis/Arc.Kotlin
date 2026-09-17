// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.queryshowcase;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.stream.IntStream;

/**
 * One read model exposing all four query shapes Arc supports.
 *
 * Nothing about a read model decides how it is served — the return type does. A
 * {@link Flow.Publisher} becomes an observable subscription; a plain value or list becomes a
 * one-shot GET. Seeing all four next to each other is the quickest way to internalize that rule.
 *
 * @param id The item identifier.
 * @param name The item name.
 * @param updatedAt When the item was last updated.
 */
@ReadModel
@AllowAnonymous
public record ShowcaseItem(int id, String name, OffsetDateTime updatedAt) {
    /**
     * Observable, single item — streams the latest showcase item.
     *
     * @param source The showcase state.
     * @return A publisher of the newest item.
     */
    public static Flow.Publisher<ShowcaseItem> latest(@FromServices ShowcaseSource source) {
        return source.observeLatest();
    }

    /**
     * Observable, collection — streams the full list.
     *
     * @param source The showcase state.
     * @return A publisher of the whole list.
     */
    public static Flow.Publisher<List<ShowcaseItem>> all(@FromServices ShowcaseSource source) {
        return source.observeAll();
    }

    /**
     * One-shot, single item by identifier.
     *
     * @param id The item identifier.
     * @return The matching item.
     */
    public static ShowcaseItem byId(int id) {
        return new ShowcaseItem(id, "Item #" + id, OffsetDateTime.now());
    }

    /**
     * One-shot, fixed collection.
     *
     * @return A fixed list of showcase items.
     */
    public static List<ShowcaseItem> getAll() {
        return IntStream.rangeClosed(1, 5)
            .mapToObj(index -> new ShowcaseItem(index, "Static Item #" + index, OffsetDateTime.now()))
            .toList();
    }
}
