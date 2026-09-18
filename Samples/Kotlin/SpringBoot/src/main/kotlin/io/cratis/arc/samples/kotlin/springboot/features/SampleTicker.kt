// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.DisposableBean

/**
 * A single daemon scheduler shared by every showcase feature that publishes on a timer.
 *
 * Samples that push without a client acting must keep a lifecycle somewhere. Putting it in one
 * bean means every feature schedules against a scope Spring closes, instead of leaking a thread
 * from a static initializer the way a throwaway demo would.
 */
public class SampleTicker : DisposableBean {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "arc-sample-ticker").apply { isDaemon = true }
    }

    /** Runs [action] every [period] seconds until the application context closes. */
    public fun everySeconds(period: Long, action: () -> Unit) {
        scheduler.scheduleAtFixedRate({ runCatching(action) }, period, period, TimeUnit.SECONDS)
    }

    override fun destroy() {
        scheduler.shutdownNow()
    }
}
