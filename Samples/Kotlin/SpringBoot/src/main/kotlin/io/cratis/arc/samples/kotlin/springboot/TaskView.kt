// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.queries.Path
import kotlinx.coroutines.flow.Flow

/** Queryable task representation. */
@ReadModel
@AllowAnonymous
public data class TaskView(public val id: String, public val title: String, public val completed: Boolean) {
    public companion object {
        /** Gets one task by identifier. */
        @JvmStatic
        @Path("/api/tasks/by-id")
        public fun byId(id: String, @FromServices repository: TaskRepository): TaskView? = repository.byId(id)

        /** Gets all tasks. */
        @JvmStatic
        @Path("/api/tasks")
        public fun all(@FromServices repository: TaskRepository): List<TaskView> = repository.all()

        /** Observes all tasks. */
        @JvmStatic
        @Path("/api/tasks/observe")
        public fun observe(@FromServices repository: TaskRepository): Flow<List<TaskView>> = repository.observe()
    }
}
