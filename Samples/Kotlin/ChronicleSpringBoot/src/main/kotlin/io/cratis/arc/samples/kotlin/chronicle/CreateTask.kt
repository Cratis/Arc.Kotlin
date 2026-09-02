// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.authorization.AllowAnonymous

/** Creates a task by returning a Chronicle event handled entirely by the server. */
@Command
@AllowAnonymous
public data class CreateTask(
    @CommandKey public val id: String,
    public val title: String
) {
    /** Produces the event appended to the command-key event source. */
    public fun handle(): TaskCreated = TaskCreated(title)
}
