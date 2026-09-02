// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.queries.Path;
import io.cratis.arc.queries.QueryContext;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Tenant-local task state materialized by Chronicle and exposed through generated Arc queries. */
@io.cratis.arc.artifacts.ReadModel
@io.cratis.chronicle.readModels.ReadModel
@AllowAnonymous
public record TaskView(String id, String title, long eventLogPosition) {
    /** Gets one task from the namespace captured in Arc's query context. */
    @Path("/api/tasks/by-id")
    public static CompletionStage<TaskView> byId(
        String id,
        QueryContext context,
        @FromServices TaskViewReader reader
    ) {
        return reader.byId(requireNamespace(context), id);
    }

    /** Gets all tasks from the namespace captured in Arc's query context. */
    @Path("/api/tasks")
    public static CompletionStage<List<TaskView>> all(
        QueryContext context,
        @FromServices TaskViewReader reader
    ) {
        return reader.all(requireNamespace(context));
    }

    private static String requireNamespace(QueryContext context) {
        var namespace = context.getTenantNamespace();
        if (namespace == null) throw new IllegalStateException("A tenant namespace is required.");
        return namespace;
    }
}
