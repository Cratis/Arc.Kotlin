// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Reads task projections from one explicit Chronicle namespace. */
public interface TaskViewReader {
    /** Gets one task by identifier. */
    CompletionStage<TaskView> byId(String namespace, String id);

    /** Gets every task in the namespace. */
    CompletionStage<List<TaskView>> all(String namespace);
}
