// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Creates a task by returning a Chronicle event handled entirely by the server. */
@Command
@AllowAnonymous
public record CreateTask(@CommandKey String id, String title) {
    /** Produces the server-handled event through Arc's Java asynchronous command path. */
    public CompletionStage<TaskCreated> handle() {
        return CompletableFuture.completedFuture(new TaskCreated(title));
    }
}
