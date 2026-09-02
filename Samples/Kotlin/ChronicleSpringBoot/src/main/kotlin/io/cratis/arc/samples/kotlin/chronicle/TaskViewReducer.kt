// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.observation.Reducer

/** Builds [TaskView] while retaining the event-source key and exact event-log position. */
@Reducer
public class TaskViewReducer {
    /** Creates the first tenant-local task state. */
    public fun taskCreated(event: TaskCreated, state: TaskView?, context: EventContext): TaskView = TaskView(
        id = context.eventSourceId,
        title = event.title,
        eventLogPosition = context.sequenceNumber
    )

    /** Applies a rename to the current tenant-local task state. */
    public fun taskRenamed(event: TaskRenamed, state: TaskView?, context: EventContext): TaskView = TaskView(
        id = context.eventSourceId,
        title = event.title,
        eventLogPosition = context.sequenceNumber
    )
}
