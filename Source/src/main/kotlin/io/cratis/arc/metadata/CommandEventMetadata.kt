// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/** Host-neutral event defaults declared by a command artifact. */
@JsonPropertyOrder("eventSourceType", "eventStreamType", "eventStreamId", "subject")
public class CommandEventMetadata @JvmOverloads constructor(
    /** Default event-source type, or `null` when the event store owns the fallback. */
    eventSourceType: String?,
    /** Default event-stream type, or `null` when the event store owns the fallback. */
    eventStreamType: String? = null,
    /** Default event-stream identifier, or `null` when the event source identifier is used. */
    eventStreamId: String? = null,
    /** Default event subject, or `null` when the event source identifier is used. */
    subject: String? = null
) {
    /** Validated event-source type. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    public val eventSourceType: String? = eventSourceType.validated("eventSourceType")

    /** Validated event-stream type. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    public val eventStreamType: String? = eventStreamType.validated("eventStreamType")

    /** Validated event-stream identifier. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    public val eventStreamId: String? = eventStreamId.validated("eventStreamId")

    /** Validated event subject. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    public val subject: String? = subject.validated("subject")

    init {
        require(listOf(eventSourceType, eventStreamType, eventStreamId, subject).any { it != null }) {
            "Command event metadata must declare at least one value."
        }
    }
}

private fun String?.validated(name: String): String? {
    if (this == null) return null
    require(isNotBlank()) { "$name cannot be blank." }
    require(none(Char::isISOControl)) { "$name cannot contain control characters." }
    return this
}
