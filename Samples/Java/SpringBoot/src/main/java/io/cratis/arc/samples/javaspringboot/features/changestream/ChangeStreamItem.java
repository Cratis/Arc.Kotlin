// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.changestream;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * An item in the change-stream showcase collection.
 *
 * Add, update and remove an item and watch what arrives on the wire. Arc does not resend the whole
 * list for a one-item edit — it computes a change set against the previous emission, so the client
 * receives the delta and reconstructs the collection locally.
 *
 * @param id The unique identifier of the item.
 * @param label A descriptive label for the item.
 * @param value A numeric value associated with the item.
 */
@ReadModel
@AllowAnonymous
public record ChangeStreamItem(int id, String label, int value) {
    /**
     * Observes the full collection, pushing every change to subscribers.
     *
     * @param source The collection state.
     * @return A publisher of the collection.
     */
    public static Flow.Publisher<List<ChangeStreamItem>> all(@FromServices ChangeStreamSource source) {
        return source.observe();
    }
}
