// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import java.util.Collections
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

internal class ArcCorrelationFilterTests {
    private val properties = ArcProperties()
    private val filter = ArcCorrelationFilter(properties)
    private val request = MockHttpServletRequest("GET", "/plain")
    private val response = MockHttpServletResponse()

    @AfterEach
    fun clearLoggingContext() {
        MDC.remove(ArcCorrelation.LOGGING_KEY)
    }

    @Test
    fun `generates a correlation identifier when the request carries none`() {
        val chain = RecordingChain()

        filter.doFilter(request, response, chain)

        val established = requireNotNull(ArcCorrelation.of(request)).toString()
        assertEquals(established, response.getHeader(CORRELATION_HEADER))
        assertEquals(established, chain.correlationHeader)
        assertEquals(established, chain.loggingValue)
    }

    @Test
    fun `reuses a correlation identifier the request carries`() {
        val supplied = UUID.randomUUID()
        request.addHeader(CORRELATION_HEADER, "  $supplied  ")
        val chain = RecordingChain()

        filter.doFilter(request, response, chain)

        assertEquals(supplied, ArcCorrelation.of(request))
        assertEquals(supplied.toString(), response.getHeader(CORRELATION_HEADER))
        assertEquals(supplied.toString(), chain.correlationHeader)
    }

    @Test
    fun `replaces text that is not a correlation identifier`() {
        request.addHeader(CORRELATION_HEADER, "../../etc/passwd")
        val chain = RecordingChain()

        filter.doFilter(request, response, chain)

        val echoed = requireNotNull(response.getHeader(CORRELATION_HEADER))
        assertNotEquals("../../etc/passwd", echoed)
        assertEquals(UUID.fromString(echoed), ArcCorrelation.of(request))
        assertEquals(echoed, chain.correlationHeader)
    }

    @Test
    fun `presents the effective identifier to every request header accessor`() {
        request.addHeader("Accept-Encoding", "gzip")
        val chain = RecordingChain()

        filter.doFilter(request, response, chain)

        val established = requireNotNull(ArcCorrelation.of(request)).toString()
        assertEquals(listOf(established), chain.correlationHeaderValues)
        assertTrue(chain.headerNames.any { name -> name.equals(CORRELATION_HEADER, ignoreCase = true) })
        assertEquals("gzip", chain.otherHeader)
    }

    @Test
    fun `an asynchronous redispatch keeps the correlation identifier of the request`() {
        val first = RecordingChain()
        val second = RecordingChain()

        filter.doFilter(request, response, first)
        filter.doFilter(request, MockHttpServletResponse(), second)

        assertEquals(first.correlationHeader, second.correlationHeader)
        assertEquals(ArcCorrelation.of(request)?.toString(), second.correlationHeader)
    }

    @Test
    fun `restores the logging context the host owned before the request`() {
        MDC.put(ArcCorrelation.LOGGING_KEY, "host-owned")

        filter.doFilter(request, response, RecordingChain())

        assertEquals("host-owned", MDC.get(ArcCorrelation.LOGGING_KEY))
    }

    @Test
    fun `clears the logging context it established`() {
        filter.doFilter(request, response, RecordingChain())

        assertNull(MDC.get(ArcCorrelation.LOGGING_KEY))
    }

    @Test
    fun `honors a reconfigured correlation header`() {
        properties.correlationHeader = "X-Trace"
        val supplied = UUID.randomUUID()
        request.addHeader("X-Trace", supplied.toString())
        val chain = RecordingChain("X-Trace")

        filter.doFilter(request, response, chain)

        assertEquals(supplied.toString(), response.getHeader("X-Trace"))
        assertEquals(supplied.toString(), chain.correlationHeader)
        assertNull(response.getHeader(CORRELATION_HEADER))
    }

    private class RecordingChain(private val headerName: String = CORRELATION_HEADER) : FilterChain {
        var correlationHeader: String? = null
        var correlationHeaderValues: List<String> = emptyList()
        var headerNames: List<String> = emptyList()
        var otherHeader: String? = null
        var loggingValue: String? = null

        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            val httpRequest = request as HttpServletRequest
            correlationHeader = httpRequest.getHeader(headerName)
            correlationHeaderValues = Collections.list(httpRequest.getHeaders(headerName))
            headerNames = Collections.list(httpRequest.headerNames)
            otherHeader = httpRequest.getHeader("Accept-Encoding")
            loggingValue = MDC.get(ArcCorrelation.LOGGING_KEY)
        }
    }
}
