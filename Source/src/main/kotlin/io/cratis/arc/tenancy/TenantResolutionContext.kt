// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.identity.IdentityClaim
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Host-neutral request information used to resolve a tenant.
 *
 * All state is supplied explicitly. The context never reads ambient or thread-local request state.
 */
public class TenantResolutionContext @JvmOverloads constructor(
    headers: Map<String, String> = emptyMap(),
    query: Map<String, String> = emptyMap(),
    /** Request host, optionally including a port. */
    public val host: String? = null,
    claims: List<IdentityClaim> = emptyList(),
    /** Explicit caller principal, when one exists. */
    public val principal: ArcPrincipal? = null
) {
    /** Immutable request headers in caller-supplied order. */
    public val headers: Map<String, String> = immutableMap(headers)

    /** Immutable query parameters in caller-supplied order. */
    public val query: Map<String, String> = immutableMap(query)

    /** Immutable explicit claims in caller-supplied order. */
    public val claims: List<IdentityClaim> = Collections.unmodifiableList(ArrayList(claims))

    private val normalizedHeaders: Map<String, String> = LinkedHashMap<String, String>().also { normalized ->
        headers.forEach { (name, value) -> normalized.putIfAbsent(name.lowercase(Locale.ROOT), value) }
    }

    /** Finds a header using HTTP's case-insensitive header-name semantics. */
    public fun header(name: String): String? = normalizedHeaders[name.lowercase(Locale.ROOT)]

    /** Finds an exact, case-sensitive query parameter. */
    public fun queryParameter(name: String): String? = query[name]

    /**
     * Finds the first nonblank claim of [type]. Explicit [claims] take precedence over claims on [principal].
     */
    public fun claim(type: String): String? = sequenceOf(claims.asSequence(), principal?.claims?.asSequence() ?: emptySequence())
        .flatten()
        .firstOrNull { it.type == type && it.value.isNotBlank() }
        ?.value

    private companion object {
        private fun immutableMap(source: Map<String, String>): Map<String, String> =
            Collections.unmodifiableMap(LinkedHashMap(source))
    }
}
