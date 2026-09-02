// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel as ArcReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.queries.Path
import io.cratis.arc.queries.QueryContext
import io.cratis.chronicle.readModels.ReadModel as ChronicleReadModel

/** Tenant-local task state materialized by Chronicle and exposed through generated Arc queries. */
@ArcReadModel
@ChronicleReadModel
@AllowAnonymous
public data class TaskView(
    public val id: String = "",
    public val title: String = "",
    public val eventLogPosition: Long = 0
) {
    public companion object {
        /** Gets one task from the namespace captured in Arc's query context. */
        @JvmStatic
        @Path("/api/tasks/by-id")
        public suspend fun byId(
            id: String,
            context: QueryContext,
            @FromServices reader: TaskViewReader
        ): TaskView? = reader.byId(requireNamespace(context), id)

        /** Gets all tasks from the namespace captured in Arc's query context. */
        @JvmStatic
        @Path("/api/tasks")
        public suspend fun all(
            context: QueryContext,
            @FromServices reader: TaskViewReader
        ): List<TaskView> = reader.all(requireNamespace(context))

        private fun requireNamespace(context: QueryContext): String =
            requireNotNull(context.tenantNamespace) { "A tenant namespace is required." }
    }
}
