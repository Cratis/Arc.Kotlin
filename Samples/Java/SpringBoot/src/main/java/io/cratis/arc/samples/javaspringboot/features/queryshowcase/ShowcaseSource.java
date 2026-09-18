// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.queryshowcase;

import io.cratis.arc.queries.ObservableState;
import io.cratis.arc.samples.javaspringboot.features.SampleTicker;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Publishes both showcase streams from one three-second tick. */
@Component
public final class ShowcaseSource {
    private final ObservableState<ShowcaseItem> latest =
        new ObservableState<>(new ShowcaseItem(1, "Initial", OffsetDateTime.now()));
    private final ObservableState<List<ShowcaseItem>> all = new ObservableState<>(List.of(
        new ShowcaseItem(1, "Alpha", OffsetDateTime.now()),
        new ShowcaseItem(2, "Beta", OffsetDateTime.now()),
        new ShowcaseItem(3, "Gamma", OffsetDateTime.now())));
    private final AtomicInteger tick = new AtomicInteger();

    /**
     * Initializes a new instance of the {@link ShowcaseSource} class.
     *
     * @param ticker The shared sample scheduler.
     */
    public ShowcaseSource(SampleTicker ticker) {
        ticker.everySeconds(3, this::advance);
    }

    /**
     * Observes the newest item.
     *
     * @return A publisher of the newest item.
     */
    public Flow.Publisher<ShowcaseItem> observeLatest() {
        return latest;
    }

    /**
     * Observes the whole list.
     *
     * @return A publisher of the whole list.
     */
    public Flow.Publisher<List<ShowcaseItem>> observeAll() {
        return all;
    }

    private void advance() {
        var current = tick.incrementAndGet();
        var now = OffsetDateTime.now();
        latest.set(new ShowcaseItem(current, "Update #" + current, now));
        all.set(List.of(
            new ShowcaseItem(1, "Alpha (v" + current + ")", now),
            new ShowcaseItem(2, "Beta (v" + current + ")", now),
            new ShowcaseItem(3, "Gamma (v" + current + ")", now),
            new ShowcaseItem(4, "Delta (v" + current + ")", now)));
    }
}
