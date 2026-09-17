// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.ticker;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import java.time.OffsetDateTime;
import java.util.concurrent.Flow;

/**
 * A live counter that ticks every second.
 *
 * This is the smallest possible observable query: one read model, one static method returning a
 * {@link Flow.Publisher}. Nothing on the client asks for the next value — Arc pushes each emission
 * over the connected transport, so a browser that opened the page simply watches the number climb.
 *
 * @param count The current count value.
 * @param lastUpdated The timestamp of the last update.
 */
@ReadModel
@AllowAnonymous
public record Ticker(int count, OffsetDateTime lastUpdated) {
    /**
     * Observes the live counter value.
     *
     * @param source The source that advances the counter.
     * @return A publisher that emits a new value each second.
     */
    public static Flow.Publisher<Ticker> observe(@FromServices TickerSource source) {
        return source.observe();
    }
}
