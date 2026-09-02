// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import java.util.UUID
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ArcCommandHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcCommandHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var modules: ArcArtifactModules

    @BeforeEach
    fun resetFixture() {
        JavaFixtureArcArtifactModule.INVOCATIONS.set(0)
        JavaFixtureArcArtifactModule.capturedContext = null
    }

    @Test
    fun `service loaded and Spring modules are deduplicated and Java command returns typed response`() {
        assertEquals(listOf(JavaFixtureArcArtifactModule::class.java), modules.modules.map(Any::javaClass))

        execute(post(COMMAND_ROUTE).json("""{"value":"hello"}"""))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response.message").value("handled:hello"))
            .andExpect(jsonPath("$.response.items").isArray)
            .andExpect(jsonPath("$.response.items", hasSize<Any>(0)))
            .andExpect(jsonPath("$.validationResults", hasSize<Any>(0)))
            .andExpect(jsonPath("$.exceptionMessages", hasSize<Any>(0)))
        assertEquals(1, JavaFixtureArcArtifactModule.INVOCATIONS.get())
    }

    @Test
    fun `validate route runs filters without invoking the handler`() {
        execute(post("$COMMAND_ROUTE/validate").json("""{"value":"hello"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
        assertEquals(0, JavaFixtureArcArtifactModule.INVOCATIONS.get())
    }

    @Test
    fun `malformed or missing body returns parser safe malformed result`() {
        execute(post(COMMAND_ROUTE).json("{"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Json"))))

        execute(post(COMMAND_ROUTE).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
    }

    @Test
    fun `Jakarta validation maps property paths and never invokes the handler`() {
        execute(post(COMMAND_ROUTE).json("""{"value":""}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].severity").value(3))
            .andExpect(jsonPath("$.validationResults[0].reason").value("rule"))
            .andExpect(jsonPath("$.validationResults[0].members[0]").value("value"))
        assertEquals(0, JavaFixtureArcArtifactModule.INVOCATIONS.get())
    }

    @Test
    fun `authorization uses the entry request principal and required servlet roles`() {
        execute(post(SECURED_ROUTE).json("""{"value":"one"}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.isAuthorized").value(false))

        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            "alice",
            "unused",
            listOf(SimpleGrantedAuthority("ROLE_admin"))
        )
        execute(
            post(SECURED_ROUTE)
                .json("""{"value":"one"}""")
                .principal(authentication)
                .with { request -> request.addUserRole("admin"); request }
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isAuthorized").value(true))
    }

    @Test
    fun `correlation and tenant are captured explicitly and correlation is echoed`() {
        val correlationId = UUID.fromString("9f540b48-c9bd-40dd-ae5a-1508348ba877")
        execute(
            post(COMMAND_ROUTE)
                .json("""{"value":"context"}""")
                .header("x-correlation-id", correlationId.toString())
                .header("X-CRATIS-TENANT-ID", "tenant-42")
                .header("X-Ignore-Warnings", "true")
                .header("x-cratis-microservice", "orders")
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Correlation-ID", correlationId.toString()))
            .andExpect(jsonPath("$.correlationId").value(correlationId.toString()))

        val context = requireNotNull(JavaFixtureArcArtifactModule.capturedContext)
        assertEquals("tenant-42", context.tenantId)
        assertEquals("tenant-42", context.tenantNamespace)
    }

    @Test
    fun `allowed severity accepts numeric and enum names and rejects invalid values`() {
        execute(post(COMMAND_ROUTE).json("""{"value":"warning"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].severity").value(2))

        execute(
            post(COMMAND_ROUTE).json("""{"value":"warning"}""").header("X-Allowed-Severity", "2")
        ).andExpect(status().isOk)

        execute(
            post(COMMAND_ROUTE).json("""{"value":"warning"}""").header("x-allowed-severity", "wArNiNg")
        ).andExpect(status().isOk)

        execute(
            post(COMMAND_ROUTE).json("""{"value":"hello"}""").header("X-Allowed-Severity", "not-a-severity")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
    }

    @Test
    fun `literal anonymous introspection endpoints expose deterministic generated metadata`() {
        mockMvc.perform(get("/.cratis/commands"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].name").value("ExplodingCommand"))
            .andExpect(jsonPath("$[1].name").value("JavaFixtureCommand"))
            .andExpect(jsonPath("$[2].name").value("SecuredCommand"))
            .andExpect(jsonPath("$[2].authorization.roles[0]").value("admin"))

        mockMvc.perform(get("/.cratis/queries"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("coexisting"))
            .andExpect(jsonPath("$[0].route").value("/api/fixtures/java-fixture-command"))
            .andExpect(jsonPath("$[4].parameters[0].name").value("name"))
            .andExpect(jsonPath("$[4].parameters[?(@.name == 'dependency')]").isEmpty)
            .andExpect(jsonPath("$[4].transport").value(0))
            .andExpect(jsonPath("$[4].supportsPaging").value(false))
    }

    @Test
    fun `handler exceptions are logged then redacted into the command envelope`() {
        execute(post(EXPLODING_ROUTE).json("""{"value":"boom"}"""))
            .andExpect(status().isInternalServerError)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.exceptionMessages[0]").value(ExceptionDetailRedactor.REDACTED_MESSAGE))
            .andExpect(jsonPath("$.exceptionStackTrace").value(""))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret-java-handler-detail"))))
    }

    private fun execute(requestBuilder: MockHttpServletRequestBuilder): ResultActions {
        val initial = mockMvc.perform(requestBuilder).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }

    private fun MockHttpServletRequestBuilder.json(value: String): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(value)

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun javaFixtureModule(): ArcArtifactModule = JavaFixtureArcArtifactModule()

        @Bean
        fun warningValidator(): CommandValidator<JavaFixtureArcArtifactModule.JavaFixtureCommand> =
            object : CommandValidator<JavaFixtureArcArtifactModule.JavaFixtureCommand> {
                override val commandType = JavaFixtureArcArtifactModule.JavaFixtureCommand::class.java

                override suspend fun validate(
                    command: JavaFixtureArcArtifactModule.JavaFixtureCommand,
                    context: CommandContext
                ): List<ValidationResult> = if (command.value() == "warning") {
                    listOf(ValidationResult(ValidationResultSeverity.Warning, "A warning was produced.", listOf("value")))
                } else {
                    emptyList()
                }
            }
    }

    private companion object {
        const val COMMAND_ROUTE = "/api/fixtures/java-fixture-command"
        const val SECURED_ROUTE = "/api/fixtures/secured-command"
        const val EXPLODING_ROUTE = "/api/fixtures/exploding-command"
    }
}
