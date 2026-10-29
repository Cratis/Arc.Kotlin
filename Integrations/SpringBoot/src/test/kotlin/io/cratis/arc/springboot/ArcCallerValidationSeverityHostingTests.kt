// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.springboot.JavaCallerSeverityArcArtifactModule.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ArcCallerValidationSeverityHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcCallerValidationSeverityHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun resetInvocations() {
        INVOCATIONS.values.forEach { it.set(0) }
    }

    @Test
    fun `callers cannot relax errors on execute or validate by default`() {
        listOf("3", "Error", "error").forEach { header ->
            listOf("", "/validate").forEach { suffix ->
                mockMvc.severityRequest("plain", "error", header, suffix).expectSeverityError(3)
            }
        }
        assertEquals(0, INVOCATIONS.getValue(PlainSeverityCommand::class.java).get())
    }

    @Test
    fun `warnings remain relaxable and unknown headers can request stricter validation`() {
        listOf("2", "3").forEach { header ->
            mockMvc.severityRequest("plain", "warning", header).andExpect(status().isOk)
        }
        listOf("0", "Unknown").forEach { header ->
            mockMvc.severityRequest("plain", "information", header).expectSeverityError(1)
        }
    }

    @Test
    fun `unknown severity results retain existing nonblocking behaviour`() {
        mockMvc.severityRequest("plain", "unknown", "3").andExpect(status().isOk)
    }

    @Test
    fun `invalid headers are ignored instead of producing malformed requests`() {
        listOf("not-a-severity", "4", "999", "-1", "").forEach { header ->
            mockMvc.severityRequest("plain", "error", header).expectSeverityError(3)
            mockMvc.severityRequest("plain", "hello", header).andExpect(status().isOk)
        }
    }

    @Test
    fun `command opt in permits errors only with a usable header and validate never invokes handler`() {
        mockMvc.severityRequest("opted-in", "error", "3").andExpect(status().isOk)
        assertEquals(1, INVOCATIONS.getValue(OptedInSeverityCommand::class.java).get())
        mockMvc.severityRequest("opted-in", "error").expectSeverityError(3)
        mockMvc.severityRequest("opted-in", "error", "3", "/validate").andExpect(status().isOk)
        assertEquals(1, INVOCATIONS.getValue(OptedInSeverityCommand::class.java).get())
    }

    @Test
    fun `command opt in can lower the maximum`() {
        mockMvc.severityRequest("stricter", "warning", "2").expectSeverityError(2)
    }

    @Test
    fun `warnings as errors floor blocks hostile headers even with a command opt in`() {
        listOf("warnings-as-errors", "opted-in-warnings-as-errors").forEach { command ->
            listOf("Warning", "wArNiNg", "1", "2", "3").forEach { header ->
                listOf("", "/validate").forEach { suffix ->
                    mockMvc.severityRequest(command, "warning", header, suffix).expectSeverityError(2)
                }
            }
        }
        assertEquals(0, INVOCATIONS.getValue(WarningsAsErrorsSeverityCommand::class.java).get())
        assertEquals(0, INVOCATIONS.getValue(OptedInWarningsAsErrorsSeverityCommand::class.java).get())
    }

    @Test
    fun `warnings as errors floor preserves a stricter caller threshold`() {
        listOf("", "/validate").forEach { suffix ->
            mockMvc.severityRequest("warnings-as-errors", "information", "0", suffix).expectSeverityError(1)
            mockMvc.severityRequest("warnings-as-errors", "information", "3", suffix).andExpect(status().isOk)
        }
    }

    @Test
    fun `invalid header retains warnings as errors floor`() {
        mockMvc.severityRequest("warnings-as-errors", "warning", "not-a-severity").expectSeverityError(2)
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean
        fun callerSeverityModule(): ArcArtifactModule = JavaCallerSeverityArcArtifactModule()

        @Bean
        fun plainValidator(): CommandValidator<PlainSeverityCommand> =
            severityValidator(PlainSeverityCommand::class.java) { it.value() }

        @Bean
        fun optedInValidator(): CommandValidator<OptedInSeverityCommand> =
            severityValidator(OptedInSeverityCommand::class.java) { it.value() }

        @Bean
        fun stricterValidator(): CommandValidator<StricterSeverityCommand> =
            severityValidator(StricterSeverityCommand::class.java) { it.value() }

        @Bean
        fun warningsAsErrorsValidator(): CommandValidator<WarningsAsErrorsSeverityCommand> =
            severityValidator(WarningsAsErrorsSeverityCommand::class.java) { it.value() }

        @Bean
        fun optedInWarningsAsErrorsValidator(): CommandValidator<OptedInWarningsAsErrorsSeverityCommand> =
            severityValidator(OptedInWarningsAsErrorsSeverityCommand::class.java) { it.value() }
    }
}

internal fun MockMvc.severityRequest(command: String, value: String, header: String? = null, suffix: String = ""): ResultActions {
    val builder = post("/api/fixtures/$command-severity-command$suffix")
        .contentType(MediaType.APPLICATION_JSON).content("""{"value":"$value"}""")
    if (header != null) builder.header("X-Allowed-Severity", header)
    val initial = perform(builder).andExpect(request().asyncStarted()).andReturn()
    return perform(asyncDispatch(initial))
}

internal fun ResultActions.expectSeverityError(severity: Int): ResultActions =
    andExpect(status().isBadRequest)
        .andExpect(jsonPath("$.validationResults[0].severity").value(severity))
        .andExpect(jsonPath("$.validationResults[0].reason").value("rule"))

private fun <T : Any> severityValidator(type: Class<T>, value: (T) -> String): CommandValidator<T> =
    object : CommandValidator<T> {
        override val commandType = type

        override suspend fun validate(command: T, context: CommandContext): List<ValidationResult> {
            val severity = when (value(command)) {
                "error" -> ValidationResultSeverity.Error
                "warning" -> ValidationResultSeverity.Warning
                "information" -> ValidationResultSeverity.Information
                "unknown" -> ValidationResultSeverity.Unknown
                else -> return emptyList()
            }
            return listOf(ValidationResult(severity, "A validation rule was reported.", listOf("value")))
        }
    }
