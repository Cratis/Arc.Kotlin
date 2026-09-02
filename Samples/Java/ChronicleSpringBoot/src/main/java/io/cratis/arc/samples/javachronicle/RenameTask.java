// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.chronicle.EventsWithConcurrencyScopes;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Renames a task using its tenant-local Chronicle read model and exact event-log position. */
@Command
@AllowAnonymous
public record RenameTask(@CommandKey String id, String title, long expectedSequenceNumber) {
    /** Derives the event from the injected model and applies the caller's exact observed position. */
    public CompletionStage<EventsWithConcurrencyScopes> handle(TaskView current) {
        var events = EventsWithConcurrencyScopes.builder()
            .event(id, new TaskRenamed(current.title(), title))
            .expectedSequenceNumber(id, expectedSequenceNumber)
            .build();
        return CompletableFuture.completedFuture(events);
    }
}
