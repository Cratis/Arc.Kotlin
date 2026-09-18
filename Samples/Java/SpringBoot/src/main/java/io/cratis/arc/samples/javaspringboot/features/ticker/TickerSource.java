// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.ticker;

import io.cratis.arc.queries.ObservableState;
import io.cratis.arc.samples.javaspringboot.features.SampleTicker;
import java.time.OffsetDateTime;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** Holds the live counter and advances it once a second. */
@Component
public final class TickerSource {
    private final ObservableState<Ticker> state = new ObservableState<>(new Ticker(0, OffsetDateTime.now()));

    /**
     * Initializes a new instance of the {@link TickerSource} class.
     *
     * @param ticker The shared sample scheduler.
     */
    public TickerSource(SampleTicker ticker) {
        ticker.everySeconds(1, () -> state.set(new Ticker(state.get().count() + 1, OffsetDateTime.now())));
    }

    /**
     * Observes the live counter value.
     *
     * @return A publisher of the counter.
     */
    public Flow.Publisher<Ticker> observe() {
        return state;
    }
}
