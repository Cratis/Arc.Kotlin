// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.ticker

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.samples.kotlin.springboot.features.SampleTicker
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.stereotype.Component

/** Holds the live counter and advances it once a second. */
@Component
public class TickerSource(ticker: SampleTicker) {
    private val state: MutableStateFlow<Ticker> = MutableStateFlow(Ticker(0, OffsetDateTime.now()))

    init {
        ticker.everySeconds(1) { state.value = Ticker(state.value.count + 1, OffsetDateTime.now()) }
    }

    /** Observes the live counter value. */
    public fun observe(): Flow<Ticker> = state
}

/**
 * A live counter that ticks every second.
 *
 * This is the smallest possible observable query: one read model, one static method returning a
 * [Flow]. Nothing on the client asks for the next value — Arc pushes each emission over the
 * connected transport, so a browser that opened the page simply watches the number climb.
 *
 * @property count The current count value.
 * @property lastUpdated The timestamp of the last update.
 */
@ReadModel
@AllowAnonymous
public data class Ticker(public val count: Int, public val lastUpdated: OffsetDateTime) {
    public companion object {
        /** Observes the live counter value. */
        @JvmStatic
        public fun observe(@FromServices source: TickerSource): Flow<Ticker> = source.observe()
    }
}
