// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;

/**
 * A single daemon scheduler shared by every showcase feature that publishes on a timer.
 *
 * Samples that push without a client acting must keep a lifecycle somewhere. Putting it in one
 * bean means every feature schedules against a scope Spring closes, instead of leaking a thread
 * from a static initializer the way a throwaway demo would.
 */
public final class SampleTicker implements DisposableBean {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "arc-sample-ticker");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Runs an action every period until the application context closes.
     *
     * @param periodSeconds The number of seconds between runs.
     * @param action The action to run.
     */
    public void everySeconds(long periodSeconds, Runnable action) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // A failing sample tick must not stop the scheduler for every other feature.
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
