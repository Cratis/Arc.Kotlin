// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authentication

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.tenancy.TenantId
import java.util.Collections
import java.util.LinkedHashMap

/** Explicit request information available to host-neutral authentication handlers. */
public class AuthenticationRequestContext @JvmOverloads constructor(
    headers: Map<String, List<String>> = emptyMap(),
    cookies: Map<String, String> = emptyMap(),
    /** Principal captured by the host before Arc authentication runs. */
    public val principal: ArcPrincipal = ArcPrincipal.anonymous(),
    /** Explicitly selected tenant, when supplied by the request. */
    public val tenant: TenantId? = null
) {
    /** Case-insensitive immutable request headers, preserving value order. */
    public val headers: Map<String, List<String>> = immutableHeaders(headers)

    /** Immutable request cookies. Cookies are input only and never become a principal automatically. */
    public val cookies: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(cookies))

    /** Returns all values for [name] using case-insensitive header matching. */
    public fun headerValues(name: String): List<String> = headers[name].orEmpty()

    /** Returns the first value for [name], when present. */
    public fun header(name: String): String? = headerValues(name).firstOrNull()
}

private fun immutableHeaders(source: Map<String, List<String>>): Map<String, List<String>> {
    val headers = java.util.TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER)
    source.forEach { (name, values) -> headers[name] = java.util.List.copyOf(values) }
    return Collections.unmodifiableMap(headers)
}
