// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.chronicle.slices

import io.cratis.chronicle.concepts.ConceptAs
import io.cratis.chronicle.events.EventType
import io.cratis.chronicle.projections.FromEvent
import io.cratis.chronicle.projections.FromEventSourceId
import io.cratis.chronicle.projections.RemovedWith
import io.cratis.chronicle.readModels.Passive
import io.cratis.chronicle.readModels.ReadModel
import java.time.Instant
import java.util.UUID

// The read models of the vertical-slice Automation page (Reservations/ExpiryManagement), so the
// real-kernel test proves what the Kotlin and Java tabs rely on: projection instances keyed by their
// event source (Chronicle#3924), a passive projection resolved on demand by that key, and closing
// events removing the instance from both. Deadlines are `java.time.Instant`, which the JVM client
// serializes as ISO-8601 strings since Chronicle.Kotlin 6.9.0 (Cratis/Chronicle.Kotlin#104). The
// concepts here implement only Chronicle's ConceptAs: the snippets also implement Arc's so commands
// can take them, which the kernel never sees.

data class ISBN(private val isbn: String) : ConceptAs<String> {
    override val value: String get() = isbn

    companion object {
        val NOT_SET = ISBN("")
    }
}

data class MemberId(private val id: UUID) : ConceptAs<UUID> {
    override val value: UUID get() = id

    companion object {
        val NOT_SET = MemberId(UUID(0, 0))
    }
}

@EventType
data class BookReserved(val isbn: ISBN, val memberId: MemberId, val expiresAt: Instant)

@EventType
data class ReservationCancelled(val isbn: ISBN, val memberId: MemberId)

@EventType
data class BookBorrowedFromReservation(val isbn: ISBN, val memberId: MemberId)

@EventType
data class ReservationExpired(val isbn: ISBN, val memberId: MemberId)

/** The page's to-do list: an active projection that maps the key with `@FromEventSourceId`. */
@ReadModel
@FromEvent(BookReserved::class)
@RemovedWith(BookBorrowedFromReservation::class)
@RemovedWith(ReservationCancelled::class)
@RemovedWith(ReservationExpired::class)
data class ReservationDueForExpiry(
    @FromEventSourceId val id: String = "",
    val expiresAt: Instant = Instant.EPOCH
)

/** The page's passive decision model, resolved on demand by the command key. */
@ReadModel
@Passive
@FromEvent(BookReserved::class)
@RemovedWith(BookBorrowedFromReservation::class)
@RemovedWith(ReservationCancelled::class)
@RemovedWith(ReservationExpired::class)
data class PendingReservation(
    val isbn: ISBN = ISBN.NOT_SET,
    val memberId: MemberId = MemberId.NOT_SET,
    val expiresAt: Instant = Instant.EPOCH
)
