// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.chronicle.slices;

import io.cratis.chronicle.projections.FromEvent;
import io.cratis.chronicle.projections.RemovedWith;
import io.cratis.chronicle.readModels.Passive;
import io.cratis.chronicle.readModels.ReadModel;

/** The Java tab's passive decision model, resolved on demand by the command key. */
@ReadModel
@Passive
@FromEvent(eventType = BookReserved.class)
@RemovedWith(eventType = BookBorrowedFromReservation.class)
@RemovedWith(eventType = ReservationCancelled.class)
@RemovedWith(eventType = ReservationExpired.class)
public class JavaPendingReservation {
    public ISBN isbn = ISBN.Companion.getNOT_SET();
    public MemberId memberId = MemberId.Companion.getNOT_SET();
    public long expiresAt;
}
