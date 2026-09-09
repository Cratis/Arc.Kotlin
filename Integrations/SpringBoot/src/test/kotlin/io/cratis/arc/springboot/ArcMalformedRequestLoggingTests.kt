// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.cratis.arc.artifacts.ArcArtifactModule
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Verifies that a body Arc cannot read is recoverable from the server log while the client answer stays generic.
 *
 * The client-facing envelope must never carry parser detail, because that would disclose type structure. The reason
 * is instead attached to a debug event on the handler's own logger, asserted here through the logging API rather
 * than through rendered console text.
 */
@SpringBootTest(classes = [ArcMalformedRequestLoggingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcMalformedRequestLoggingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    private lateinit var commandLog: DebugLogCapture
    private lateinit var queryLog: DebugLogCapture

    @BeforeEach
    fun attachAppenders() {
        commandLog = DebugLogCapture(COMMAND_HANDLER_LOGGER).also(DebugLogCapture::attach)
        queryLog = DebugLogCapture(ArcQueryHttpRequestHandler::class.java.name).also(DebugLogCapture::attach)
    }

    @AfterEach
    fun detachAppenders() {
        commandLog.detach()
        queryLog.detach()
    }

    @Test
    fun `an unreadable command body is logged at debug with the parser failure`() {
        execute(post(COMMAND_ROUTE).json("{"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(content().string(not(containsString("Json"))))
            .andExpect(content().string(not(containsString("end-of-input"))))

        val events = commandLog.events()
        assertEquals(1, events.size, "Expected exactly one command handler log event, got $events")
        val event = events.single()
        assertEquals(Level.DEBUG, event.level)
        val throwable = event.throwableProxy ?: fail("The parser failure must be attached to the event")
        assertTrue(
            throwable.className.startsWith("com.fasterxml.jackson"),
            "Expected the Jackson failure on the event, got ${throwable.className}"
        )
    }

    @Test
    fun `an unreadable query body is logged at debug with the parser failure as the cause`() {
        execute(query(TYPED_ROUTE).json("{"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(content().string(not(containsString("Json"))))

        val events = queryLog.events()
        assertEquals(1, events.size, "Expected exactly one query handler log event, got $events")
        val event = events.single()
        assertEquals(Level.DEBUG, event.level)
        val throwable = event.throwableProxy ?: fail("The binding failure must be attached to the event")
        assertEquals(MalformedQueryRequestException::class.java.name, throwable.className)
        val cause = throwable.cause ?: fail("The binding failure must keep the parser failure as its cause")
        assertTrue(
            cause.className.startsWith("com.fasterxml.jackson"),
            "Expected the Jackson failure as the cause, got ${cause.className}"
        )
    }

    private fun query(route: String): MockHttpServletRequestBuilder = request(HttpMethod.valueOf("QUERY"), route)

    private fun execute(requestBuilder: MockHttpServletRequestBuilder): ResultActions {
        val initial = mockMvc.perform(requestBuilder).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }

    private fun MockHttpServletRequestBuilder.json(value: String): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(value)

    private class DebugLogCapture(loggerName: String) {
        private val logger = LoggerFactory.getLogger(loggerName) as ch.qos.logback.classic.Logger
        private val appender = ListAppender<ILoggingEvent>()
        private val previousLevel = logger.level

        fun attach() {
            appender.start()
            logger.level = Level.DEBUG
            logger.addAppender(appender)
        }

        fun detach() {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }

        fun events(): List<ILoggingEvent> = appender.list.toList()
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun javaFixtureModule(): ArcArtifactModule = JavaFixtureArcArtifactModule()

        @Bean
        fun queryFixtureService(): JavaFixtureArcArtifactModule.QueryFixtureService =
            JavaFixtureArcArtifactModule.QueryFixtureService()
    }

    private companion object {
        const val COMMAND_ROUTE = "/api/fixtures/java-fixture-command"
        const val TYPED_ROUTE = "/api/fixtures/typed-query"
        const val COMMAND_HANDLER_LOGGER = "io.cratis.arc.springboot.ArcCommandHttpRequestHandler"
    }
}
