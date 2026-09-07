// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.concepts.ArcEnum
import io.cratis.arc.concepts.Flags
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Pins the API boundary for a flags-style combination a generated client can produce but no JVM constant declares.
 *
 * The generated TypeScript enum for a `@Flags` enum carries an `all<Name>` constant, so a client can compose values
 * with `|`. A combination that no JVM constant declares has nothing to deserialize into, and both the command body
 * and the GET query argument answer that with the ordinary safe malformed envelope rather than a leaked Jackson
 * error.
 */
@SpringBootTest(classes = [ArcFlagsEnumHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcFlagsEnumHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `a command body carrying a declared flag value is accepted`() {
        execute(post(COMMAND_ROUTE).json("""{"permission":2}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
    }

    @Test
    fun `a command body carrying an undeclared combination is a safe malformed request`() {
        execute(post(COMMAND_ROUTE).json("""{"permission":3}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(content().string(not(containsString("Json"))))
            .andExpect(content().string(not(containsString(FlagsFixturePermission::class.java.name))))
    }

    @Test
    fun `a GET query argument carrying a declared flag value is accepted`() {
        execute(get(QUERY_ROUTE).queryParam("permission", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value(2))
    }

    @Test
    fun `a GET query argument carrying an undeclared combination is a safe malformed request`() {
        execute(get(QUERY_ROUTE).queryParam("permission", "3"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(content().string(not(containsString("Json"))))
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
        fun flagsFixtureModule(): ArcArtifactModule = FlagsFixtureArcArtifactModule()
    }

    private companion object {
        const val COMMAND_ROUTE = "/api/flags/flags-fixture-command"
        const val QUERY_ROUTE = "/api/flags/permission-query"
    }
}

/** Flags fixture enum whose named constants deliberately leave `3` undeclared. */
@Flags
internal enum class FlagsFixturePermission(private val wireValue: Int) : ArcEnum {
    None(0),
    Read(1),
    Write(2),
    Execute(4);

    override fun value(): Int = wireValue
}

internal data class FlagsFixtureCommand(val permission: FlagsFixturePermission)

internal class FlagsFixtureArcArtifactModule : ArcArtifactModule(
    listOf(FlagsFixtureCommandHandler()),
    listOf(FlagsFixtureQueryPerformer())
)

private const val FLAGS_FIXTURE_DECLARING_TYPE = "io.cratis.arc.springboot.FlagsFixture"

private fun anonymous() = AuthorizationMetadata(true, null, emptyList(), emptyList())

internal class FlagsFixtureCommandHandler : CommandHandler {
    override val commandType: Class<*> = FlagsFixtureCommand::class.java

    override val metadata: CommandDescriptor = CommandDescriptor(
        "FlagsFixtureCommand",
        FlagsFixtureCommand::class.java.name,
        emptyList(),
        RouteOptions(),
        listOf("flags"),
        anonymous(),
        null,
        false
    )

    override suspend fun invoke(context: CommandContext): Any? =
        (context.command as FlagsFixtureCommand).permission.value()
}

internal class FlagsFixtureQueryPerformer : QueryPerformer {
    override val fullyQualifiedName: FullyQualifiedQueryName =
        FullyQualifiedQueryName("$FLAGS_FIXTURE_DECLARING_TYPE.permissionQuery")

    override val descriptor: QueryDescriptor = QueryDescriptor(
        "permissionQuery",
        FLAGS_FIXTURE_DECLARING_TYPE,
        "java.lang.Object",
        listOf(ParameterDescriptor("permission", FlagsFixturePermission::class.java.name, false, false)),
        RouteOptions(QUERY_PATH),
        fullyQualifiedName.value,
        listOf("flags"),
        anonymous(),
        QUERY_PATH,
        QueryHttpMethodType.GET,
        QueryTransportType.REQUEST_RESPONSE,
        false,
        false,
        false
    )

    override suspend fun perform(context: QueryContext): Any? =
        (context.request.arguments["permission"] as FlagsFixturePermission).value()
}

private const val QUERY_PATH = "/api/flags/permission-query"
