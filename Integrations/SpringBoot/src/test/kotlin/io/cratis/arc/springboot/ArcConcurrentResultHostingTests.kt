// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.ExceptionDetailRedactor
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.HttpRequestHandler
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest
import org.springframework.web.context.request.async.WebAsyncUtils

@SpringBootTest(
    classes = [ArcQueryHostingTests.Application::class],
    properties = ["cratis.arc.maximum-request-body-bytes=128", "cratis.arc.expose-exception-details=false"]
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
internal class ArcConcurrentResultHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @ParameterizedTest
    @ValueSource(strings = ["command", "query"])
    fun `hosted result retains its payload and correlation across redispatch`(kind: String) {
        val correlationId = UUID.randomUUID()
        val initial = start(normalRequest(kind).header(CORRELATION_HEADER, correlationId.toString()))
        val manager = WebAsyncUtils.getAsyncManager(initial.request)
        val hostedResult = initial.getAsyncResult(5000)
        assertSame(hostedResult, manager.concurrentResult)
        requireNotNull(manager.concurrentResultContext)[0] = UUID.randomUUID()
        initial.request.removeHeader(CORRELATION_HEADER)
        initial.request.addHeader(CORRELATION_HEADER, UUID.randomUUID().toString())

        val response = mockMvc.perform(asyncDispatch(initial)).andExpect(status().isOk).andReturn().response

        assertCorrelation(response, correlationId)
        val body = objectMapper.readTree(response.contentAsString)
        assertTrue(body["isSuccess"].asBoolean())
        if (kind == "command") {
            assertEquals("handled:classification", body["response"]["message"].asText())
        } else {
            assertEquals("query", body["data"]["transport"].asText())
        }
        assertFalse(manager.hasConcurrentResult())
        assertNull(manager.concurrentResultContext)
    }

    @ParameterizedTest
    @ValueSource(strings = ["command", "query"])
    fun `hosted result retains the explicit payload too large status`(kind: String) {
        val correlationId = UUID.randomUUID()
        val builder = if (kind == "command") post(ROUTE) else request(HttpMethod.valueOf("QUERY"), ROUTE)
        val initial = start(
            builder.contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(129))
                .header(CORRELATION_HEADER, correlationId.toString())
        )
        initial.getAsyncResult(5000)

        val response = mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isContentTooLarge).andReturn().response

        assertCorrelation(response, correlationId)
        assertEquals("malformedRequest", objectMapper.readTree(response.contentAsString)
            ["validationResults"][0]["reason"].asText())
        assertFalse(WebAsyncUtils.getAsyncManager(initial.request).hasConcurrentResult())
    }

    @ParameterizedTest
    @ValueSource(strings = ["command", "query"])
    fun `throwable concurrent result is logged and mapped with the selected correlation`(
        kind: String,
        output: CapturedOutput
    ) {
        assertFailure(kind, IllegalStateException("concurrent-result-control"), "concurrent-result-control", output)
    }

    @ParameterizedTest
    @ValueSource(strings = ["command", "query"])
    fun `null concurrent result uses the unexpected result fallback`(kind: String, output: CapturedOutput) {
        assertFailure(kind, null, "Spring MVC returned an unexpected Arc $kind result.", output)
    }

    @ParameterizedTest
    @ValueSource(strings = ["command", "query"])
    fun `unexpected concurrent result uses the unexpected result fallback`(kind: String, output: CapturedOutput) {
        assertFailure(kind, "not-a-hosted-result", "Spring MVC returned an unexpected Arc $kind result.", output)
    }

    private fun assertFailure(kind: String, value: Any?, expectedLog: String, output: CapturedOutput) {
        val initial = start(normalRequest(kind))
        initial.getAsyncResult(5000)
        mockMvc.perform(asyncDispatch(initial)).andExpect(status().isOk)
        val handler = initial.handler as HttpRequestHandler

        for (useContext in listOf(true, false)) {
            val headerId = UUID.randomUUID()
            val contextId = UUID.randomUUID()
            val expectedId = if (useContext) contextId else headerId
            val servletRequest = MockHttpServletRequest(if (kind == "command") "POST" else "QUERY", ROUTE)
            servletRequest.isAsyncSupported = true
            servletRequest.addHeader(CORRELATION_HEADER, headerId.toString())
            val response = MockHttpServletResponse()
            val manager = WebAsyncUtils.getAsyncManager(servletRequest)
            manager.setAsyncWebRequest(StandardServletAsyncWebRequest(servletRequest, response))
            val deferred = DeferredResult<Any>()
            if (useContext) manager.startDeferredResultProcessing(deferred, contextId)
            else manager.startDeferredResultProcessing(deferred)
            assertTrue(DeferredResults.setResult(deferred, value))
            assertTrue(manager.hasConcurrentResult())
            assertSame(value, manager.concurrentResult)

            handler.handleRequest(servletRequest, response)

            assertEquals(500, response.status)
            assertEquals(MediaType.APPLICATION_JSON_VALUE, response.contentType)
            assertCorrelation(response, expectedId)
            val body = objectMapper.readTree(response.contentAsString)
            assertEquals(ExceptionDetailRedactor.REDACTED_MESSAGE, body["exceptionMessages"][0].asText())
            assertEquals("", body["exceptionStackTrace"].asText())
            assertFalse(body["isSuccess"].asBoolean())
            assertFalse(manager.hasConcurrentResult())
            assertNull(manager.concurrentResultContext)
            assertTrue(output.all.contains(expectedLog), output.all)
            assertTrue(output.all.contains("Arc $kind request failed. correlationId=$expectedId"), output.all)
            if (kind == "query") assertEquals("no-store", response.getHeader("Cache-Control"))
        }
    }

    private fun normalRequest(kind: String): MockHttpServletRequestBuilder =
        if (kind == "command") post(ROUTE).contentType(MediaType.APPLICATION_JSON)
            .content("""{"value":"classification"}""") else get(ROUTE)

    private fun start(builder: MockHttpServletRequestBuilder): MvcResult = mockMvc.perform(builder).andReturn().also {
        assertTrue(it.request.isAsyncStarted)
    }

    private fun assertCorrelation(response: MockHttpServletResponse, correlationId: UUID) {
        assertEquals(correlationId.toString(), response.getHeader(CORRELATION_HEADER))
        assertEquals(correlationId.toString(), objectMapper.readTree(response.contentAsString)["correlationId"].asText())
    }

    private companion object {
        const val ROUTE = "/api/fixtures/java-fixture-command"
        const val CORRELATION_HEADER = "X-Correlation-ID"
    }
}
