// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.chronicle.slices;

import io.cratis.chronicle.projections.FromEvent;
import io.cratis.chronicle.projections.FromEventSourceId;
import io.cratis.chronicle.projections.RemovedWith;
import io.cratis.chronicle.readModels.ReadModel;

/** The Java tab's to-do list: an active projection that maps the key with {@code @FromEventSourceId}. */
@ReadModel
@FromEvent(eventType = BookReserved.class)
@RemovedWith(eventType = BookBorrowedFromReservation.class)
@RemovedWith(eventType = ReservationCancelled.class)
@RemovedWith(eventType = ReservationExpired.class)
public class JavaReservationDueForExpiry {
    @FromEventSourceId
    public String id = "";
    public long expiresAt;
}
