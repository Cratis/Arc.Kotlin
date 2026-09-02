// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.chronicle.events.EventContext;
import io.cratis.chronicle.observation.Reducer;

/** Builds {@link TaskView} while retaining the event-source key and exact event-log position. */
@Reducer
public final class TaskViewReducer {
    /** Creates the first tenant-local task state. */
    public TaskView taskCreated(TaskCreated event, TaskView state, EventContext context) {
        return new TaskView(context.getEventSourceId(), event.title(), context.getSequenceNumber());
    }

    /** Applies a rename to the current tenant-local task state. */
    public TaskView taskRenamed(TaskRenamed event, TaskView state, EventContext context) {
        return new TaskView(context.getEventSourceId(), event.title(), context.getSequenceNumber());
    }
}
