// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.correlation.CorrelationIdResolver
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.util.Collections
import java.util.Enumeration
import java.util.UUID
import org.slf4j.MDC

/**
 * Host-wide correlation established for every servlet request, including routes Arc does not own.
 *
 * The Arc correlation filter runs before Spring Security and before Arc authentication, resolves one
 * correlation identifier for the request, and publishes it three ways:
 *
 * - as the [ATTRIBUTE] servlet request attribute, holding a [UUID];
 * - as the configured correlation request header, so downstream code reading that header - Arc's own
 *   endpoints included - observes the same effective value even when the client sent none;
 * - as the configured correlation response header.
 *
 * Read it with [of] from any servlet-thread code, such as an ordinary blocking `@RestController`
 * method. The attribute survives an asynchronous dispatch, so a controller reached through
 * `AsyncContext.dispatch` observes the same identifier.
 */
public object ArcCorrelation {
    /** Servlet request attribute holding the [UUID] correlation identifier established for the request. */
    @JvmField
    public val ATTRIBUTE: String = "${ArcCorrelation::class.java.name}.correlationId"

    /**
     * SLF4J MDC key under which the correlation identifier is published while the filter chain runs.
     *
     * The value is bound to the servlet thread for the duration of the filter invocation only, which
     * is what a logging framework needs and all it is safe for. It is deliberately not a source of
     * coroutine-visible state: a suspending Arc handler resumes on another thread and must take its
     * correlation identifier from `CommandContext` or `QueryContext`. The Arc observability starter
     * publishes the same key for the duration of each coroutine resume through a
     * `ThreadContextElement`.
     */
    public const val LOGGING_KEY: String = "arc.correlation_id"

    /**
     * Returns the correlation identifier established for [request], or `null` when the Arc
     * correlation filter did not run for it.
     */
    @JvmStatic
    public fun of(request: HttpServletRequest): UUID? = request.getAttribute(ATTRIBUTE) as? UUID
}

/**
 * Establishes one correlation identifier for every servlet request.
 *
 * An inbound header value is reused only when it is a UUID; anything else is replaced by a freshly
 * generated identifier. The effective value is always re-emitted in its canonical [UUID.toString]
 * form, so client-controlled text can never reach a response header, a log line, or a diagnostic
 * context.
 */
internal class ArcCorrelationFilter(private val properties: ArcProperties) : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as? HttpServletRequest ?: return chain.doFilter(request, response)
        val httpResponse = response as? HttpServletResponse ?: return chain.doFilter(request, response)
        val headerName = properties.correlationHeader
        val established = ArcCorrelation.of(httpRequest)
        val correlationId = established ?: CorrelationIdResolver.resolveOrCreate(httpRequest.getHeader(headerName))
        val value = correlationId.toString()
        if (established == null) httpRequest.setAttribute(ArcCorrelation.ATTRIBUTE, correlationId)
        if (!httpResponse.isCommitted) httpResponse.setHeader(headerName, value)
        val correlated = if (httpRequest.getHeader(headerName) == value) {
            httpRequest
        } else {
            CorrelatedHttpRequest(httpRequest, headerName, value)
        }
        val previous = MDC.get(ArcCorrelation.LOGGING_KEY)
        MDC.put(ArcCorrelation.LOGGING_KEY, value)
        try {
            chain.doFilter(correlated, httpResponse)
        } finally {
            if (previous == null) MDC.remove(ArcCorrelation.LOGGING_KEY) else MDC.put(ArcCorrelation.LOGGING_KEY, previous)
        }
    }
}

/** Presents the effective correlation identifier as the request's correlation header. */
private class CorrelatedHttpRequest(
    request: HttpServletRequest,
    private val headerName: String,
    private val correlationId: String
) : HttpServletRequestWrapper(request) {
    override fun getHeader(name: String): String? =
        if (name.equals(headerName, ignoreCase = true)) correlationId else super.getHeader(name)

    override fun getHeaders(name: String): Enumeration<String> =
        if (name.equals(headerName, ignoreCase = true)) {
            Collections.enumeration(listOf(correlationId))
        } else {
            super.getHeaders(name)
        }

    override fun getHeaderNames(): Enumeration<String> {
        val names = Collections.list(super.getHeaderNames() ?: Collections.emptyEnumeration())
        if (names.none { name -> name.equals(headerName, ignoreCase = true) }) names.add(headerName)
        return Collections.enumeration(names)
    }
}
