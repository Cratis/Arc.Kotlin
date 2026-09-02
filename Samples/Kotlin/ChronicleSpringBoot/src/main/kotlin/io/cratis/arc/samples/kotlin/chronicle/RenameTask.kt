// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.chronicle.EventsWithConcurrencyScopes

/** Renames a task using its tenant-local Chronicle read model and exact event-log position. */
@Command
@AllowAnonymous
public data class RenameTask(
    @CommandKey public val id: String,
    public val title: String,
    public val expectedSequenceNumber: Long
) {
    /** Derives the event from the injected current model and applies the caller's exact observed position. */
    public fun handle(current: TaskView): EventsWithConcurrencyScopes = EventsWithConcurrencyScopes.builder()
        .event(id, TaskRenamed(current.title, title))
        .expectedSequenceNumber(id, expectedSequenceNumber)
        .build()
}
