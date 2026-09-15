// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.eventSequences.AppendOptions
import io.cratis.chronicle.eventSequences.AppendResult
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.EventSequenceNumber
import io.cratis.chronicle.eventSequences.IEventLog
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

/** Test-only append boundary shared by Kotlin and Java real-pipeline routing tests. */
public class ChronicleRoutingEventSink {
    private val recorded = mutableListOf<EventForEventSourceId>()
    public val events: List<EventForEventSourceId> get() = recorded.toList()
    public var appendCalls: Int = 0
        private set
    public var appendKind: String? = null
        private set

    private val eventLog = mockk<IEventLog>()
    public val eventStore: IEventStore = mockk {
        every { namespace } returns "default"
        every { eventLog } returns this@ChronicleRoutingEventSink.eventLog
    }

    init {
        coEvery { eventLog.append(any<String>(), any<Any>(), any<AppendOptions>()) } answers {
            appendKind = "single"
            record(listOf(routed(firstArg(), secondArg(), thirdArg()))).single()
        }
        coEvery { eventLog.appendMany(any<String>(), any<List<Any>>(), any<AppendOptions>()) } answers {
            appendKind = "plain-batch"
            val id = firstArg<String>()
            val options = thirdArg<AppendOptions>()
            record(secondArg<List<Any>>().map { routed(id, it, options) })
        }
        coEvery {
            eventLog.appendMany(any<List<EventForEventSourceId>>(), any<Map<String, ConcurrencyScope>>(), any<UUID>())
        } answers {
            appendKind = "routed-batch"
            record(firstArg())
        }
    }

    private fun routed(id: String, event: Any, options: AppendOptions): EventForEventSourceId =
        EventForEventSourceId(id, event, causation = options.causation)

    private fun record(events: List<EventForEventSourceId>): List<AppendResult> {
        appendCalls++
        recorded.addAll(events)
        return events.mapIndexed { index, _ ->
            AppendResult(EventSequenceNumber(index.toLong()), emptyList(), emptyList(), true, null)
        }
    }
}
