// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ValidationFailure
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

internal abstract class CommandValidationFailureHostingContract(private val development: Boolean) {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var fixture: Fixture
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val correlation = UUID.randomUUID().toString()

    @BeforeEach
    fun reset() { fixture.invocations.set(0); fixture.begins.set(0); fixture.completions.set(0) }

    @ParameterizedTest
    @ValueSource(strings = ["", "/validate"])
    fun `filter validation exception is pure 400 and payload state remains client visible`(suffix: String) {
        val result = send("filter", false, suffix, 400)
        assertValidation(result)
        assertEquals(0, fixture.invocations.get())
        assertEquals(if (suffix.isEmpty()) 1 else 0, fixture.begins.get())
        assertEquals(fixture.begins.get(), fixture.completions.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["invoke", "response", "complete"])
    fun `execution boundary validation exceptions are pure 400`(stage: String) {
        assertValidation(send(stage, false, "", 400))
        assertEquals(1, fixture.invocations.get())
        assertEquals(1, fixture.completions.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/validate"])
    fun `ordinary filter exception remains 500 with profile specific redaction`(suffix: String) {
        val result = send("filter", true, suffix, 500)
        assertEquals(0, result.path("validationResults").size())
        assertOrdinary(result)
        assertEquals(0, fixture.invocations.get())
    }

    @Test
    fun `mixed authorization validation and ordinary errors retain 403 priority`() {
        val result = send("unauthorized", false, "", 403)
        assertFalse(result.path("isAuthorized").booleanValue())
        assertEquals("prior denial", result.path("authorizationFailureReason").stringValue())
        assertEquals("prior validation", result.path("validationResults").path(0).path("message").stringValue())
        assertPayload(result.path("validationResults").path(1))
        assertOrdinary(result)
    }

    @Test
    fun `mixed validation and ordinary errors retain 400 priority`() {
        val result = send("mixed", false, "", 400)
        assertTrue(result.path("isAuthorized").booleanValue())
        assertEquals("prior validation", result.path("validationResults").path(0).path("message").stringValue())
        assertPayload(result.path("validationResults").path(1))
        assertOrdinary(result)
    }

    private fun assertValidation(result: JsonNode) {
        assertTrue(result.path("isAuthorized").booleanValue())
        assertFalse(result.path("isValid").booleanValue())
        assertFalse(result.path("hasExceptions").booleanValue())
        assertEquals(0, result.path("exceptionMessages").size())
        assertEquals("", result.path("exceptionStackTrace").stringValue())
        assertEquals(1, result.path("validationResults").size())
        assertPayload(result.path("validationResults").path(0))
    }
    private fun assertPayload(payload: JsonNode) {
        assertEquals(3, payload.path("severity").intValue())
        assertEquals("safe feedback", payload.path("message").stringValue())
        assertEquals("value", payload.path("members").path(0).stringValue())
        assertEquals("client-visible state", payload.path("state").path("data").stringValue())
        assertEquals("applicationReason", payload.path("reason").stringValue())
        assertEquals("client-visible detail", payload.path("reasonDetail").stringValue())
    }
    private fun assertOrdinary(result: JsonNode) {
        assertTrue(result.path("hasExceptions").booleanValue())
        assertEquals(if (development) "ordinary secret" else ExceptionDetailRedactor.REDACTED_MESSAGE,
            result.path("exceptionMessages").path(0).stringValue())
        val trace = result.path("exceptionStackTrace").stringValue()
        if (development) assertTrue(trace.contains("IllegalStateException: ordinary secret"))
        else assertEquals("", trace)
    }
    private fun send(stage: String, ordinary: Boolean, suffix: String, expected: Int): JsonNode {
        val response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/validation/failure$suffix"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").header("X-Correlation-ID", correlation)
            .POST(HttpRequest.BodyPublishers.ofString("""{"stage":"$stage","ordinary":$ordinary}""")).build(),
            HttpResponse.BodyHandlers.ofString())
        assertEquals(expected, response.statusCode(), response.body())
        assertEquals(correlation, response.headers().firstValue("X-Correlation-ID").orElseThrow())
        assertFalse(response.body().contains("marker exception secret"))
        if (!development) assertFalse(response.body().contains("ordinary secret"))
        val envelope = mapper.readTree(response.body())
        assertEquals(correlation, envelope.path("correlationId").stringValue())
        assertFalse(envelope.path("isSuccess").booleanValue())
        assertFalse(envelope.has("response"))
        return envelope
    }

    data class TestCommand(val stage: String, val ordinary: Boolean)
    private class Signal
    private class Failure : RuntimeException("marker exception secret"), ValidationFailure {
        override val validationResults = listOf(ValidationResult.error("safe feedback", listOf("value"),
            mapOf("data" to "client-visible state"), "applicationReason", "client-visible detail"))
    }
    class Fixture {
        val invocations = AtomicInteger()
        val begins = AtomicInteger()
        val completions = AtomicInteger()
        fun fail(command: TestCommand): Nothing = throw if (command.ordinary) IllegalStateException("ordinary secret") else Failure()
        fun module(): ArcArtifactModule = object : ArcArtifactModule(listOf(object : CommandHandler {
            override val commandType: Class<*> = TestCommand::class.java
            override val metadata = CommandDescriptor("failure", commandType.name, location = listOf("validation"),
                authorization = AuthorizationMetadata(allowAnonymous = true))
            override suspend fun invoke(context: CommandContext): Any {
                invocations.incrementAndGet()
                val command = context.command as TestCommand
                if (command.stage == "invoke") fail(command)
                if (command.stage == "mixed" || command.stage == "unauthorized") return CommandResult<Void>(
                    context.correlationId, isAuthorized = command.stage != "unauthorized",
                    validationResults = listOf(ValidationResult.error("prior validation")),
                    exceptionMessages = listOf("ordinary secret"),
                    exceptionStackTrace = IllegalStateException("ordinary secret").stackTraceToString(),
                    authorizationFailureReason = if (command.stage == "unauthorized") "prior denial" else ""
                )
                return if (command.stage == "response") Signal() else "discarded"
            }
        }), emptyList()) {}
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean fun fixture(): Fixture = Fixture()
        @Bean fun validationFailureModule(fixture: Fixture): ArcArtifactModule = fixture.module()
        @Bean fun failureFilter(fixture: Fixture): CommandFilter = CommandFilter { context ->
            val command = context.command
            if (command is TestCommand && command.stage == "filter") fixture.fail(command)
            CommandResult.success(context.correlationId)
        }
        @Bean fun failureScope(fixture: Fixture): CommandExecutionScope = object : CommandExecutionScope {
            override fun begin(context: CommandContext) { fixture.begins.incrementAndGet() }
            override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                fixture.completions.incrementAndGet()
                val command = context.command
                if (command is TestCommand && command.stage in listOf("complete", "mixed", "unauthorized")) fixture.fail(command)
                return null
            }
        }
        @Bean fun failureResponse(fixture: Fixture): CommandResponseValueHandler = object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean = value is Signal
            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> = fixture.fail(context.command as TestCommand)
        }
    }
}
