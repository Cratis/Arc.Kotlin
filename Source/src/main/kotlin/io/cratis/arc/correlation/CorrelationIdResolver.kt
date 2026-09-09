// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.correlation

import java.util.UUID

/**
 * Resolves the correlation identifier a host carries across a transport boundary.
 *
 * Arc represents a correlation identifier as a [UUID]. A host reads a candidate value from its
 * transport - an HTTP header, a message property - and turns it into the single identifier that
 * every execution belonging to that request shares.
 *
 * A candidate arriving from a client is untrusted text. [parse] accepts only text that is a UUID and
 * returns `null` for anything else, so a host never propagates client-controlled text into a
 * response header, a log line, or a diagnostic context. A correlation identifier is diagnostic only:
 * it never carries authority and is never used for authentication, authorization, or tenant
 * selection.
 *
 * This type is host-agnostic and does not depend on any HTTP or messaging framework.
 */
public object CorrelationIdResolver {
    /**
     * Parses [value] into a correlation identifier.
     *
     * Surrounding whitespace is ignored. Returns `null` when [value] is `null` or is not a UUID.
     */
    @JvmStatic
    public fun parse(value: String?): UUID? = value
        ?.trim()
        ?.let { candidate -> runCatching { UUID.fromString(candidate) }.getOrNull() }

    /**
     * Returns the correlation identifier that [value] represents, or a newly generated one when it
     * represents none.
     */
    @JvmStatic
    public fun resolveOrCreate(value: String?): UUID = parse(value) ?: UUID.randomUUID()
}
