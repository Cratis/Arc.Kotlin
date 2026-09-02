// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.queries.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Queryable task representation. */
@ReadModel
@AllowAnonymous
public record TaskView(String id, String title, boolean completed) {
    /** Gets one task by identifier asynchronously. */
    @Path("/api/tasks/by-id")
    public static CompletionStage<TaskView> byId(String id, @FromServices TaskRepository repository) {
        return CompletableFuture.completedFuture(repository.byId(id));
    }

    /** Gets all tasks. */
    @Path("/api/tasks")
    public static TaskView[] all(@FromServices TaskRepository repository) {
        return repository.all().toArray(TaskView[]::new);
    }

    /** Observes replayable task snapshots without exposing a Kotlin coroutine type. */
    @Path("/api/tasks/observe")
    public static Flow.Publisher<List<TaskView>> observe(@FromServices TaskRepository repository) {
        return repository.observe();
    }
}
