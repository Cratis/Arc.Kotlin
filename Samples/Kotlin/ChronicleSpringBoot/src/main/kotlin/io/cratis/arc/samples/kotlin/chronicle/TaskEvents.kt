// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.chronicle.events.EventType

/** Records creation of a task. */
@EventType
public data class TaskCreated(public val title: String = "")

/** Records a rename and the tenant-local title from which it was derived. */
@EventType
public data class TaskRenamed(
    public val previousTitle: String = "",
    public val title: String = ""
)
