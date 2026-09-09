// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance

import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import kotlinx.coroutines.flow.MutableStateFlow

/** Only the genuine StateFlow source and performer plumbing for the Java-owned composition tests. */
public class ObservableCompositionSource(initial: List<*>, name: FullyQualifiedQueryName) {
    private val state = MutableStateFlow(initial.toList())
    private val queryPerformer = object : QueryPerformer {
        override val descriptor = QueryDescriptor(
            "observe", "Composition", List::class.java.name,
            transport = QueryTransportType.OBSERVABLE, isEnumerable = true
        )
        override val fullyQualifiedName = name
        override suspend fun perform(context: QueryContext): Any = state
    }

    /** Returns a performer whose actual result is the owned StateFlow, without converting it to a publisher. */
    public fun performer(): QueryPerformer = queryPerformer

    /** Publishes the next distinct source value; the Java caller owns all processing acknowledgements. */
    public fun update(value: List<*>) {
        state.value = value.toList()
    }
}
