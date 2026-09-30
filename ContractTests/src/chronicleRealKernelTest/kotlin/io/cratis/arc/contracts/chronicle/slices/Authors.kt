// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.chronicle.slices

import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.events.EventType
import io.cratis.chronicle.observation.Reducer
import io.cratis.chronicle.readModels.ReadModel

// The author list behind the Kotlin and Java tabs of the full-stack capstone and the vertical-slice
// State View page: a reducer keyed by the event source, observed live through the materialized
// read models and readable as a snapshot with readModels.getInstances.

@EventType
data class AuthorRegistered(val name: String = "")

@ReadModel
data class Author(val id: String = "", val name: String = "")

@Reducer
class AuthorReducer {
    fun registered(event: AuthorRegistered, state: Author?, context: EventContext): Author =
        Author(id = context.eventSourceId, name = event.name)
}
