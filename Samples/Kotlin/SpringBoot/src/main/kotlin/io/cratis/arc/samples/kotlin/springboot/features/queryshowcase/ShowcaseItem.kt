// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.queryshowcase

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.samples.kotlin.springboot.features.SampleTicker
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.stereotype.Component

/** Publishes both showcase streams from one three-second tick. */
@Component
public class ShowcaseSource(ticker: SampleTicker) {
    private val latest: MutableStateFlow<ShowcaseItem> =
        MutableStateFlow(ShowcaseItem(1, "Initial", OffsetDateTime.now()))
    private val all: MutableStateFlow<List<ShowcaseItem>> = MutableStateFlow(
        listOf(
            ShowcaseItem(1, "Alpha", OffsetDateTime.now()),
            ShowcaseItem(2, "Beta", OffsetDateTime.now()),
            ShowcaseItem(3, "Gamma", OffsetDateTime.now())
        )
    )
    private var tick: Int = 0

    init {
        ticker.everySeconds(3) {
            tick++
            val now = OffsetDateTime.now()
            latest.value = ShowcaseItem(tick, "Update #$tick", now)
            all.value = listOf(
                ShowcaseItem(1, "Alpha (v$tick)", now),
                ShowcaseItem(2, "Beta (v$tick)", now),
                ShowcaseItem(3, "Gamma (v$tick)", now),
                ShowcaseItem(4, "Delta (v$tick)", now)
            )
        }
    }

    /** Observes the newest item. */
    public fun observeLatest(): Flow<ShowcaseItem> = latest

    /** Observes the whole list. */
    public fun observeAll(): Flow<List<ShowcaseItem>> = all
}

/**
 * One read model exposing all four query shapes Arc supports.
 *
 * Nothing about a read model decides how it is served — the return type does. A `Flow` becomes an
 * observable subscription; a plain value or list becomes a one-shot GET. Seeing all four next to
 * each other is the quickest way to internalize that rule.
 *
 * @property id The item identifier.
 * @property name The item name.
 * @property updatedAt When the item was last updated.
 */
@ReadModel
@AllowAnonymous
public data class ShowcaseItem(
    public val id: Int,
    public val name: String,
    public val updatedAt: OffsetDateTime
) {
    public companion object {
        /** Observable, single item — streams the latest showcase item. */
        @JvmStatic
        public fun latest(@FromServices source: ShowcaseSource): Flow<ShowcaseItem> = source.observeLatest()

        /** Observable, collection — streams the full list. */
        @JvmStatic
        public fun all(@FromServices source: ShowcaseSource): Flow<List<ShowcaseItem>> = source.observeAll()

        /** One-shot, single item by identifier. */
        @JvmStatic
        public fun byId(id: Int): ShowcaseItem = ShowcaseItem(id, "Item #$id", OffsetDateTime.now())

        /** One-shot, fixed collection. */
        @JvmStatic
        public fun getAll(): List<ShowcaseItem> =
            (1..5).map { index -> ShowcaseItem(index, "Static Item #$index", OffsetDateTime.now()) }
    }
}
